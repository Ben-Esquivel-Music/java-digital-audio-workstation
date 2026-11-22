package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.recording.RecordState;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.recording.TakeManifest;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.await;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A capture input that stops delivering while the output keeps running,
 * through the production wiring (story 338, rulings E11, E13, E14 and E16;
 * the Note from story 337): the engine-health monitor's ticks read the
 * engine's capture inputs, its watchdog declares the input stalled and the
 * controller ends it in the engine, which latches the input halt. During a
 * take, the take keeps recording, its manifest holds the track's
 * {@code input-stopped} line and the person reads the track's warning.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class InputStallThroughTheMonitorTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 24, 128);
    private static final long TICK_NANOS = TimeUnit.MILLISECONDS.toNanos(
            DefaultAudioEngineController.ENGINE_HEALTH_TICK_MILLIS);

    @TempDir
    Path projectDirectory;

    private DeviceLossRecordingFixture fixture;

    @AfterEach
    void stopEverything() throws Exception {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void theMonitorDeclaresAStreamInputThatStoppedDeliveringThroughTheProductionWiring() throws Exception {
        RecordingNotifications notices = new RecordingNotifications();
        AudioEngine engine = new AudioEngine(FORMAT);
        DefaultAudioEngineController controller = new DefaultAudioEngineController(engine, null, notices);
        AtomicLong clock = new AtomicLong();
        controller.setEngineHealthClock(clock::get);
        MockAudioBackend mock = new MockAudioBackend();
        engine.setStreamingProvision(new StreamingProvision(mock.name(),
                List.of(new BackendStreamRung(mock, new DeviceId(mock.name(), "Interface A")))));
        try {
            engine.startAudioOutput();
            for (int i = 0; i < 3; i++) {
                mock.pumpInput(FORMAT.bufferSize());
            }
            await(() -> engine.captureInputs().stream().anyMatch(input -> input.deliveredBlocks() >= 3),
                    "premise: the stream's input delivered");

            tickUntilTheInputIsEnded(engine, controller, clock);

            assertThat(engine.captureInputs()).as("the stalled input was ended by the watchdog").isEmpty();
            assertThat(engine.audioHealth().backendCounters().inputHalts()).as("ruling E11: one input halt")
                    .isEqualTo(1);
            assertThat(controller.engineState()).as("the stream itself is not lost")
                    .isNotEqualTo(EngineState.DEVICE_LOST);
        } finally {
            engine.stopAudioOutput();
            engine.stop();
            controller.shutdown();
        }
    }

    @Test
    void aTakeWhoseInputStopsKeepsRecordingAndItsTrackIsFlaggedAndTheWarningReachesThePerson() throws Exception {
        fixture = DeviceLossRecordingFixture.withEngineBridge(projectDirectory);
        DefaultAudioEngineController controller = fixture.engineController().orElseThrow();
        AtomicLong clock = new AtomicLong();
        controller.setEngineHealthClock(clock::get);
        Path takeDirectory = fixture.recordUntilABlockIsOnDisk();
        int before = fixture.shownCount();
        for (int i = 0; i < 4; i++) {
            fixture.backend().pumpInput(fixture.project().getFormat().bufferSize());
        }
        AudioEngine engine = fixture.engine();
        await(() -> engine.captureInputs().stream().anyMatch(input -> input.deliveredBlocks() >= 4),
                "premise: the take's input delivered");

        tickUntilTheInputIsEnded(engine, controller, clock);

        assertThat(engine.captureInputs()).as("the declaration was made").isEmpty();
        assertThat(engine.audioHealth().backendCounters().inputHalts()).as("ruling E11's fact").isEqualTo(1);
        assertThat(onFx(() -> fixture.coordinator().getState())).as("the take keeps recording")
                .isEqualTo(RecordState.RECORDING);
        assertThat(controller.engineState()).isNotEqualTo(EngineState.DEVICE_LOST);
        String trackId = fixture.guitar().getId();
        await(() -> fixture.entriesSince(before).stream().anyMatch(entry -> entry.message().startsWith(
                "Track 'Guitar': input from ")), "the track's warning reached the notification bar");
        assertThat(fixture.entriesSince(before)).filteredOn(entry -> entry.message().startsWith("Track 'Guitar'"))
                .extracting(NotificationEntry::message)
                .singleElement().asString()
                .as("ruling E14: stopped mid-take, never the routing sentence")
                .contains("stopped delivering audio at ").endsWith("; the track records silence from there.");

        runOnFx(fixture.controller()::toggleRecord);
        fixture.awaitPublished();
        TakeManifest manifest = TakeManifest.read(TakeManifest.manifestPath(takeDirectory));
        assertThat(manifest.inputStops()).as("ruling E13: the track's stop is on disk")
                .extracting(TakeManifest.InputStopped::trackId).containsExactly(trackId);
        assertThat(manifest.sealedBy()).as("an ordinary Stop sealed it").contains(TakeManifest.SealedBy.STOP);
    }

    @Test
    void anInputThatNeverDeliveredIsDeclaredDuringATakeAndNotOutsideOne() throws Exception {
        fixture = DeviceLossRecordingFixture.withEngineBridge(projectDirectory);
        DefaultAudioEngineController controller = fixture.engineController().orElseThrow();
        AtomicLong clock = new AtomicLong();
        controller.setEngineHealthClock(clock::get);
        AudioEngine engine = fixture.engine();
        runOnFx(() -> fixture.controller().start());
        await(() -> !engine.captureInputs().isEmpty(), "premise: the stream's input is open");
        tickUntilTheInputIsEnded(engine, controller, clock);
        assertThat(engine.captureInputs()).as("ruling E15: outside a take a silent input is not declared")
                .isNotEmpty();
        runOnFx(() -> fixture.controller().stop());

        fixture.recordUntilABlockIsOnDisk();
        tickUntilTheInputIsEnded(engine, controller, clock);

        assertThat(engine.captureInputs()).as("rulings E15 and E52: declared during the take").isEmpty();
        assertThat(engine.audioHealth().backendCounters().inputHalts()).as("ruling E52: no fault latched")
                .isZero();
    }

    /** Ticks the monitor after every advance of the render drive, until the stream's input is ended. */
    private static void tickUntilTheInputIsEnded(AudioEngine engine, DefaultAudioEngineController controller,
                                                 AtomicLong clock) {
        for (int i = 0; i < 60 && !engine.captureInputs().isEmpty(); i++) {
            long beats = engine.audioHealth().renderDriveBeats();
            await(() -> engine.audioHealth().renderDriveBeats() > beats, "the render drive beats");
            clock.addAndGet(TICK_NANOS);
            controller.tickEngineHealthMonitor();
        }
    }
}
