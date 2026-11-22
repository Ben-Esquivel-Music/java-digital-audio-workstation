package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.recording.RecordState;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.sdk.audio.BackendThreadCounters;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.await;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.awaitOnFx;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 338, amendment A13: the stall notice's Reconnect asks the window's
 * device-loss reactions, on the FX thread, whether the loss rescued a take,
 * and they answer what the record coordinator holds — wired as
 * {@code MainController} wires it (the fixture's engine bridge). A stall
 * during a count-in rescues nothing, although a take's capture was installed
 * at the loss, so a Reconnect that succeeds says the device responds again;
 * a stall during a take rescues it, and the Reconnect leaves the words to the
 * record coordinator.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class TheReconnectQueryReadsTheRecordStateTest {

    private static final long TICK_NANOS = TimeUnit.MILLISECONDS.toNanos(
            DefaultAudioEngineController.ENGINE_HEALTH_TICK_MILLIS);

    @TempDir
    Path projectDirectory;

    private DeviceLossRecordingFixture fixture;
    private final AtomicLong clock = new AtomicLong();

    @AfterEach
    void stopEverything() throws Exception {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void aStallDuringTheCountInRescuesNothingAndTheReconnectSaysTheDeviceRespondsAgain() throws Exception {
        fixture = DeviceLossRecordingFixture.withEngineBridge(projectDirectory, CountInMode.TWO_BARS);
        fixture.project().getTransport().setTempo(60);
        DefaultAudioEngineController engineController = engineControllerOnAFakeClock();
        runOnFx(fixture.controller()::toggleRecord);
        awaitOnFx(() -> fixture.coordinator().getState() == RecordState.COUNT_IN, "the take counts in");
        assertThat(fixture.engine().isRecordingCaptureActive())
                .as("premise: a take's capture is installed, which the controller's own guess reads as a rescue")
                .isTrue();

        stallUntilDeclared(engineController);
        awaitOnFx(() -> fixture.coordinator().getState() == RecordState.ABORTED
                || fixture.coordinator().getState() == RecordState.IDLE, "the loss ended the count-in");

        assertThat(onFx(fixture.deviceLossReactions()::takeRescued)).as("nothing was rescued").isFalse();
        reconnectFromTheStallNotice();
        await(() -> fixture.engineNotices()
                .contains("The audio device is responding again. Press Play to continue."),
                "the Reconnect says the device responds again");
    }

    @Test
    void aStallDuringATakeRescuesItAndTheReconnectLeavesTheWordsToTheRecordCoordinator() throws Exception {
        fixture = DeviceLossRecordingFixture.withEngineBridge(projectDirectory);
        DefaultAudioEngineController engineController = engineControllerOnAFakeClock();
        fixture.recordUntilABlockIsOnDisk();
        DeviceId device = fixture.activeDevice();

        stallUntilDeclared(engineController);
        fixture.awaitRescued();
        assertThat(onFx(fixture.deviceLossReactions()::takeRescued)).as("a rescued take waits").isTrue();
        int before = fixture.shownCount();

        reconnectFromTheStallNotice();
        awaitOnFx(() -> fixture.coordinator().getState() == RecordState.IDLE, "the reopened device settled the take");

        assertThat(fixture.entriesSince(before)).extracting(NotificationEntry::message)
                .contains("The audio device reconnected. Tracks are still armed; press Record to continue.");
        assertThat(fixture.engineNotices()).as("the controller says nothing of its own on success")
                .noneMatch(notice -> notice.contains("is responding again"));
    }

    private DefaultAudioEngineController engineControllerOnAFakeClock() {
        DefaultAudioEngineController engineController = fixture.engineController().orElseThrow();
        engineController.setEngineHealthClock(clock::get);
        return engineController;
    }

    /** Freezes the device periods while the render drive keeps cycling and ticks until the watchdog declares the loss. */
    private void stallUntilDeclared(DefaultAudioEngineController engineController) {
        fixture.backend().setThreadCounters(devicePeriods(1));
        engineController.tickEngineHealthMonitor();
        fixture.backend().setThreadCounters(devicePeriods(2));
        for (int i = 0; i < 40 && engineController.engineState() != EngineState.DEVICE_LOST; i++) {
            clock.addAndGet(TICK_NANOS);
            engineController.tickEngineHealthMonitor();
        }
        assertThat(engineController.engineState()).as("premise: the frozen device periods were declared a loss")
                .isEqualTo(EngineState.DEVICE_LOST);
        assertThat(fixture.engineNoticeActions()).as("premise: the stall's notice offers Reconnect")
                .containsExactly(Optional.of(DefaultAudioEngineController.RECONNECT_ACTION));
    }

    /** Runs the stall notice's action on the FX thread, as the notification bar runs it. */
    private void reconnectFromTheStallNotice() throws Exception {
        List<Runnable> actions = fixture.engineNoticeRunnables();
        runOnFx(actions.getLast());
    }

    private static BackendThreadCounters devicePeriods(long periods) {
        return new BackendThreadCounters(0, 0, 0, 0, OptionalLong.of(periods), Optional.empty(), Optional.empty(),
                Optional.empty());
    }
}
