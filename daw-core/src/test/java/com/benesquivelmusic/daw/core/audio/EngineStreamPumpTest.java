package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioBlock;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * Unit contract of the engine-owned render pump (story 316): device-clock
 * pacing via {@code awaitSinkCapacity}, capture flow from
 * {@code inputBlocks()} into {@code processBlock}'s input planes, sink-fault
 * resilience, a bounded stop-join, and a quiet exit when the engine stops
 * racing the pump.
 *
 * <p>Async assertions use one generous guard budget
 * ({@value #GUARD_BUDGET_MILLIS}&nbsp;ms) and await conditions, never
 * sleeps.</p>
 */
class EngineStreamPumpTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 24, 128);
    private static final com.benesquivelmusic.daw.sdk.audio.AudioFormat SDK_FORMAT =
            new com.benesquivelmusic.daw.sdk.audio.AudioFormat(48_000.0, 2, 24);

    /**
     * A deliberately long block period for the two pacing regressions: 4096
     * frames at 48 kHz is a ~85 ms period, so one period dwarfs every source
     * of timing noise in the observation window below and the ceiling has an
     * enormous margin over a correctly paced loop.
     *
     * <p>{@link #SDK_FORMAT} still applies unchanged: the pump's constructor
     * requires the opened format to match the engine format on CHANNELS and
     * SAMPLE RATE only — the buffer size is not part of that invariant.</p>
     */
    private static final AudioFormat SLOW_FORMAT = new AudioFormat(48_000.0, 2, 24, 4096);

    /**
     * The format {@link #aFaultedLoopIsNotDoublePacedOnAFullPeriodBackend()}
     * measures on: 960 frames at 48 kHz is a period of EXACTLY 20 ms.
     *
     * <p>The whole-millisecond period is the point. That test's backend paces
     * itself with a single {@code LockSupport.parkNanos(timeoutNanos)} — the
     * very call {@link AudioBackend#awaitSinkCapacity(long)}'s default
     * implementation makes — and on Windows that resolves to a
     * millisecond-granularity wait: a park of 2 666 666 ns (the 128-frame
     * {@link #FORMAT} period) measures ~2.09 ms on this host, i.e. it returns
     * ~0.57 ms SHORT of the period it was asked for. The pump's floor then
     * legitimately tops that shortfall up, which depresses the ratio below
     * without any double-parking having occurred — a confound, not the defect
     * under test. A period that is a whole number of milliseconds loses
     * nothing to that truncation: a 20 ms park measures ~20.3 ms on this host
     * and falls short of the full period only about one park in twenty, so the
     * backend satisfies the floor essentially on its own and the ratio
     * isolates the one behaviour the test is about.</p>
     *
     * <p>{@link #SDK_FORMAT} applies unchanged, for the same reason it does
     * for {@link #SLOW_FORMAT}: only the buffer size differs.</p>
     */
    private static final AudioFormat WHOLE_MILLIS_FORMAT =
            new AudioFormat(48_000.0, 2, 24, 960);

    /** Guard budget — generous, never inner-inflated. */
    private static final long GUARD_BUDGET_MILLIS = 5_000L;

    /**
     * How long the pacing regressions watch a fault-mode loop. Not a guard
     * budget and not a tolerance: it is the length of the observation, and the
     * assertion inside it fails fast rather than at the end.
     */
    private static final long PACING_OBSERVATION_MILLIS = 400L;

    /**
     * Upper bound on {@code sink} calls during that observation. Only ~4.7
     * {@link #SLOW_FORMAT} periods fit in 400 ms, so a correctly paced loop
     * cannot approach this; an un-paced one blows past it within milliseconds.
     * One-sided on purpose — a slow machine can only make the pump slower,
     * i.e. only make this assertion safer.
     */
    private static final int PACED_SINK_CEILING = 16;

    /**
     * Length of each of the two measurement windows in
     * {@link #aFaultedLoopIsNotDoublePacedOnAFullPeriodBackend()}. Sized for
     * STATISTICS rather than for a ceiling: at
     * {@link #WHOLE_MILLIS_FORMAT}'s 20 ms period it admits about 30
     * iterations, so the ±1 quantisation of an iteration count moves the ratio
     * by only ~3% — nearly an order of magnitude under the ~0.2 that separates
     * the 0.7 threshold from each of the two measured readings.
     */
    private static final long RATIO_WINDOW_MILLIS = 600L;

    private AudioEngine engine;
    private EngineStreamPump pump;

    @AfterEach
    void tearDown() {
        if (pump != null) {
            pump.stop();
        }
        if (engine != null) {
            engine.stop();
        }
    }

    private EngineStreamPump startPump(RecordingBackend backend) {
        return startPump(backend, FORMAT);
    }

    /**
     * Starts a pump on an explicit engine format. Only the BUFFER SIZE ever
     * differs between {@link #FORMAT} and {@link #SLOW_FORMAT}, so
     * {@link #SDK_FORMAT} keeps satisfying the constructor's channels-and-rate
     * invariant for both.
     */
    private EngineStreamPump startPump(RecordingBackend backend, AudioFormat format) {
        engine = new AudioEngine(format);
        engine.start();
        pump = new EngineStreamPump(backend, engine, format, SDK_FORMAT);
        pump.start();
        return pump;
    }

    @Test
    void pumpPacesProductionThroughAwaitSinkCapacity() {
        RecordingBackend backend = new RecordingBackend();
        startPump(backend);

        awaitCondition(() -> backend.sinkCalls.get() >= 3, "the pump sinks blocks");
        awaitCondition(() -> backend.awaitCalls.get() >= 2,
                "awaitSinkCapacity is called between blocks");
        assertThat(backend.lastAwaitTimeoutNanos.get())
                .as("the pacing timeout is one block period: 128 frames at 48 kHz")
                .isEqualTo(128L * 1_000_000_000L / 48_000L);
    }

    @Test
    void pumpReusesOneBlockInstanceAcrossSinkCalls() {
        RecordingBackend backend = new RecordingBackend();
        startPump(backend);

        awaitCondition(() -> backend.sunkInstances.size() >= 3, "several blocks sunk");
        assertThat(backend.sunkInstances.stream().distinct().count())
                .as("the sink contract permits one reusable block — the pump is allocation-free")
                .isEqualTo(1);
        AudioBlock block = backend.sunkInstances.get(0);
        assertThat(block.channels()).isEqualTo(2);
        assertThat(block.frames()).isEqualTo(128);
    }

    @Test
    void publishedInputBlocksReachProcessBlockInputPlanes() {
        RecordingBackend backend = new RecordingBackend();
        AtomicBoolean sawInput = new AtomicBoolean();
        engine = new AudioEngine(FORMAT);
        engine.setRecordingCallback((inputBuffer, numFrames) -> {
            for (float[] channel : inputBuffer) {
                for (int i = 0; i < numFrames; i++) {
                    if (channel[i] == 0.5f) {
                        sawInput.set(true);
                        return;
                    }
                }
            }
        });
        engine.start();
        pump = new EngineStreamPump(backend, engine, FORMAT, SDK_FORMAT);
        pump.start();

        float[] samples = new float[2 * 128];
        java.util.Arrays.fill(samples, 0.5f);
        backend.inputPublisher.submit(new AudioBlock(48_000.0, 2, 128, samples));

        awaitCondition(sawInput::get,
                "a published capture block is de-interleaved into the input planes");
    }

    @Test
    void anOpenedFormatDisagreeingOnShapeIsRejectedAtConstruction() {
        // Story 316 review (F6), defence in depth: the planes, the interleave
        // buffer and the reusable block are built from the ENGINE format while
        // the block is stamped with the OPENED sample rate. A wider channel
        // count would make every sink reject the block; a different rate would
        // merely relabel un-resampled audio. The ladder already enforces this
        // when it picks a rung — asserting it here means no caller can build a
        // mislabelling pump silently.
        RecordingBackend backend = new RecordingBackend();
        engine = new AudioEngine(FORMAT);
        engine.start();

        assertThatThrownBy(() -> new EngineStreamPump(backend, engine, FORMAT,
                new com.benesquivelmusic.daw.sdk.audio.AudioFormat(48_000.0, 4, 24)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channels and sample rate");
        assertThatThrownBy(() -> new EngineStreamPump(backend, engine, FORMAT,
                new com.benesquivelmusic.daw.sdk.audio.AudioFormat(44_100.0, 2, 24)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channels and sample rate");
        assertThat(new EngineStreamPump(backend, engine, FORMAT,
                new com.benesquivelmusic.daw.sdk.audio.AudioFormat(48_000.0, 2, 16)))
                .as("a bit-depth-only renegotiation is still accepted")
                .isNotNull();
    }

    @Test
    void sinkFailuresAreSurvivedAndCounted() {
        RecordingBackend backend = new RecordingBackend();
        backend.failSink = true;
        startPump(backend);

        awaitCondition(() -> pump.sinkFailures() >= 3,
                "a throwing sink does not kill the pump — it keeps looping");
        assertThat(pump.isRunning()).isTrue();
    }

    /**
     * Story 316 review (round 3): surviving a sink fault must not cost the
     * loop its real-time cadence.
     *
     * <p>What a failed {@code sink} costs an iteration depends on the BACKEND,
     * which is why the pump enforces a floor rather than adding a wait (story
     * 316 review, round 4). {@code AudioBackend}'s default
     * {@code awaitSinkCapacity} parks the full timeout regardless — that is
     * what {@code WasapiBackend}, {@code CoreAudioBackend}, {@code JackBackend}
     * and {@code MockAudioBackend} inherit — whereas {@code AsioBackend}'s ring
     * poll returns the moment its output ring reads drained, and a {@code sink}
     * that threw put nothing into that ring to wait on, and
     * {@code JavaxSoundBackend}'s override is
     * empty. {@link RecordingBackend} stands with that second group here: its
     * {@code awaitSinkCapacity} parks a flat 1 ms, nearly two orders of
     * magnitude under {@link #SLOW_FORMAT}'s ~85 ms period. Without the floor a
     * PERSISTENT fault therefore spins: a burned core, and — the real damage —
     * transport advancing one block per iteration with no hardware clock behind
     * it, running the timeline far faster than real time until the fault
     * cleared.</p>
     *
     * <p>The bound is deliberately one-sided. {@link #SLOW_FORMAT}'s ~85 ms
     * period admits under five iterations in the observation window, while the
     * un-paced loop this pins was limited only by {@code RecordingBackend}'s
     * own 1 ms pacing park — hundreds of iterations in the same window — so
     * the ceiling separates the two by well over an order of magnitude and a
     * slow machine can only push the reading further under it.</p>
     *
     * <p>{@link #aFaultedLoopIsNotDoublePacedOnAFullPeriodBackend()} pins the
     * other side of the same floor: on a backend that already parks the whole
     * period, the pump must add nothing.</p>
     */
    @Test
    void aPersistentSinkFaultKeepsTheLoopPacedAtOneBlockPeriod() {
        RecordingBackend backend = new RecordingBackend();
        backend.failSink = true;
        startPump(backend, SLOW_FORMAT);

        awaitCondition(() -> backend.sinkCalls.get() >= 1,
                "the pump reached the throwing sink at least once");
        assertStaysUnder(backend.sinkCalls, PACED_SINK_CEILING,
                "sink calls while every sink throws");
        assertThat(pump.isRunning())
                .as("pacing itself must not end the loop")
                .isTrue();
    }

    @Test
    void processBlockFaultRendersSilenceAndLaterBlocksStillReachSink() {
        // Story 316 review (F2): a plugin NPE inside processBlock must not
        // kill the render drive — the faulted block becomes silence, the
        // fault is counted, and later blocks keep reaching the sink.
        RecordingBackend backend = new RecordingBackend();
        AtomicInteger processBlockCalls = new AtomicInteger();
        engine = new AudioEngine(FORMAT);
        engine.setRecordingCallback((inputBuffer, numFrames) -> {
            if (processBlockCalls.incrementAndGet() == 1) {
                throw new NullPointerException("plugin NPE on the first block");
            }
        });
        engine.start();
        pump = new EngineStreamPump(backend, engine, FORMAT, SDK_FORMAT);
        pump.start();

        awaitCondition(() -> pump.renderFaults() >= 1,
                "the processBlock fault is counted, not fatal");
        int sunkAtFault = backend.sinkCalls.get();
        awaitCondition(() -> backend.sinkCalls.get() > sunkAtFault + 2,
                "blocks rendered AFTER the fault still reach the sink");
        assertThat(pump.isRunning()).isTrue();
        assertThat(pump.renderFaults()).isEqualTo(1);
    }

    @Test
    void collaboratorIllegalStateWhileTheEngineRunsIsAnOrdinaryRenderFault() {
        // Story 316 review: the quiet exit is decided by STATE, not by the
        // exception TYPE. An insert plugin asserting "not prepared" after a
        // mid-session swap — or the mixer, the MIDI renderer, the graph
        // scheduler — throws IllegalStateException while the engine is
        // perfectly healthy. Matching on the type alone made every one of
        // them silently end audio for the rest of the session.
        RecordingBackend backend = new RecordingBackend();
        AtomicInteger processBlockCalls = new AtomicInteger();
        engine = new AudioEngine(FORMAT);
        engine.setRecordingCallback((inputBuffer, numFrames) -> {
            if (processBlockCalls.incrementAndGet() == 1) {
                throw new IllegalStateException("insert not prepared");
            }
        });
        engine.start();
        pump = new EngineStreamPump(backend, engine, FORMAT, SDK_FORMAT);
        pump.start();

        awaitCondition(() -> pump.renderFaults() >= 1,
                "a collaborator's IllegalStateException is counted, not fatal");
        int sunkAtFault = backend.sinkCalls.get();
        awaitCondition(() -> backend.sinkCalls.get() > sunkAtFault + 2,
                "blocks rendered AFTER the fault still reach the sink");
        assertThat(pump.isRunning())
                .as("only a stopped engine (or a stopped pump) ends the render loop")
                .isTrue();
        assertThat(pump.renderFaults()).isEqualTo(1);
    }

    @Test
    void awaitSinkCapacityFaultIsSurvivedAndThePumpKeepsSinking() {
        // Story 316 review (F2): a throwing pacing seam must not kill the
        // render drive — the loop logs once and paces itself instead.
        RecordingBackend backend = new RecordingBackend();
        backend.failAwait = true;
        startPump(backend);

        awaitCondition(() -> backend.sinkCalls.get() >= 5,
                "the pump keeps sinking blocks despite the pacing fault");
        assertThat(backend.awaitCalls.get())
                .as("the pacing seam really was exercised and threw")
                .isGreaterThanOrEqualTo(1);
        assertThat(pump.isRunning()).isTrue();
    }

    /**
     * Story 316 review (round 3): the companion bound for
     * {@link #awaitSinkCapacityFaultIsSurvivedAndThePumpKeepsSinking()}. That
     * test proves the loop SURVIVES a throwing pacing seam; this one proves it
     * does not then run unboundedly fast.
     *
     * <p>This is the harsher of the two faults, and the one case that does not
     * turn on which backend is underneath: a seam that threw cannot be
     * credited with any of the wait it was asked for, whatever it did before
     * throwing, so the pump re-arms its deadline AT the throw and the floor
     * supplies the entire block period. {@link RecordingBackend} makes the
     * un-paced case concrete — it throws before it parks at all — so nothing
     * whatsoever paced the iteration and the loop was limited only by how fast
     * {@code processBlock} returns: thousands of iterations, and thousands of
     * blocks of transport advance, per second of fault.</p>
     */
    @Test
    void aPersistentPacingFaultKeepsTheLoopPacedAtOneBlockPeriod() {
        RecordingBackend backend = new RecordingBackend();
        backend.failAwait = true;
        startPump(backend, SLOW_FORMAT);

        awaitCondition(() -> backend.sinkCalls.get() >= 1,
                "the pump completed at least one render/sink iteration");
        assertStaysUnder(backend.sinkCalls, PACED_SINK_CEILING,
                "sink calls while every awaitSinkCapacity throws");
        assertThat(backend.awaitCalls.get())
                .as("the pacing seam really was exercised and threw")
                .isGreaterThanOrEqualTo(1);
        assertThat(pump.isRunning())
                .as("pacing itself must not end the loop")
                .isTrue();
    }

    /**
     * Story 316 review (round 4): the fault-path pacing must be a FLOOR, never
     * a second wait.
     *
     * <p>The regression this pins is the mirror image of
     * {@link #aPersistentSinkFaultKeepsTheLoopPacedAtOneBlockPeriod()}. An
     * unconditional park in the {@code sink} catch was justified by "the throw
     * enqueued nothing, so the capacity wait behind it returns at once" — which
     * is false for most backends. {@code AudioBackend}'s default
     * {@code awaitSinkCapacity} is {@code LockSupport.parkNanos(timeoutNanos)}:
     * a full-period park taken whether or not anything was enqueued, inherited
     * by {@code WasapiBackend}, {@code CoreAudioBackend}, {@code JackBackend}
     * and {@code MockAudioBackend}, all of which override a {@code sink} that
     * can throw. On every one of those a persistent sink fault parked TWICE per
     * iteration and ran the render loop — and the transport it advances — at
     * half real time.</p>
     *
     * <p>{@link RecordingBackend#fullPeriodPark} makes the fake stand in for
     * that whole group, and the assertion is a RATIO of two measurements taken
     * the same way — see {@link #measureSinkCallsInWindow(boolean)} — so no
     * iteration count is hard-coded and this host's park granularity and
     * scheduling noise cancel out of both sides. The measurement runs on
     * {@link #WHOLE_MILLIS_FORMAT} rather than {@link #FORMAT} for the reason
     * documented there: a fractional-millisecond period is genuinely
     * under-parked by the backend and correctly topped up by the floor, which
     * would mix a second effect into the same reading.</p>
     *
     * <p><strong>Where the 0.7 comes from.</strong> Measured on the
     * development host, {@code faulted / paced} is ~0.90 with the floor in
     * place and ~0.45 with the unconditional park reinstated; a control run
     * that measures the healthy loop TWICE gives exactly 1.00, so there is no
     * phase-order bias in the harness. The floor is not the whole of that
     * 0.10 shortfall — with the floor's park removed entirely the ratio is
     * ~0.93, i.e. the throwing path costs a little more per iteration than the
     * healthy one before any pacing is considered. The threshold therefore
     * sits ~0.2 clear of the regression and ~0.2 clear of the fixed reading,
     * which is far more headroom than the ~3% an iteration of quantisation
     * moves it.</p>
     */
    @Test
    void aFaultedLoopIsNotDoublePacedOnAFullPeriodBackend() {
        int pacedRate = measureSinkCallsInWindow(false);
        int faultedRate = measureSinkCallsInWindow(true);

        assertThat(pacedRate)
                .as("the healthy baseline must actually have iterated — a zero"
                        + " reading would leave nothing to compare the faulted run"
                        + " against, so fail here rather than on a meaningless ratio")
                .isGreaterThan(0);
        assertThat((double) faultedRate)
                .as("a faulted iteration on a backend that already parked the whole"
                        + " block period must not be parked a second time: %d sink"
                        + " calls while faulting against %d while healthy is a ratio"
                        + " of %.2f, where a correct FLOOR measures about 0.9 and the"
                        + " unconditional extra park about 0.45",
                        faultedRate, pacedRate, faultedRate / (double) pacedRate)
                .isGreaterThanOrEqualTo(0.7 * pacedRate);
    }

    /**
     * Runs one {@link #RATIO_WINDOW_MILLIS} measurement window against a fresh
     * backend, engine and pump on {@link #WHOLE_MILLIS_FORMAT}, with the
     * backend's {@code awaitSinkCapacity} parking the full block period it is
     * handed, and returns the number of {@code sink} calls made inside that
     * window.
     *
     * <p>The counter is zeroed only after the first {@code sink} lands, so
     * thread start-up and the first render fall outside the window in both
     * phases. {@code failSink} is the ONLY difference between the two calls —
     * same format, same window, same full-period park — which is what makes
     * their RATIO the
     * robust quantity rather than either reading alone: whatever this host's
     * park granularity and scheduling noise cost one phase, they cost the
     * other equally and divide out. The pump and engine are stopped before
     * returning so the second phase measures its own loop and not two.</p>
     *
     * @param failSink whether every {@code sink} call throws during the window
     * @return {@code sink} calls observed within the window
     */
    private int measureSinkCallsInWindow(boolean failSink) {
        RecordingBackend backend = new RecordingBackend();
        backend.fullPeriodPark = true;
        backend.failSink = failSink;
        startPump(backend, WHOLE_MILLIS_FORMAT);
        awaitCondition(() -> backend.sinkCalls.get() >= 1,
                "the pump reached the sink at least once");
        backend.sinkCalls.set(0);
        observeFor(RATIO_WINDOW_MILLIS);
        int observed = backend.sinkCalls.get();
        pump.stop();
        engine.stop();
        return observed;
    }

    @Test
    void stopJoinsThePumpThread() {
        RecordingBackend backend = new RecordingBackend();
        startPump(backend);
        awaitCondition(() -> backend.sinkCalls.get() >= 1, "the pump is running");

        pump.stop();

        assertThat(pump.isRunning()).isFalse();
        int sunkAtStop = backend.sinkCalls.get();
        // A stopped pump renders nothing more — poll briefly within the guard
        // budget and require the counter to stay put.
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
        while (System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
        assertThat(backend.sinkCalls.get()).isEqualTo(sunkAtStop);
    }

    @Test
    void engineStoppingMidStreamExitsThePumpQuietly() {
        RecordingBackend backend = new RecordingBackend();
        startPump(backend);
        awaitCondition(() -> backend.sinkCalls.get() >= 1, "the pump is running");

        engine.stop(); // processBlock now throws IllegalStateException

        awaitCondition(() -> !pump.isRunning(),
                "the pump exits quietly when the engine stops racing it");
    }

    @Test
    void inputQueueOverflowDropsAndCounts() {
        RecordingBackend backend = new RecordingBackend();
        // Do NOT start the pump thread: subscribe-only via start would race.
        // Instead start it against a stalling engine? Simpler: start the pump
        // and flood far beyond the 32-slot queue faster than the paced loop
        // can drain — drops must be counted, and the pump must survive.
        startPump(backend);
        float[] samples = new float[2 * 128];
        for (int i = 0; i < 500; i++) {
            backend.inputPublisher.submit(new AudioBlock(48_000.0, 2, 128, samples));
        }

        awaitCondition(() -> pump.droppedInputBlocks() > 0,
                "input blocks beyond the bounded queue are dropped and counted");
        assertThat(pump.isRunning()).isTrue();
    }

    @Test
    void anOnSubscribeArrivingAfterTheStopIsCancelledAndNeverRequestedFrom() {
        // Story 316 re-review: SubmissionPublisher may deliver onSubscribe
        // ASYNCHRONOUSLY, so a pause or stop can land between the pump's
        // subscribe() call and the callback. cancelInputSubscription() then
        // finds a null field and cancels nothing, and the late callback used
        // to store the subscription and request Long.MAX_VALUE from it on a
        // dead pump — leaving a stale capture subscriber attached across the
        // pause that was supposed to detach it.
        RecordingBackend backend = new RecordingBackend();
        backend.deferSubscribe = true;
        engine = new AudioEngine(FORMAT);
        engine.start();
        pump = new EngineStreamPump(backend, engine, FORMAT, SDK_FORMAT);
        pump.start();

        awaitCondition(() -> backend.lastSubscriber.get() != null,
                "the pump subscribed to the capture stream");
        assertThat(pump.stop())
                .as("the pump stops before the subscription is ever granted")
                .isTrue();

        RecordingSubscription subscription = new RecordingSubscription();
        backend.lastSubscriber.get().onSubscribe(subscription);

        assertThat(subscription.cancels.get())
                .as("a subscription granted after the stop is cancelled at once")
                .isEqualTo(1);
        assertThat(subscription.requests.get())
                .as("and is never requested from — nothing drains the pump's queue now")
                .isZero();
    }

    // ── Await support ────────────────────────────────────────────────────

    private static void awaitCondition(BooleanSupplier condition, String description) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GUARD_BUDGET_MILLIS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("Timed out after " + GUARD_BUDGET_MILLIS + " ms awaiting: " + description);
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
    }

    /**
     * Watches {@code counter} for {@link #PACING_OBSERVATION_MILLIS} and fails
     * the instant it passes {@code ceiling}.
     *
     * <p>Polled on the same 2 ms cadence as {@link #awaitCondition} rather
     * than slept through, so a regression is reported at the iteration that
     * broke the bound instead of only as a final reading — the class's "await
     * conditions, never sleeps" convention applied to an upper bound instead
     * of a lower one. {@link #GUARD_BUDGET_MILLIS} is untouched: the window is
     * how long the observation lasts, not how long a condition is tolerated to
     * take.</p>
     *
     * @param counter     the live reading to bound
     * @param ceiling     the highest value a correctly paced loop can reach
     * @param description what the reading counts, for the failure message
     */
    private static void assertStaysUnder(AtomicInteger counter, int ceiling,
                                         String description) {
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(PACING_OBSERVATION_MILLIS);
        while (deadline - System.nanoTime() > 0L) {
            int observed = counter.get();
            if (observed > ceiling) {
                fail("Expected " + description + " to stay at or under " + ceiling
                        + " within " + PACING_OBSERVATION_MILLIS + " ms but observed "
                        + observed + " — the loop is running without pacing");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
    }

    /**
     * Watches the clock for {@code millis} without asserting anything — the
     * measurement window behind
     * {@link #measureSinkCallsInWindow(boolean)}.
     *
     * <p>Deliberately a poll loop rather than a single park:
     * {@link LockSupport#parkNanos(long)} may return early, and a window that
     * ended early would shorten one phase of a two-phase RATIO and bias it. The
     * comparison against the deadline is a {@link System#nanoTime()}
     * DIFFERENCE, not an absolute {@code <}, so it survives that counter's
     * wraparound. {@link #GUARD_BUDGET_MILLIS} is untouched: this is how long
     * an observation lasts, not how long a condition is tolerated to take.</p>
     *
     * @param millis how long to observe
     */
    private static void observeFor(long millis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (deadline - System.nanoTime() > 0L) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
    }

    /** {@link Flow.Subscription} counting what the pump did with it. */
    private static final class RecordingSubscription implements Flow.Subscription {

        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger cancels = new AtomicInteger();

        @Override
        public void request(long n) {
            requests.incrementAndGet();
        }

        @Override
        public void cancel() {
            cancels.incrementAndGet();
        }
    }

    // ── Scripted backend ─────────────────────────────────────────────────

    /**
     * Fake backend recording sink/pacing traffic. Its pacing park is a fast
     * 1 ms by default, or the full block period it was handed when
     * {@link #fullPeriodPark} is set.
     */
    private static final class RecordingBackend implements AudioBackend {

        final AtomicInteger sinkCalls = new AtomicInteger();
        final AtomicInteger awaitCalls = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicLong lastAwaitTimeoutNanos =
                new java.util.concurrent.atomic.AtomicLong();
        final List<AudioBlock> sunkInstances = new CopyOnWriteArrayList<>();
        final SubmissionPublisher<AudioBlock> inputPublisher = new SubmissionPublisher<>();
        volatile boolean failSink;
        volatile boolean failAwait;
        /**
         * Story 316 review (round 4): when set,
         * {@link #awaitSinkCapacity(long)} parks the FULL timeout it was handed
         * instead of the fixed 1 ms, standing in for every backend that keeps
         * {@link AudioBackend}'s default implementation —
         * {@code LockSupport.parkNanos(timeoutNanos)}, inherited by
         * {@code WasapiBackend}, {@code CoreAudioBackend}, {@code JackBackend}
         * and {@code MockAudioBackend}, each of which overrides a {@code sink}
         * that can throw. Against those the pump's fault-path pacing must be a
         * floor the backend has already satisfied, not a second park.
         */
        volatile boolean fullPeriodPark;
        /**
         * Story 316 re-review: when set, {@link #inputBlocks()} hands back a
         * publisher that only CAPTURES the subscriber and never calls
         * {@code onSubscribe} — the test grants the subscription by hand,
         * standing in for a {@link SubmissionPublisher} that delivers the
         * callback asynchronously, after a stop has already run.
         */
        volatile boolean deferSubscribe;
        final AtomicReference<Flow.Subscriber<? super AudioBlock>> lastSubscriber =
                new AtomicReference<>();

        @Override
        public String name() {
            return "Recording";
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
            return List.of();
        }

        @Override
        public void open(DeviceId device,
                         com.benesquivelmusic.daw.sdk.audio.AudioFormat format,
                         int bufferFrames) {
            // Not exercised: the pump receives an already-open backend.
        }

        @Override
        public Flow.Publisher<AudioBlock> inputBlocks() {
            if (deferSubscribe) {
                return subscriber -> lastSubscriber.set(subscriber);
            }
            return inputPublisher;
        }

        @Override
        public void sink(AudioBlock block) {
            sinkCalls.incrementAndGet();
            if (sunkInstances.size() < 16) {
                sunkInstances.add(block);
            }
            if (failSink) {
                throw new IllegalArgumentException("shape mismatch after driver reset");
            }
        }

        @Override
        public void awaitSinkCapacity(long timeoutNanos) {
            awaitCalls.incrementAndGet();
            lastAwaitTimeoutNanos.set(timeoutNanos);
            if (failAwait) {
                throw new IllegalStateException("pacing seam failure");
            }
            LockSupport.parkNanos(fullPeriodPark
                    ? timeoutNanos
                    : TimeUnit.MILLISECONDS.toNanos(1));
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {
            inputPublisher.close();
        }
    }
}
