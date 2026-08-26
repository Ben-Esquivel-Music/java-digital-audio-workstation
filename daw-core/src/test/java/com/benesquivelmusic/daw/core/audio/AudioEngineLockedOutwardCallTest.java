package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.audio.harness.HeadlessAudioBackend;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.Transport.ChangeKind;
import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioBackendException;
import com.benesquivelmusic.daw.sdk.audio.AudioBlock;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * Story 316 re-review — the behaviour behind {@code AudioEngine}'s
 * "nothing outward runs under {@code lifecycleLock}" invariant, and the
 * ladder's unwind on the {@code Error} paths.
 *
 * <h2>E1 — the third outward seam</h2>
 * <p>{@code releaseTransportClock()} used to run INSIDE the critical section.
 * It reaches {@link Transport#setRealTimeClockActive(boolean)} with
 * {@code false}, which drains any queued seek inline and fires the
 * transport's {@link ChangeKind#POSITION} observers — arbitrary consumers the
 * application registers. The application's audio controller binds device
 * events from a {@code synchronized} method whose reconfigure path calls the
 * engine's lifecycle methods, so an outward call under the lock closes the
 * cycle [lifecycle lock → controller monitor] against [controller monitor →
 * lifecycle lock].</p>
 *
 * <p>{@link #theRtClockReleaseIsDeliveredWithTheLifecycleLockReleased()} is
 * that deadlock in miniature: the transport observer — standing in for the
 * app layer — asks ANOTHER thread to complete an engine lifecycle call and
 * records whether it could. Under the old inline release the observer runs
 * with the lock held, the probe thread blocks on it, and the verdict is
 * {@code false}.</p>
 *
 * <h2>E4 — the ladder's Error paths</h2>
 * <p>{@code openLadder}'s hop unwind used to catch {@code RuntimeException}
 * only, so a rung that took a native handle and then threw an {@code Error}
 * kept holding the device. {@code closeFailedHop}'s inner catch was
 * {@code RuntimeException}-only too, so a {@code close()} that threw an
 * {@code Error} REPLACED the hop failure with a teardown failure.</p>
 *
 * <p>Widening that inner catch fixed the masking, and the masking only —
 * which is the half these tests still assert. What it deliberately does NOT
 * mean is that the walk continues: a rung that had already reached
 * {@code open()} and could not then give its handle back may still hold the
 * device, so {@code closeFailedHop} reports the non-release and
 * {@code openLadder} ABANDONS the walk rather than open a fallback rung
 * beside it. An unreleased handle is unreleased whatever the close threw, so
 * the {@code Error} case and the {@code RuntimeException} case behave
 * identically here. A rung refused BEFORE {@code open()} holds no device and
 * still falls through.</p>
 *
 * <h2>Determinism</h2>
 * <p>No sleeps. Waits use one generous guard budget
 * ({@value #GUARD_BUDGET_MILLIS} ms) on a latch; a test that needs a seek to
 * stay queued first stalls the pump inside {@code sink}.</p>
 */
class AudioEngineLockedOutwardCallTest {

    private static final AudioFormat FORMAT = new AudioFormat(44_100.0, 2, 16, 512);

    /** Guard budget for cross-thread waits — generous, never inner-inflated. */
    private static final long GUARD_BUDGET_MILLIS = 5_000L;

    private static StreamingProvision provisionOf(AudioBackend... backends) {
        List<BackendStreamRung> ladder = java.util.Arrays.stream(backends)
                .map(b -> new BackendStreamRung(b, DeviceId.defaultFor(b.name())))
                .toList();
        return new StreamingProvision(backends[0].name(), ladder);
    }

    // ── E1: the RT-clock release is an OUTWARD seam, delivered unlocked ──

    @Test
    void theRtClockReleaseIsDeliveredWithTheLifecycleLockReleased() {
        StallableBackend backend = new StallableBackend("Stalling");
        backend.stallSink = true;
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(backend));
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();
        awaitPumpStalledInSink(backend);
        transport.play();
        // Queued behind the claim, so the release below is forced to drain it
        // — which is what fires the observer.
        transport.setPositionInBeats(24.0);
        assertThat(transport.getPositionInBeats()).isZero();

        AtomicBoolean observerFired = new AtomicBoolean();
        AtomicBoolean lifecycleWasReachable = new AtomicBoolean();
        transport.addChangeListener(kind -> {
            if (kind != ChangeKind.POSITION || !observerFired.compareAndSet(false, true)) {
                return;
            }
            // Stand-in for the app layer re-entering the engine from its own
            // monitor: a SECOND thread must be able to complete a lifecycle
            // call while this observer is on the stack.
            lifecycleWasReachable.set(anotherThreadCanCompleteALifecycleCall(engine));
        });

        engine.stopAudioOutput();

        assertThat(observerFired)
                .as("the release must actually have drained the queued seek and fired"
                        + " POSITION — otherwise this test proves nothing")
                .isTrue();
        assertThat(lifecycleWasReachable)
                .as("the RT-clock release reaches an app-registered transport observer,"
                        + " so it must be delivered with lifecycleLock RELEASED; holding"
                        + " it here is the [lifecycle lock → controller monitor] half of"
                        + " a deadlock whose other half is the audio controller's"
                        + " synchronized reconfigure path")
                .isTrue();
        assertThat(transport.getPositionInBeats())
                .as("deferring the release must not lose the seek it owes")
                .isEqualTo(24.0);
        assertThat(transport.isRealTimeClockActive()).isFalse();
    }

    /**
     * A close-then-reopen inside ONE critical section records a release
     * (from the close) that only arrives once the reopen's pump is already
     * running. Delivering it would un-claim a transport that pump drives —
     * and, worse, DRAIN the pump's queued seek on the lifecycle thread while
     * the pump advances the same transport, which is the exact interleaving
     * the claim exists to prevent.
     *
     * <p>The discriminating observation is the THREAD the drain runs on: an
     * inline release drains on the caller of {@code startAudioInputOutput},
     * while the legitimate drain belongs to {@code engine-render-pump}. Both
     * fire {@link ChangeKind#POSITION}, so only the thread tells them apart.
     * </p>
     */
    @Test
    void aCloseThenReopenInOneCriticalSectionDoesNotDrainTheReopenedPumpsSeek() {
        StallableBackend backend = new StallableBackend("Duplex");
        backend.stallSink = true;
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(backend));
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();
        awaitPumpStalledInSink(backend);
        transport.play();
        transport.setPositionInBeats(24.0); // queued behind the claim
        assertThat(transport.getPositionInBeats()).isZero();

        Thread caller = Thread.currentThread();
        AtomicBoolean drainedOnTheCallingThread = new AtomicBoolean();
        transport.addChangeListener(kind -> {
            if (kind == ChangeKind.POSITION && Thread.currentThread() == caller) {
                drainedOnTheCallingThread.set(true);
            }
        });

        engine.startAudioInputOutput();

        assertThat(drainedOnTheCallingThread)
                .as("the reopen's pump owns the clock by the time the close's recorded"
                        + " release is delivered, so that release must be dropped rather"
                        + " than draining the seek inline against a live render thread")
                .isFalse();
        assertThat(transport.isRealTimeClockActive())
                .as("the reopen's claim must survive the stop's deferred release")
                .isTrue();
        engine.stopAudioOutput();
        assertThat(transport.isRealTimeClockActive()).isFalse();
    }

    // ── G3: the SAME seam on the OTHER lock (graphLock) ─────────────────

    /**
     * {@code setGraph} hands the RT-clock claim from the outgoing transport to
     * the incoming one. The outgoing RELEASE is the identical outward seam E1
     * removed from {@code lifecycleLock}: it drains a queued seek inline and
     * fires {@code POSITION} on app-registered consumers ({@code TransportVM}
     * is the live production registrant). Running it inside
     * {@code synchronized (graphLock)} closes the same inversion one lock
     * over — and {@code EngineBinder.bind}/{@code unbind} call {@code setGraph}
     * from inside their own monitor, so the app layer really does re-enter.
     */
    @Test
    void theGraphSwapsOutgoingClockReleaseIsDeliveredWithTheGraphLockReleased() {
        StallableBackend backend = new StallableBackend("GraphSwap");
        backend.stallSink = true;
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(backend));
        Transport outgoing = new Transport();
        engine.setGraph(outgoing, null, null);
        engine.startAudioOutput();
        awaitPumpStalledInSink(backend);
        outgoing.play();
        // Queued behind the claim the running stream holds, so the hand-over
        // below is forced to drain it — which is what fires the observer.
        outgoing.setPositionInBeats(24.0);
        assertThat(outgoing.getPositionInBeats()).isZero();

        AtomicBoolean observerFired = new AtomicBoolean();
        AtomicBoolean graphLockWasReachable = new AtomicBoolean();
        outgoing.addChangeListener(kind -> {
            if (kind != ChangeKind.POSITION || !observerFired.compareAndSet(false, true)) {
                return;
            }
            graphLockWasReachable.set(anotherThreadCanCompleteAGraphCall(engine));
        });

        engine.setGraph(new Transport(), null, null);

        assertThat(observerFired)
                .as("the hand-over must actually have drained the queued seek and fired"
                        + " POSITION — otherwise this test proves nothing")
                .isTrue();
        assertThat(graphLockWasReachable)
                .as("the outgoing transport's release reaches an app-registered observer,"
                        + " so it must be delivered with graphLock RELEASED; holding the"
                        + " monitor across it is the same [engine lock -> app observer]"
                        + " inversion the lifecycle lock defers its three seams for")
                .isTrue();
        assertThat(outgoing.getPositionInBeats())
                .as("deferring the release must not lose the seek it owes")
                .isEqualTo(24.0);
        assertThat(outgoing.isRealTimeClockActive())
                .as("and the hand-over must still have completed by the time setGraph"
                        + " returns — the deferral is past the monitor, not past the call")
                .isFalse();
        engine.stopAudioOutput();
    }

    /**
     * The OTHER release {@code setGraph} owes: when no stream is driving, the
     * INCOMING transport is released rather than claimed, and that release
     * drains and notifies exactly like the outgoing one. It is a separate
     * branch, so it needs its own proof.
     */
    @Test
    void theGraphSwapsIncomingClockReleaseIsDeliveredWithTheGraphLockReleased() {
        AudioEngine engine = new AudioEngine(FORMAT);
        Transport incoming = new Transport();
        // A transport arriving with a stale claim — exactly what setGraph's
        // false branch exists to clear — and one seek queued behind it.
        incoming.setRealTimeClockActive(true);
        incoming.play();
        incoming.setPositionInBeats(24.0);
        assertThat(incoming.getPositionInBeats()).isZero();

        AtomicBoolean observerFired = new AtomicBoolean();
        AtomicBoolean graphLockWasReachable = new AtomicBoolean();
        incoming.addChangeListener(kind -> {
            if (kind != ChangeKind.POSITION || !observerFired.compareAndSet(false, true)) {
                return;
            }
            graphLockWasReachable.set(anotherThreadCanCompleteAGraphCall(engine));
        });

        engine.setGraph(incoming, null, null);

        assertThat(observerFired)
                .as("no stream is driving, so the incoming transport must be RELEASED —"
                        + " draining its queued seek and firing POSITION")
                .isTrue();
        assertThat(graphLockWasReachable)
                .as("that release is the same outward seam and must also be delivered"
                        + " with graphLock released")
                .isTrue();
        assertThat(incoming.getPositionInBeats()).isEqualTo(24.0);
        assertThat(incoming.isRealTimeClockActive()).isFalse();
    }

    // ── E4: the ladder unwinds Error hops too ────────────────────────────

    @Test
    void aRungWhoseOpenThrowsAnErrorGivesItsHandleBackAndAbandonsTheWalk() {
        StallableBackend failing = new StallableBackend("ErrorRung");
        failing.openError = new AssertionError("driver blew up mid-open");
        StallableBackend fallback = new StallableBackend("Fallback");
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(failing, fallback));

        assertThatThrownBy(engine::startAudioOutput)
                .as("an Error is not a device refusal; it propagates as-is")
                .isSameAs(failing.openError);

        assertThat(failing.closeAttempts.get())
                .as("the rung may have taken a native handle before throwing, so the"
                        + " handle is given back exactly as it is for a RuntimeException"
                        + " hop — this is the leak the unwind exists for")
                .isEqualTo(1);
        assertThat(fallback.openAttempts.get())
                .as("an Error means do-not-continue: the walk is abandoned rather than"
                        + " allocating a second rung's buffers after (say) an OOM")
                .isZero();
        assertThat(engine.isStreamOpen()).isFalse();
    }

    @Test
    void aCloseThatThrowsAnErrorDoesNotMaskTheHopFailureButStillAbandonsTheWalk() {
        StallableBackend failing = new StallableBackend("BadClose");
        failing.failOpen = true;
        failing.closeError = new AssertionError("close blew up too");
        StallableBackend fallback = new StallableBackend("Fallback");
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(failing, fallback));

        assertThatThrownBy(engine::startAudioOutput)
                .as("the rung reached open() and its handle was NOT released, whatever the"
                        + " close threw — a fallback rung opened beside it would be the"
                        + " second backend on one device")
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("BadClose")
                .hasMessageContaining("could not be released")
                .satisfies(refusal -> {
                    assertThat(refusal.getCause())
                            .as("the surviving half: a close-thrown Error must never"
                                    + " REPLACE the hop failure")
                            .isInstanceOf(AudioBackendException.class)
                            .hasMessage("open refused by BadClose");
                    assertThat(refusal.getCause().getSuppressed())
                            .as("…it travels suppressed on that cause instead")
                            .contains(failing.closeError);
                });

        assertThat(failing.closeAttempts.get())
                .as("the release was still attempted on the failed hop")
                .isEqualTo(1);
        assertThat(fallback.openAttempts.get())
                .as("the walk is abandoned rather than opening beside an unreleased handle")
                .isZero();
        assertThat(engine.isStreamOpen()).isFalse();
        engine.stopAudioOutput();
    }

    // ── E3: setFormat is serialized with the lifecycle transitions ───────

    @Test
    void setFormatWaitsForAnInFlightLifecycleTransition() throws Exception {
        StallableBackend outgoing = new StallableBackend("Outgoing");
        StallableBackend incoming = new StallableBackend("Incoming");
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(outgoing));
        engine.startAudioOutput();
        // Leaves the engine STOPPED with the handle still tracked, which is
        // the one state where a provision swap and a setFormat are both legal
        // — so what is measured below is the LOCK, not the running-engine
        // refusal both of them also carry.
        engine.stop();

        CountDownLatch handBackEntered = new CountDownLatch(1);
        CountDownLatch releaseHandBack = new CountDownLatch(1);
        outgoing.onClose = () -> {
            handBackEntered.countDown();
            await(releaseHandBack, "the test's release of the stalled hand-back");
        };
        Thread swapper = Thread.ofPlatform().daemon(true).name("stalled-swap")
                .start(() -> engine.setStreamingProvision(provisionOf(incoming)));
        await(handBackEntered, "the outgoing backend's hand-back close");

        CountDownLatch formatStored = new CountDownLatch(1);
        Thread reformatter = Thread.ofPlatform().daemon(true).name("reformat")
                .start(() -> {
                    engine.setFormat(new AudioFormat(48_000.0, 2, 16, 512));
                    formatStored.countDown();
                });
        try {
            assertThat(formatStored.await(250, TimeUnit.MILLISECONDS))
                    .as("setFormat mutates the very field the locked open path reads to"
                            + " size the render pipeline, the buffer pool and the pump's"
                            + " planes, so it must not land while a lifecycle transition"
                            + " is in flight")
                    .isFalse();
        } finally {
            releaseHandBack.countDown();
        }

        swapper.join(GUARD_BUDGET_MILLIS);
        reformatter.join(GUARD_BUDGET_MILLIS);
        assertThat(formatStored.getCount())
                .as("once the transition completes, the waiting setFormat proceeds")
                .isZero();
        assertThat(engine.getFormat().sampleRate()).isEqualTo(48_000.0);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /**
     * Runs a lifecycle call on a fresh thread and reports whether it
     * COMPLETED within the guard budget. {@code start()} is the cheapest
     * choice: it takes {@code lifecycleLock}, finds the engine already
     * running, and returns {@code false} without touching anything.
     */
    private static boolean anotherThreadCanCompleteALifecycleCall(AudioEngine engine) {
        CountDownLatch completed = new CountDownLatch(1);
        Thread probe = Thread.ofPlatform().daemon(true).name("lifecycle-probe")
                .start(() -> {
                    engine.start();
                    completed.countDown();
                });
        try {
            return completed.await(GUARD_BUDGET_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            probe.interrupt();
        }
    }

    /**
     * As {@link #anotherThreadCanCompleteALifecycleCall(AudioEngine)}, for the
     * OTHER lock. {@code setTracks} is the cheapest {@code graphLock} taker:
     * it swaps one field of the published snapshot and returns, calling
     * nothing outward.
     */
    private static boolean anotherThreadCanCompleteAGraphCall(AudioEngine engine) {
        CountDownLatch completed = new CountDownLatch(1);
        Thread probe = Thread.ofPlatform().daemon(true).name("graph-lock-probe")
                .start(() -> {
                    engine.setTracks(List.of());
                    completed.countDown();
                });
        try {
            return completed.await(GUARD_BUDGET_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            probe.interrupt();
        }
    }

    private static void awaitPumpStalledInSink(StallableBackend backend) {
        await(backend.sinkEntered, "the pump's first sink call");
    }

    private static void await(CountDownLatch latch, String what) {
        try {
            if (!latch.await(GUARD_BUDGET_MILLIS, TimeUnit.MILLISECONDS)) {
                fail("Timed out after " + GUARD_BUDGET_MILLIS + " ms awaiting " + what);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail("Interrupted awaiting " + what);
        }
    }

    // ── Test double ─────────────────────────────────────────────────────

    /**
     * {@link HeadlessAudioBackend} decorator with opt-in open / close faults —
     * including {@code Error}-typed ones, which is what the E4 tests need —
     * a stallable {@code sink}, and an {@link #onOpen} hook the E3 test uses
     * to hold a ladder walk open inside the lifecycle lock.
     */
    private static final class StallableBackend implements AudioBackend {

        private final HeadlessAudioBackend delegate = new HeadlessAudioBackend();
        private final String name;
        final AtomicInteger openAttempts = new AtomicInteger();
        final AtomicInteger closeAttempts = new AtomicInteger();
        final CountDownLatch sinkEntered = new CountDownLatch(1);
        private final CountDownLatch neverReleased = new CountDownLatch(1);
        volatile boolean failOpen;
        volatile boolean stallSink;
        volatile Error openError;
        volatile Error closeError;
        volatile Runnable onOpen = () -> { };
        volatile Runnable onClose = () -> { };

        StallableBackend(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            return true;
        }

        @Override
        public List<AudioDeviceInfo> listDevices() {
            return delegate.listDevices();
        }

        @Override
        public void open(DeviceId device,
                         com.benesquivelmusic.daw.sdk.audio.AudioFormat format,
                         int bufferFrames) {
            openAttempts.incrementAndGet();
            onOpen.run();
            if (openError != null) {
                throw openError;
            }
            if (failOpen) {
                throw new AudioBackendException("open refused by " + name);
            }
            delegate.open(device, format, bufferFrames);
        }

        @Override
        public Flow.Publisher<AudioBlock> inputBlocks() {
            return delegate.inputBlocks();
        }

        @Override
        public void sink(AudioBlock block) {
            if (stallSink) {
                sinkEntered.countDown();
                try {
                    neverReleased.await(); // until pump.stop() interrupts
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            delegate.sink(block);
        }

        @Override
        public void awaitSinkCapacity(long timeoutNanos) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        /**
         * Story 316 review: capture truth comes from the delegate, which is
         * a full-duplex headless stand-in. Without this override the
         * interface default ({@code 0}) would make every record-path test
         * here fail the engine's REQUIRED-open verification, which is not
         * what any of them is about.
         */
        @Override
        public int openedInputChannels() {
            return delegate.openedInputChannels();
        }

        @Override
        public void close() {
            closeAttempts.incrementAndGet();
            onClose.run();
            if (closeError != null) {
                throw closeError;
            }
            delegate.close();
        }
    }
}
