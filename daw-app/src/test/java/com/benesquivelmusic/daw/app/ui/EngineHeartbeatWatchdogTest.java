package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.CaptureInputSnapshot;
import com.benesquivelmusic.daw.core.audio.InputStallKind;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The heartbeat watchdog's rule (story 338, rulings E1, E2,
 * E12, E15, E16), proved with a scripted probe and a fake clock: the window
 * scales with the buffer above a two-second floor, a late tick and a refused
 * verdict re-baseline, a drive that is not alive is never judged, the latch
 * holds one accepted verdict per epoch, a stream still starting gets the
 * start-up allowance, and capture inputs are judged only while the stream
 * beats.
 */
class EngineHeartbeatWatchdogTest {

    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long TICK = 100 * MS;

    /** A scripted engine: every reading is a field the test sets. */
    private static final class ScriptedEngine implements EngineHeartbeatWatchdog.Probe {
        long epoch = 1;
        boolean alive = true;
        long beats;
        OptionalLong periods = OptionalLong.empty();
        long periodNanos;
        List<CaptureInputSnapshot> inputs = List.of();
        boolean captureActive;

        ScriptedEngine(double sampleRate, int bufferFrames) {
            periodNanos = Math.round(bufferFrames * 1_000_000_000.0 / sampleRate);
        }

        @Override public long renderDriveEpoch() { return epoch; }
        @Override public boolean isRenderDriveAlive() { return alive; }
        @Override public long renderDriveBeats() { return beats; }
        @Override public OptionalLong deviceCallbackPeriods() { return periods; }
        @Override public long bufferPeriodNanos() { return periodNanos; }
        @Override public List<CaptureInputSnapshot> captureInputs() { return inputs; }
        @Override public boolean isRecordingCaptureActive() { return captureActive; }
    }

    /** Records every verdict; accepts the stream's unless told to refuse. */
    private static final class RecordedReactions implements EngineHeartbeatWatchdog.Reactions {
        final List<EngineHeartbeatWatchdog.StreamStall> stalls = new ArrayList<>();
        final List<Object[]> inputStalls = new ArrayList<>();
        int refusalsLeft;

        @Override
        public boolean streamStalled(EngineHeartbeatWatchdog.StreamStall stall) {
            stalls.add(stall);
            if (refusalsLeft > 0) {
                refusalsLeft--;
                return false;
            }
            return true;
        }

        @Override
        public void inputStalled(CaptureInputSnapshot input, InputStallKind kind) {
            inputStalls.add(new Object[]{input.handle(), kind});
        }
    }

    private long now;
    private final RecordedReactions reactions = new RecordedReactions();

    private EngineHeartbeatWatchdog watchdog(ScriptedEngine engine, long tickInterval) {
        return new EngineHeartbeatWatchdog(engine, reactions, () -> now, tickInterval);
    }

    /** Ticks every {@code step} for {@code duration}, advancing the beats once per {@code beatEvery} of fake time. */
    private void run(EngineHeartbeatWatchdog watchdog, ScriptedEngine engine, long step, long duration, long beatEvery) {
        long end = now + duration;
        long nextBeat = now + beatEvery;
        while (now < end) {
            now += step;
            while (beatEvery > 0 && nextBeat <= now) {
                engine.beats++;
                nextBeat += beatEvery;
            }
            watchdog.tick();
        }
    }

    /** Ticks every {@code step} for {@code duration} with nothing advancing. */
    private void runStill(EngineHeartbeatWatchdog watchdog, long step, long duration) {
        long end = now + duration;
        while (now < end) {
            now += step;
            watchdog.tick();
        }
    }

    @Test
    void healthyStreamingAt2048And4096FramesAt44kNeverTripsOverTenSimulatedSeconds() {
        for (int frames : new int[]{2048, 4096}) {
            ScriptedEngine engine = new ScriptedEngine(44_100.0, frames);
            EngineHeartbeatWatchdog watchdog = watchdog(engine, TICK);
            run(watchdog, engine, TICK, TimeUnit.SECONDS.toNanos(10), engine.periodNanos);
            assertThat(reactions.stalls).as("%d frames at 44.1 kHz beats once per period", frames).isEmpty();
        }
    }

    @Test
    void aTwentyMillisecondSchedulingGapAt32FramesAt192kIsNoVerdict() {
        ScriptedEngine engine = new ScriptedEngine(192_000.0, 32);
        EngineHeartbeatWatchdog watchdog = watchdog(engine, 10 * MS);
        run(watchdog, engine, 10 * MS, TimeUnit.SECONDS.toNanos(1), engine.periodNanos);
        runStill(watchdog, 10 * MS, 20 * MS);
        run(watchdog, engine, 10 * MS, TimeUnit.SECONDS.toNanos(1), engine.periodNanos);

        assertThat(reactions.stalls).as("eight periods of 32 frames are 1.3 ms; the floor is 2 s").isEmpty();
    }

    @Test
    void aStillnessShorterThanEightPeriodsOfALargeBufferIsNoVerdict() {
        // 22,050 frames at 44.1 kHz: a 500 ms period, so the window is 4 s.
        ScriptedEngine engine = new ScriptedEngine(44_100.0, 22_050);
        EngineHeartbeatWatchdog watchdog = watchdog(engine, TICK);
        run(watchdog, engine, TICK, TimeUnit.SECONDS.toNanos(2), engine.periodNanos);
        runStill(watchdog, TICK, TimeUnit.SECONDS.toNanos(3));
        assertThat(reactions.stalls).as("3 s still is under eight 500 ms periods").isEmpty();

        runStill(watchdog, TICK, TimeUnit.SECONDS.toNanos(1));
        assertThat(reactions.stalls).as("4 s still is eight periods").hasSize(1);
    }

    @Test
    void aHeartbeatStillForTheWindowIsOneVerdictAndNotBefore() {
        ScriptedEngine engine = new ScriptedEngine(48_000.0, 256);
        EngineHeartbeatWatchdog watchdog = watchdog(engine, TICK);
        run(watchdog, engine, TICK, TimeUnit.SECONDS.toNanos(1), engine.periodNanos);
        long frozenAt = engine.beats;

        runStill(watchdog, TICK, 1_900 * MS);
        assertThat(reactions.stalls).as("1.9 s is inside the 2 s window").isEmpty();
        runStill(watchdog, TICK, 100 * MS);

        assertThat(reactions.stalls)
                .extracting(EngineHeartbeatWatchdog.StreamStall::epoch, EngineHeartbeatWatchdog.StreamStall::kind,
                        EngineHeartbeatWatchdog.StreamStall::count)
                .containsExactly(tuple(1L, EngineHeartbeatWatchdog.HeartbeatKind.RENDER_BEATS, frozenAt));
    }

    @Test
    void aDriveThatIsNotAliveIsNeverJudgedHoweverLongItsCountersFreeze() {
        ScriptedEngine engine = new ScriptedEngine(48_000.0, 256);
        EngineHeartbeatWatchdog watchdog = watchdog(engine, TICK);
        run(watchdog, engine, TICK, TimeUnit.SECONDS.toNanos(1), engine.periodNanos);

        engine.alive = false;
        runStill(watchdog, TICK, TimeUnit.SECONDS.toNanos(10));
        assertThat(reactions.stalls).as("a halt, a timed-out join or a stop window is never a verdict").isEmpty();

        engine.alive = true;
        runStill(watchdog, TICK, 1_900 * MS);
        assertThat(reactions.stalls).as("alive again, it is measured afresh").isEmpty();
    }

    @Test
    void oneLateTickReBaselinesAndNeverFires() {
        ScriptedEngine engine = new ScriptedEngine(48_000.0, 256);
        EngineHeartbeatWatchdog watchdog = watchdog(engine, TICK);
        run(watchdog, engine, TICK, TimeUnit.SECONDS.toNanos(1), engine.periodNanos);

        now += TimeUnit.SECONDS.toNanos(5);
        watchdog.tick();
        assertThat(reactions.stalls).as("a 5 s jump of the clock is a process pause, not a stall").isEmpty();

        runStill(watchdog, TICK, 1_900 * MS);
        assertThat(reactions.stalls).as("the window restarts at the late tick").isEmpty();
        runStill(watchdog, TICK, 100 * MS);
        assertThat(reactions.stalls).hasSize(1);
    }

    @Test
    void ticksAtMostTwiceTheIntervalApartStillReachAVerdict() {
        ScriptedEngine engine = new ScriptedEngine(48_000.0, 256);
        EngineHeartbeatWatchdog watchdog = watchdog(engine, TICK);
        run(watchdog, engine, TICK, TimeUnit.SECONDS.toNanos(1), engine.periodNanos);

        runStill(watchdog, 2 * TICK, TimeUnit.SECONDS.toNanos(2));

        assertThat(reactions.stalls).as("200 ms steps are on time").hasSize(1);
    }

    @Test
    void anAcceptedVerdictLatchesItsEpochAndANewEpochIsWatchedAgain() {
        ScriptedEngine engine = new ScriptedEngine(48_000.0, 256);
        EngineHeartbeatWatchdog watchdog = watchdog(engine, TICK);
        run(watchdog, engine, TICK, TimeUnit.SECONDS.toNanos(1), engine.periodNanos);
        runStill(watchdog, TICK, TimeUnit.SECONDS.toNanos(10));
        assertThat(reactions.stalls).as("one verdict per epoch").hasSize(1);

        engine.epoch = 2;
        run(watchdog, engine, TICK, TimeUnit.SECONDS.toNanos(1), engine.periodNanos);
        runStill(watchdog, TICK, TimeUnit.SECONDS.toNanos(2));
        assertThat(reactions.stalls).extracting(EngineHeartbeatWatchdog.StreamStall::epoch).containsExactly(1L, 2L);
    }

    @Test
    void aRefusedVerdictReBaselinesAndDecidesAgainAWindowLater() {
        ScriptedEngine engine = new ScriptedEngine(48_000.0, 256);
        EngineHeartbeatWatchdog watchdog = watchdog(engine, TICK);
        run(watchdog, engine, TICK, TimeUnit.SECONDS.toNanos(1), engine.periodNanos);
        reactions.refusalsLeft = 1;

        runStill(watchdog, TICK, TimeUnit.SECONDS.toNanos(2));
        assertThat(reactions.stalls).as("the refused verdict").hasSize(1);
        runStill(watchdog, TICK, 1_900 * MS);
        assertThat(reactions.stalls).as("measured afresh from the refusal").hasSize(1);
        runStill(watchdog, TICK, 100 * MS);
        assertThat(reactions.stalls).as("a refusal never latches the epoch").hasSize(2);
    }

    @Test
    void aStreamStillStartingGetsTheStartUpAllowance() {
        ScriptedEngine engine = new ScriptedEngine(48_000.0, 256);
        EngineHeartbeatWatchdog watchdog = watchdog(engine, TICK);
        watchdog.tick();

        runStill(watchdog, TICK, 4_900 * MS);
        assertThat(reactions.stalls).as("no first period yet: 5 s of allowance").isEmpty();
        runStill(watchdog, TICK, 100 * MS);
        assertThat(reactions.stalls).hasSize(1);
    }

    /** Ruling E107: a deliberate clock-source switch gets the start-up allowance, counted from the switch. */
    @Test
    void aClockSourceSwitchGetsTheStartUpAllowanceFromTheSwitch() {
        ScriptedEngine engine = new ScriptedEngine(48_000.0, 256);
        EngineHeartbeatWatchdog watchdog = watchdog(engine, TICK);
        run(watchdog, engine, TICK, TimeUnit.SECONDS.toNanos(1), engine.periodNanos);

        watchdog.allowForAClockSourceSwitch();
        runStill(watchdog, TICK, 4_900 * MS);
        assertThat(reactions.stalls).as("never \"stopped responding\" within 5 s of the switch").isEmpty();
        runStill(watchdog, TICK, 100 * MS);

        assertThat(reactions.stalls).as("max(5 s, window) with no advance").hasSize(1);
    }

    /** Ruling E228: an allowance asked for is pending until the next tick takes it up, never before. */
    @Test
    void anAllowanceRequestIsPendingUntilTheNextTickTakesItUp() {
        ScriptedEngine engine = new ScriptedEngine(48_000.0, 256);
        EngineHeartbeatWatchdog watchdog = watchdog(engine, TICK);
        run(watchdog, engine, TICK, TimeUnit.SECONDS.toNanos(1), engine.periodNanos);
        boolean before = watchdog.isAllowanceRequestPending();

        watchdog.allowForAClockSourceSwitch();
        boolean asked = watchdog.isAllowanceRequestPending();
        runStill(watchdog, TICK, TICK);

        assertThat(before).as("none asked for").isFalse();
        assertThat(asked).as("asked for, no tick since").isTrue();
        assertThat(watchdog.isAllowanceRequestPending()).as("the next tick took it up").isFalse();
    }

    /** Ruling E107: after the first advance that follows the switch, the normal window applies again. */
    @Test
    void theSwitchAllowanceEndsAtTheFirstAdvanceAfterIt() {
        ScriptedEngine engine = new ScriptedEngine(48_000.0, 256);
        EngineHeartbeatWatchdog watchdog = watchdog(engine, TICK);
        run(watchdog, engine, TICK, TimeUnit.SECONDS.toNanos(1), engine.periodNanos);
        watchdog.allowForAClockSourceSwitch();
        runStill(watchdog, TICK, TimeUnit.SECONDS.toNanos(1));

        now += TICK;
        engine.beats++;
        watchdog.tick();
        runStill(watchdog, TICK, 2_000 * MS);

        assertThat(reactions.stalls).as("the E1 window of 2 s after the first advance").hasSize(1);
    }

    @Test
    void theDevicePeriodsAreWatchedWhereTheBackendReportsThemWhileTheRenderDriveKeepsCycling() {
        ScriptedEngine engine = new ScriptedEngine(48_000.0, 256);
        engine.periods = OptionalLong.of(10);
        EngineHeartbeatWatchdog watchdog = watchdog(engine, TICK);
        watchdog.tick();
        now += TICK;
        engine.periods = OptionalLong.of(20);
        watchdog.tick();

        run(watchdog, engine, TICK, TimeUnit.SECONDS.toNanos(2), engine.periodNanos);

        assertThat(reactions.stalls)
                .extracting(EngineHeartbeatWatchdog.StreamStall::kind, EngineHeartbeatWatchdog.StreamStall::count)
                .containsExactly(tuple(EngineHeartbeatWatchdog.HeartbeatKind.DEVICE_PERIODS, 20L));
    }

    @Test
    void aChangeOfTheWatchedKindReBaselines() {
        ScriptedEngine engine = new ScriptedEngine(48_000.0, 256);
        engine.periods = OptionalLong.of(10);
        EngineHeartbeatWatchdog watchdog = watchdog(engine, TICK);
        watchdog.tick();
        now += TICK;
        engine.periods = OptionalLong.of(20);
        watchdog.tick();
        runStill(watchdog, TICK, 1_500 * MS);

        engine.periods = OptionalLong.empty();
        now += TICK;
        watchdog.tick();
        runStill(watchdog, TICK, 1_900 * MS);
        assertThat(reactions.stalls).as("the render beats are measured from the change, not from 1.5 s ago")
                .isEmpty();
        runStill(watchdog, TICK, 100 * MS);
        assertThat(reactions.stalls).extracting(EngineHeartbeatWatchdog.StreamStall::kind)
                .containsExactly(EngineHeartbeatWatchdog.HeartbeatKind.RENDER_BEATS);
    }

    @Test
    void anInputThatStopsDeliveringWhileTheStreamBeatsIsDeclaredStoppedOnceAfterTheWindow() {
        ScriptedEngine engine = new ScriptedEngine(48_000.0, 256);
        Object handle = new Object();
        EngineHeartbeatWatchdog watchdog = watchdog(engine, TICK);
        for (int delivered = 0; delivered < 10; delivered++) {
            engine.inputs = List.of(new CaptureInputSnapshot(handle, "Interface", delivered, true));
            run(watchdog, engine, TICK, TICK, engine.periodNanos);
        }
        assertThat(reactions.inputStalls).as("a delivering input").isEmpty();

        run(watchdog, engine, TICK, 1_900 * MS, engine.periodNanos);
        assertThat(reactions.inputStalls).as("1.9 s without a block is inside the window").isEmpty();
        run(watchdog, engine, TICK, TimeUnit.SECONDS.toNanos(5), engine.periodNanos);

        assertThat(reactions.inputStalls).extracting(stall -> stall[0], stall -> stall[1])
                .containsExactly(tuple(handle, InputStallKind.STOPPED));
        assertThat(reactions.stalls).isEmpty();
    }

    @Test
    void anInputIsNeverDeclaredWhileTheDevicePeriodsFroze() {
        ScriptedEngine engine = new ScriptedEngine(48_000.0, 256);
        engine.periods = OptionalLong.of(10);
        Object handle = new Object();
        engine.inputs = List.of(new CaptureInputSnapshot(handle, "Interface", 5, true));
        reactions.refusalsLeft = Integer.MAX_VALUE;
        EngineHeartbeatWatchdog watchdog = watchdog(engine, TICK);

        run(watchdog, engine, TICK, TimeUnit.SECONDS.toNanos(10), engine.periodNanos);

        assertThat(reactions.inputStalls).as("a dead device is the stream's verdict, not an input stall").isEmpty();
    }

    @Test
    void anInputThatNeverDeliveredIsDeclaredOnlyWhileATakeCapturesAndOnlyAfterTheStartUpAllowance() {
        ScriptedEngine engine = new ScriptedEngine(48_000.0, 256);
        Object handle = new Object();
        engine.inputs = List.of(new CaptureInputSnapshot(handle, "Interface", 0, true));
        EngineHeartbeatWatchdog watchdog = watchdog(engine, TICK);
        run(watchdog, engine, TICK, TimeUnit.SECONDS.toNanos(10), engine.periodNanos);
        assertThat(reactions.inputStalls).as("outside a take a silent input is not declared").isEmpty();

        engine.epoch = 2;
        engine.captureActive = true;
        run(watchdog, engine, TICK, 4_900 * MS, engine.periodNanos);
        assertThat(reactions.inputStalls).as("inside the 5 s start-up allowance").isEmpty();
        run(watchdog, engine, TICK, TimeUnit.SECONDS.toNanos(5), engine.periodNanos);

        assertThat(reactions.inputStalls).extracting(stall -> stall[0], stall -> stall[1])
                .containsExactly(tuple(handle, InputStallKind.NEVER_DELIVERED));
    }
}
