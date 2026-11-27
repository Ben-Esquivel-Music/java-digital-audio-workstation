package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.recording.RecordState;
import com.benesquivelmusic.daw.core.recording.TakeManifest;
import com.benesquivelmusic.daw.sdk.audio.BackendThreadCounters;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.onFx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * A device that stops responding during a take (story 338,
 * rulings E4, E5 and E6), through the production detection wiring of story
 * 327: a real engine controller whose heartbeat watchdog sees the recording
 * device's callback periods stop while its render drive keeps cycling, its
 * engine-state events bridged to the transport controller as
 * {@code MainController} bridges them. The take is sealed
 * {@code sealed-by: device-loss}, registered and reported with Review; the
 * engine controller's own notice, sent to a real injected
 * {@link NotificationManager}, is the stall's, with Reconnect; and the record
 * coordinator keeps how the device was lost.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class HeartbeatStallDuringATakeTest {

    private static final long TICK_NANOS = TimeUnit.MILLISECONDS.toNanos(
            DefaultAudioEngineController.ENGINE_HEALTH_TICK_MILLIS);

    @TempDir
    Path projectDirectory;

    private DeviceLossRecordingFixture fixture;

    @BeforeEach
    void wireTheProductionDetectionPath() throws Exception {
        fixture = DeviceLossRecordingFixture.withEngineBridge(projectDirectory);
    }

    @AfterEach
    void stopEverything() throws Exception {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void aRecordingDeviceThatStopsCallingBackSealsRegistersAndReportsTheTake() throws Exception {
        DefaultAudioEngineController engineController = fixture.engineController().orElseThrow();
        AtomicLong clock = new AtomicLong();
        engineController.setEngineHealthClock(clock::get);
        Path takeDirectory = fixture.recordUntilABlockIsOnDisk();
        DeviceId device = fixture.activeDevice();
        int before = fixture.shownCount();

        fixture.backend().setThreadCounters(devicePeriods(1));
        engineController.tickEngineHealthMonitor();
        fixture.backend().setThreadCounters(devicePeriods(2));
        tick(engineController, clock);
        for (int i = 0; i < 30 && engineController.engineState() != EngineState.DEVICE_LOST; i++) {
            tick(engineController, clock);
        }
        assertThat(engineController.engineState()).as("the frozen device periods were declared a loss")
                .isEqualTo(EngineState.DEVICE_LOST);
        fixture.awaitRescued();

        TakeManifest manifest = TakeManifest.read(TakeManifest.manifestPath(takeDirectory));
        assertThat(manifest.sealedBy()).contains(TakeManifest.SealedBy.DEVICE_LOSS);
        Optional<IncompleteTakeStore> store = onFx(() -> fixture.coordinator().rescuedTakeStore());
        assertThat(store.orElseThrow().rescuedTakes())
                .extracting(IncompleteTakeStore.RescuedTake::reason, IncompleteTakeStore.RescuedTake::deviceName)
                .containsExactly(tuple(TakeManifest.SealedBy.DEVICE_LOSS, Optional.of(device.name())));
        List<NotificationEntry> entries = fixture.entriesSince(before);
        assertThat(entries).as("the take's report: one WARNING naming the device, with Review")
                .filteredOn(entry -> entry.actionLabel().equals(Optional.of("Review")))
                .extracting(NotificationEntry::level)
                .containsExactly(NotificationLevel.WARNING);
        assertThat(entries).filteredOn(entry -> entry.actionLabel().equals(Optional.of("Review")))
                .extracting(NotificationEntry::message)
                .as("ruling E111: the default device is unnamed")
                .noneMatch(message -> message.contains(device.name()))
                .as("the stall's lead, never \"disconnected\" (ruling E5)")
                .allMatch(message -> message.startsWith("The audio device stopped responding during recording"));
        assertThat(onFx(() -> fixture.coordinator().getState())).isEqualTo(RecordState.DEVICE_LOST);
        assertThat(onFx(() -> fixture.coordinator().lostCause())).isEqualTo(EngineStateChange.LossCause.STALLED);

        assertThat(fixture.engineNotices()).containsExactly("The audio device stopped responding — audio stopped."
                + " Check that it is connected and powered, then choose Reconnect.");
        assertThat(fixture.engineNoticeActions()).containsExactly(Optional.of("Reconnect"));
        assertThat(fixture.engine().isStreamOpen()).as("the lost device's stream is closed").isFalse();
        assertThat(engineController.xrunJournal().entries())
                .filteredOn(entry -> entry.detail() == XrunJournal.Detail.DEVICE_STOPPED_RESPONDING)
                .extracting(XrunJournal.Entry::arguments)
                .as("ruling E78 with ruling E111: the log's Stall entry names no placeholder")
                .containsExactly(List.of(""));
    }

    private static BackendThreadCounters devicePeriods(long periods) {
        return new BackendThreadCounters(0, 0, 0, 0, OptionalLong.of(periods), Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    private static void tick(DefaultAudioEngineController controller, AtomicLong clock) {
        clock.addAndGet(TICK_NANOS);
        controller.tickEngineHealthMonitor();
    }
}
