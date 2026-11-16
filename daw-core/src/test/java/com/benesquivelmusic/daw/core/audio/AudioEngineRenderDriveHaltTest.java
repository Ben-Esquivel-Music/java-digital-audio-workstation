package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.audio.health.AudioFailureCategory;
import com.benesquivelmusic.daw.core.audio.health.AudioFailureSample;
import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The honest exit, engine side, through the real wiring (story 337, rulings
 * E8, E10 and E69): an engine streaming on a {@link MockAudioBackend} whose
 * render drive halts after a sustained render fault reports the dead drive
 * through {@link AudioEngine#isRenderDriveAlive()} while the stream still
 * counts as open, and the next {@link AudioEngine#startAudioOutput()} reaps it
 * and opens a fresh drive instead of returning early on {@code RUNNING} —
 * Play is never wedged. {@link AudioEngine#isRenderDriveHalted()} tells the
 * drive that halted itself apart from a paused, closed, reaped or resumed
 * one, and {@link AudioEngine#closeHaltedStream()} closes only that stream
 * and never waits for the lifecycle lock. The pump reads the engine's own
 * health record, so the counts and samples asserted here are the ones the
 * application's drain will read.
 */
class AudioEngineRenderDriveHaltTest {

    /** 22 consecutive faulted blocks halt the drive at 2048 frames and 44.1 kHz (ruling E8). */
    private static final AudioFormat FORMAT = new AudioFormat(44_100.0, 2, 16, 2048);

    /**
     * Guard budget — generous, never inner-inflated. The halt needs 22 block
     * periods of the mock's full-period pacing, about one second. It also
     * bounds the whole lock race of
     * {@link #closingTheHaltedStreamNeverWaitsForAnOpenOnAnotherThread()},
     * whose three inner waits sum to {@code 3 × INNER_WAIT_MILLIS}.
     */
    private static final long GUARD_BUDGET_MILLIS = 10_000L;

    /** One bounded inner wait of the lock race; three of them stay under the guard. */
    private static final long INNER_WAIT_MILLIS = 2_000L;

    private AudioEngine engine;

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.stopAudioOutput();
            engine.stop();
        }
    }

    @Test
    void aHaltedRenderDriveIsReportedDeadAndReapedByTheNextStart() {
        MockAudioBackend backend = new MockAudioBackend();
        AtomicBoolean failing = new AtomicBoolean();
        engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provision(backend));
        engine.setRecordingCallback((inputBuffer, numFrames) -> {
            if (failing.get()) {
                throw new IllegalStateException("an insert fails every block");
            }
        });
        engine.startAudioOutput();
        assertThat(engine.isRenderDriveAlive()).isTrue();
        long epochAtFirstOpen = engine.audioHealth().renderDriveEpoch();

        failing.set(true);
        awaitCondition(() -> !engine.isRenderDriveAlive(), "the render drive halts honestly");

        assertThat(engine.isStreamOpen())
                .as("the halted stream still holds its handle until it is reaped")
                .isTrue();
        assertThat(engine.audioHealth().total(AudioFailureCategory.RENDER_HALTED)).isEqualTo(1);
        assertThat(engine.audioHealth().total(AudioFailureCategory.RENDER))
                .isEqualTo(EngineStreamPump.haltThreshold(FORMAT));

        failing.set(false);
        engine.startAudioOutput();

        assertThat(engine.isRenderDriveAlive())
                .as("the next start reaped the dead drive and opened a fresh one")
                .isTrue();
        assertThat(engine.audioHealth().renderDriveEpoch()).isEqualTo(epochAtFirstOpen + 1);
        long beatsAtReopen = engine.audioHealth().renderDriveBeats();
        awaitCondition(() -> engine.audioHealth().renderDriveBeats() > beatsAtReopen + 2,
                "the reopened drive renders and beats");
        assertThat(engine.audioHealth().total(AudioFailureCategory.RENDER_HALTED))
                .as("the halted drive's counts outlive the reap")
                .isEqualTo(1);
    }

    @Test
    void onlyARunningStreamWithARunningPumpIsALiveRenderDrive() {
        MockAudioBackend backend = new MockAudioBackend();
        engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provision(backend));

        assertThat(engine.isRenderDriveAlive()).as("no stream").isFalse();

        engine.startAudioOutput();
        assertThat(engine.isRenderDriveAlive()).isTrue();

        engine.pauseAudioOutput();
        assertThat(engine.isStreamOpen()).isTrue();
        assertThat(engine.isRenderDriveAlive()).as("a paused stream renders nothing").isFalse();

        engine.startAudioOutput();
        assertThat(engine.isRenderDriveAlive()).isTrue();

        engine.stopAudioOutput();
        assertThat(engine.isRenderDriveAlive()).as("a closed stream").isFalse();
    }

    // ── The halted drive and its own close (story 337, ruling E69) ───────

    @Test
    void aHaltedDriveReadsAsHaltedOnlyUntilItIsReapedPausedOrClosed() {
        AtomicBoolean failing = new AtomicBoolean();
        engine = haltableEngine(new MockAudioBackend(), failing);
        assertThat(engine.isRenderDriveHalted()).as("no stream").isFalse();
        engine.startAudioOutput();
        assertThat(engine.isRenderDriveHalted()).as("a live drive").isFalse();

        halt(failing);
        assertThat(engine.isRenderDriveHalted()).isTrue();
        engine.pauseAudioOutput();
        assertThat(engine.isStreamPaused()).isTrue();
        assertThat(engine.isRenderDriveHalted())
                .as("a halted stream paused after the halt is no longer the halted drive")
                .isFalse();

        engine.startAudioOutput();
        assertThat(engine.isRenderDriveAlive()).as("the resume built a fresh drive").isTrue();
        halt(failing);
        engine.startAudioOutput();
        assertThat(engine.isRenderDriveAlive()).as("Play reaped the halted drive and reopened").isTrue();
        assertThat(engine.isRenderDriveHalted()).as("a reaped and reopened stream").isFalse();

        halt(failing);
        engine.stopAudioOutput();
        assertThat(engine.isStreamOpen()).isFalse();
        assertThat(engine.isRenderDriveHalted()).as("a closed stream").isFalse();
    }

    /**
     * Story 337, ruling E69: a drive that outlived a stop whose join timed out
     * and then halted itself is not the open stream's halted drive — the
     * stream is no longer running, so the halt owes no close.
     */
    @Test
    void aDriveThatHaltsAfterAStopGaveUpOnItsJoinIsNotTheHaltedDrive() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean blockNext = new AtomicBoolean();
        engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provision(new MockAudioBackend()));
        engine.setRecordingCallback((inputBuffer, numFrames) -> {
            if (blockNext.compareAndSet(true, false)) {
                inside.countDown();
                awaitIgnoringInterrupts(release);
                throw new InternalError("the capture path failed after the stop gave up on it");
            }
        });
        engine.startAudioOutput();
        try {
            blockNext.set(true);
            assertThat(inside.await(GUARD_BUDGET_MILLIS, TimeUnit.MILLISECONDS))
                    .as("the render drive is blocked inside a block").isTrue();

            engine.stop();
            assertThat(engine.isStreamOpen()).as("fixture: the stop left the stream releasing").isFalse();
            release.countDown();
            awaitCondition(() -> engine.audioHealth().total(AudioFailureCategory.RENDER_HALTED) == 1,
                    "the drive the stop could not join halts itself");

            assertThat(engine.isRenderDriveHalted()).as("the stream is no longer running").isFalse();
            assertThat(engine.isStreamOpen()).isFalse();
        } finally {
            release.countDown();
        }
    }

    @Test
    void closingTheHaltedStreamClosesIt() {
        AtomicBoolean failing = new AtomicBoolean();
        MockAudioBackend backend = new MockAudioBackend();
        engine = haltableEngine(backend, failing);
        engine.startAudioOutput();
        halt(failing);

        assertThat(engine.closeHaltedStream()).isEqualTo(AudioEngine.HaltedStreamClose.CLOSED);

        assertThat(engine.isStreamOpen()).isFalse();
        assertThat(backend.isOpen()).as("the halted stream's handle was released").isFalse();
        assertThat(engine.isRenderDriveHalted()).isFalse();
    }

    @Test
    void closingTheHaltedStreamLeavesAStreamReopenedSinceTheHaltOpen() {
        AtomicBoolean failing = new AtomicBoolean();
        engine = haltableEngine(new MockAudioBackend(), failing);
        engine.startAudioOutput();
        halt(failing);
        engine.startAudioOutput();

        assertThat(engine.closeHaltedStream())
                .as("Play reaped the halted stream and reopened; the late close owes nothing")
                .isEqualTo(AudioEngine.HaltedStreamClose.NOT_HALTED);

        assertThat(engine.isStreamOpen()).isTrue();
        assertThat(engine.isRenderDriveAlive()).isTrue();
    }

    @Test
    void closingTheHaltedStreamLeavesAStreamResumedSinceTheHaltOpen() {
        AtomicBoolean failing = new AtomicBoolean();
        engine = haltableEngine(new MockAudioBackend(), failing);
        engine.startAudioOutput();
        halt(failing);
        Object generationAtTheHalt = engine.getStreamGeneration();
        engine.pauseAudioOutput();
        engine.startAudioOutput();
        assertThat(engine.getStreamGeneration())
                .as("a resume keeps the stream generation — why the fence is the pump's own halt")
                .isSameAs(generationAtTheHalt);

        assertThat(engine.closeHaltedStream())
                .as("the resumed stream runs a fresh drive")
                .isEqualTo(AudioEngine.HaltedStreamClose.NOT_HALTED);

        assertThat(engine.isStreamOpen()).isTrue();
        assertThat(engine.isRenderDriveAlive()).isTrue();
    }

    @Test
    void closingTheHaltedStreamNeverWaitsForAnOpenOnAnotherThread() throws Exception {
        AtomicBoolean failing = new AtomicBoolean();
        AudioEngineOutputTest.SynchronousTestBackend backend =
                new AudioEngineOutputTest.SynchronousTestBackend("Held");
        engine = haltableEngine(backend, failing);
        engine.startAudioOutput();
        halt(failing);

        CountDownLatch insideOpen = new CountDownLatch(1);
        CountDownLatch releaseOpen = new CountDownLatch(1);
        backend.openBarrier = () -> {
            insideOpen.countDown();
            try {
                // Bounded, so a broken test can never hold the open for ever.
                releaseOpen.await(GUARD_BUDGET_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        AtomicReference<Throwable> openerFailure = new AtomicReference<>();
        Thread opener = Thread.ofPlatform().name("halted-stream-reopener").daemon(true).unstarted(() -> {
            try {
                engine.startAudioOutput();
            } catch (Throwable failure) {
                openerFailure.set(failure);
            }
        });
        ExecutorService closer = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("halted-stream-closer").daemon(true).factory());
        try {
            assertTimeoutPreemptively(Duration.ofMillis(GUARD_BUDGET_MILLIS), () -> {
                opener.start();
                assertThat(insideOpen.await(INNER_WAIT_MILLIS, TimeUnit.MILLISECONDS))
                        .as("Play on another thread reaped the halted stream and is inside the reopen")
                        .isTrue();
                Future<AudioEngine.HaltedStreamClose> close = closer.submit(engine::closeHaltedStream);
                AudioEngine.HaltedStreamClose verdict;
                try {
                    verdict = close.get(INNER_WAIT_MILLIS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException waited) {
                    throw new AssertionError("closeHaltedStream waited for the lifecycle lock the"
                            + " reopen holds instead of answering at once", waited);
                } finally {
                    releaseOpen.countDown();
                }
                assertThat(verdict)
                        .as("the close does not wait; it stays owed")
                        .isEqualTo(AudioEngine.HaltedStreamClose.LIFECYCLE_BUSY);
                opener.join(INNER_WAIT_MILLIS);
                assertThat(opener.isAlive()).as("the reopen finished").isFalse();
            });
        } finally {
            releaseOpen.countDown();
            closer.shutdownNow();
        }

        assertThat(openerFailure.get()).isNull();
        assertThat(engine.isStreamOpen()).as("the reopened stream is open").isTrue();
        assertThat(engine.isRenderDriveAlive()).isTrue();
        assertThat(engine.closeHaltedStream())
                .as("asked again, the close finds the halted stream already replaced")
                .isEqualTo(AudioEngine.HaltedStreamClose.NOT_HALTED);
        assertThat(engine.isStreamOpen()).isTrue();
    }

    // ── A retired lane's samples (story 337, ruling E81) ─────────────────

    @Test
    void aStreamStopAfterAHaltStillHandsTheHaltSampleToTheReading() {
        AtomicBoolean failing = new AtomicBoolean();
        engine = haltableEngine(new MockAudioBackend(), failing);
        engine.startAudioOutput();
        Object generation = engine.getStreamGeneration();
        halt(failing);

        engine.stopAudioOutput();

        List<AudioFailureSample> samples = new ArrayList<>();
        engine.audioHealth().read(samples::add);
        List<AudioFailureSample> halts = samples.stream()
                .filter(sample -> sample.category() == AudioFailureCategory.RENDER_HALTED)
                .toList();
        assertThat(halts)
                .as("the halted drive's lane retired at the stop and still hands its halt to the reading")
                .singleElement()
                .satisfies(sample -> {
                    assertThat(sample.subject()).containsSame(generation);
                    assertThat(sample.failure()).get().isInstanceOf(IllegalStateException.class);
                    assertThat(sample.detail()).isEqualTo(EngineStreamPump.haltThreshold(FORMAT));
                });
        List<AudioFailureSample> again = new ArrayList<>();
        engine.audioHealth().read(again::add);
        assertThat(again).as("a drained sample is handed over once").isEmpty();
    }

    /**
     * An engine on {@code backend} whose recording callback throws on every
     * block while {@code failing} is set — a sustained render fault.
     */
    private static AudioEngine haltableEngine(AudioBackend backend, AtomicBoolean failing) {
        AudioEngine haltable = new AudioEngine(FORMAT);
        haltable.setStreamingProvision(provision(backend));
        haltable.setRecordingCallback((inputBuffer, numFrames) -> {
            if (failing.get()) {
                throw new IllegalStateException("an insert fails every block");
            }
        });
        return haltable;
    }

    /** Fails every block until the open stream's drive halts itself, then stops failing. */
    private void halt(AtomicBoolean failing) {
        failing.set(true);
        try {
            awaitCondition(engine::isRenderDriveHalted, "the render drive halts honestly");
        } finally {
            failing.set(false);
        }
    }

    private static StreamingProvision provision(AudioBackend backend) {
        DeviceId device = DeviceId.defaultFor(backend.name());
        return new StreamingProvision(
                backend.name(), List.of(new BackendStreamRung(backend, device)));
    }

    /** Waits for {@code release} within the guard budget, through the stop's interrupt, then keeps the interrupt. */
    private static void awaitIgnoringInterrupts(CountDownLatch release) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GUARD_BUDGET_MILLIS);
        boolean interrupted = false;
        try {
            while (release.getCount() > 0L && deadline - System.nanoTime() > 0L) {
                try {
                    release.await(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                } catch (InterruptedException stopInterrupt) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void awaitCondition(BooleanSupplier condition, String description) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GUARD_BUDGET_MILLIS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0L) {
                fail("Timed out after " + GUARD_BUDGET_MILLIS + " ms awaiting: " + description);
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
    }
}
