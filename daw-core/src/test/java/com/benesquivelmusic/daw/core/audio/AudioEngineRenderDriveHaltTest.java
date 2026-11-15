package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.audio.health.AudioFailureCategory;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The honest exit, engine side, through the real wiring (story 337, rulings
 * E8 and E10): an engine streaming on a {@link MockAudioBackend} whose render
 * drive halts after a sustained render fault reports the dead drive through
 * {@link AudioEngine#isRenderDriveAlive()} while the stream still counts as
 * open, and the next {@link AudioEngine#startAudioOutput()} reaps it and
 * opens a fresh drive instead of returning early on {@code RUNNING} — Play is
 * never wedged. The pump reads the engine's own health record, so the counts
 * asserted here are the ones the application's drain will read.
 */
class AudioEngineRenderDriveHaltTest {

    /** 22 consecutive faulted blocks halt the drive at 2048 frames and 44.1 kHz (ruling E8). */
    private static final AudioFormat FORMAT = new AudioFormat(44_100.0, 2, 16, 2048);

    /**
     * Guard budget — generous, never inner-inflated. The halt needs 22 block
     * periods of the mock's full-period pacing, about one second.
     */
    private static final long GUARD_BUDGET_MILLIS = 10_000L;

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

    private static StreamingProvision provision(MockAudioBackend backend) {
        DeviceId device = DeviceId.defaultFor(backend.name());
        return new StreamingProvision(
                backend.name(), List.of(new BackendStreamRung(backend, device)));
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
