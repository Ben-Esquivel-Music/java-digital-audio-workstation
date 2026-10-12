package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.recording.RecordState;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.recording.TakeDirectories;
import com.benesquivelmusic.daw.core.recording.TakeManifest;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.await;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.awaitOnFx;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 327, through the production detection wiring (Recording Reliability
 * book §5.5, §5.2 "Device lost" / "Device returned"): a real
 * {@link DefaultAudioEngineController} over the engine the take records
 * through, its engine-state events bridged to the {@link TransportController}
 * by the same {@link EngineStateBridge} {@code MainController} subscribes,
 * and the host's device events from {@code MockAudioBackend}'s fixture — no
 * hand-called {@code onDeviceLost}. Unplugging the recording device seals the
 * take on disk under the project, registers it, reports it with a "Review"
 * action and leaves the machine in DEVICE_LOST; a different device arriving
 * changes nothing and repeats the remediation; the lost device returning
 * settles to IDLE with the armed set kept, and recording resumes only when
 * the user presses Record again, into a new take.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class DeviceLossRescuesTheTakeThroughTheEngineBridgeTest {

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
    void unpluggingTheRecordingDeviceSealsRegistersAndReportsTheTake() throws Exception {
        Path takeDirectory = fixture.recordUntilABlockIsOnDisk();
        DeviceId device = fixture.activeDevice();
        assertThat(fixture.engineController().orElseThrow().getWatchedDevice())
                .as("premise: the stream-open seam bound the controller to the recording device")
                .contains(device);
        int before = fixture.shownCount();

        fixture.unplug();
        fixture.awaitRescued();

        TakeManifest manifest = TakeManifest.read(TakeManifest.manifestPath(takeDirectory));
        assertThat(manifest.sealStatus()).isEqualTo(TakeManifest.SealStatus.ABORTED);
        assertThat(manifest.sealedBy()).contains(TakeManifest.SealedBy.DEVICE_LOSS);
        assertThat(manifest.sealedAt()).as("sealed-at is stamped").isPresent();
        assertThat(filesUnder(takeDirectory)).as("every segment is sealed; no .part remains")
                .noneMatch(file -> file.getFileName().toString().endsWith(".part"));
        assertThat(takeDirectory.resolve(fixture.guitar().getId()).resolve("segment-000.wav")).isRegularFile();

        Optional<IncompleteTakeStore> store = onFx(() -> fixture.coordinator().rescuedTakeStore());
        assertThat(store).as("the project has a rescued-take registry").isPresent();
        Path takesDirectory = TakeDirectories.takesDirectory(ProjectManager.audioDirectory(projectDirectory))
                .toAbsolutePath().normalize();
        assertThat(store.orElseThrow().rescuedTakes())
                .extracting(IncompleteTakeStore.RescuedTake::reason, IncompleteTakeStore.RescuedTake::takeDirectory,
                        IncompleteTakeStore.RescuedTake::deviceName, IncompleteTakeStore.RescuedTake::clipCount,
                        IncompleteTakeStore.RescuedTake::sealedSegmentCount)
                .containsExactly(tuple(TakeManifest.SealedBy.DEVICE_LOSS, takeDirectory.toAbsolutePath().normalize(),
                        Optional.of(device.name()), 1, 1));
        assertThat(store.orElseThrow().latest().orElseThrow().takeDirectory().getParent())
                .as("the registered take lies under the project's audio/takes").isEqualTo(takesDirectory);

        String message = RecordCoordinator.rescuedTakeMessage(device, 1, 1);
        assertThat(message).contains(device.name()).contains("saved");
        List<NotificationEntry> entries = fixture.entriesSince(before);
        assertThat(entries).as("one WARNING with the Review action, and nothing else")
                .extracting(NotificationEntry::level, NotificationEntry::message, NotificationEntry::actionLabel)
                .containsExactly(tuple(NotificationLevel.WARNING, message, Optional.of("Review")));

        assertThat(onFx(() -> fixture.coordinator().getState())).isEqualTo(RecordState.DEVICE_LOST);
        assertThat(onFx(() -> fixture.recIndicator().isVisible())).as("REC is off").isFalse();
        assertThat(onFx(() -> fixture.statusBar().getText()))
                .isEqualTo(RecordCoordinator.deviceLostStatus(device, true));
        assertThat(onFx(() -> fixture.transportStatus().getText())).isEqualTo("Device lost");
        assertThat(onFx(() -> List.copyOf(fixture.transportStatus().getStyleClass())))
                .contains("status-device-lost");
        assertThat(onFx(() -> fixture.project().getTransport().getState())).isEqualTo(TransportState.STOPPED);
        assertThat(onFx(() -> List.copyOf(fixture.guitar().getClips()))).as("the clip is on the track").hasSize(1);
        assertThat(onFx(() -> fixture.project().isDirty())).isTrue();

        assertThat(fixture.engineController().orElseThrow().engineState()).isEqualTo(EngineState.DEVICE_LOST);
        assertThat(fixture.engine().isStreamOpen()).as("the lost device's stream is closed").isFalse();
        assertThat(fixture.engineNotices()).anyMatch(notice -> notice.contains("disconnected"));

        double anchor = fixture.pipeline().getRecordingStartBeat();
        Runnable review = entries.getFirst().action().orElseThrow();
        runOnFx(() -> {
            fixture.project().getTransport().setPositionInBeats(anchor + 3.0);
            review.run();
        });
        assertThat(onFx(() -> fixture.project().getTransport().getPositionInBeats()))
                .as("Review seeks to the take's start anchor while the transport is stopped")
                .isCloseTo(anchor, within(1e-9));
        assertThat(onFx(() -> fixture.statusBar().getText()))
                .startsWith("Rescued take: audio/takes/" + takeDirectory.getFileName() + " — ")
                .endsWith(" recorded");
    }

    /**
     * Fix round A7: the Review action seeks only while the transport is
     * STOPPED. Once the device has returned and the user plays, Review over
     * the rolling transport only puts the take's folder and length in the
     * status bar; the playhead is not pulled back to the anchor.
     */
    @Test
    void reviewOverARollingTransportSetsTheStatusButDoesNotSeek() throws Exception {
        Path takeDirectory = fixture.recordUntilABlockIsOnDisk();
        DeviceId device = fixture.activeDevice();
        int before = fixture.shownCount();
        fixture.unplug();
        fixture.awaitRescued();
        Runnable review = fixture.entriesSince(before).stream()
                .filter(entry -> entry.actionLabel().equals(Optional.of("Review"))).findFirst().orElseThrow()
                .action().orElseThrow();
        double anchor = fixture.pipeline().getRecordingStartBeat();
        fixture.plugIn(device);
        awaitOnFx(() -> fixture.coordinator().getState() == RecordState.IDLE, "the coordinator settled to IDLE");
        runOnFx(() -> {
            fixture.project().getTransport().setPositionInBeats(anchor + 5.0);
            fixture.controller().start();
        });
        assertThat(onFx(() -> fixture.project().getTransport().getState()))
                .as("premise: the transport rolls again after the return").isEqualTo(TransportState.PLAYING);

        runOnFx(review);

        assertThat(onFx(() -> fixture.project().getTransport().getPositionInBeats()))
                .as("no seek over a rolling transport").isGreaterThanOrEqualTo(anchor + 4.0);
        assertThat(onFx(() -> fixture.statusBar().getText()))
                .startsWith("Rescued take: audio/takes/" + takeDirectory.getFileName() + " — ")
                .endsWith(" recorded");
        runOnFx(fixture.controller()::stop);
    }

    @Test
    void aDifferentDeviceArrivingChangesNothingAndRepeatsTheRemediation() throws Exception {
        Path takeDirectory = fixture.recordUntilABlockIsOnDisk();
        DeviceId lost = fixture.activeDevice();
        fixture.unplug();
        fixture.awaitRescued();
        DeviceId other = new DeviceId(fixture.backend().name(), "Some Other Interface");
        int before = fixture.shownCount();

        fixture.plugIn(other);
        await(() -> stillDisconnectedNotices().size() >= 1, "the first remediation");
        fixture.plugIn(other);
        await(() -> stillDisconnectedNotices().size() >= 2, "the second remediation");
        runOnFx(() -> { }); // whatever the arrivals posted has run

        assertThat(stillDisconnectedNotices()).as("once per arrival, naming both devices").hasSize(2)
                .allSatisfy(notice -> assertThat(notice).contains(lost.name()).contains(other.name()));
        assertThat(fixture.engineController().orElseThrow().engineState()).isEqualTo(EngineState.DEVICE_LOST);
        assertThat(fixture.engine().isStreamOpen()).as("nothing was reopened").isFalse();
        assertThat(onFx(() -> fixture.coordinator().getState())).isEqualTo(RecordState.DEVICE_LOST);
        assertThat(onFx(() -> fixture.coordinator().recordAvailableProperty().get())).isFalse();
        assertThat(fixture.entriesSince(before)).as("the transport showed nothing new").isEmpty();
        assertThat(fixture.engineNotices()).noneMatch(notice -> notice.contains("reconnected"));
        assertThat(onFx(() -> fixture.guitar().isArmed())).isTrue();
        assertThat(onFx(() -> fixture.coordinator().rescuedTakeStore()).orElseThrow().rescuedTakes())
                .extracting(IncompleteTakeStore.RescuedTake::takeDirectory)
                .containsExactly(takeDirectory.toAbsolutePath().normalize());
    }

    @Test
    void theLostDeviceReturningSettlesToIdleWithTheArmedSetAndRecordsOnlyOnRequest() throws Exception {
        Path takeDirectory = fixture.recordUntilABlockIsOnDisk();
        DeviceId device = fixture.activeDevice();
        fixture.unplug();
        fixture.awaitRescued();
        double anchor = fixture.pipeline().getRecordingStartBeat();
        runOnFx(() -> fixture.project().getTransport().setPositionInBeats(anchor + 5.0));
        int before = fixture.shownCount();

        fixture.plugIn(device);
        awaitOnFx(() -> fixture.coordinator().getState() == RecordState.IDLE, "the coordinator settled to IDLE");

        assertThat(fixture.engineController().orElseThrow().engineState()).isEqualTo(EngineState.STOPPED);
        assertThat(fixture.engine().isStreamOpen()).as("reopened").isTrue();
        assertThat(fixture.engine().isStreamPaused()).as("open but parked; nothing records").isTrue();
        assertThat(onFx(() -> fixture.guitar().isArmed())).as("the armed set is kept").isTrue();
        assertThat(onFx(() -> fixture.project().getTransport().getState())).isEqualTo(TransportState.STOPPED);
        assertThat(onFx(() -> fixture.project().getTransport().getPositionInBeats()))
                .as("the transport sits at the rescued take's start anchor").isCloseTo(anchor, within(1e-9));
        assertThat(onFx(() -> fixture.coordinator().recordAvailableProperty().get())).isTrue();
        assertThat(onFx(() -> fixture.controller().isRecordingInFlight())).isFalse();
        assertThat(onFx(() -> fixture.recIndicator().isVisible())).isFalse();
        assertThat(onFx(() -> fixture.transportStatus().getText())).isNotEqualTo("Device lost");
        assertThat(fixture.entriesSince(before))
                .extracting(NotificationEntry::level, NotificationEntry::message)
                .containsExactly(tuple(NotificationLevel.INFO,
                        device.name() + " reconnected. Tracks are still armed; press Record to continue."));

        runOnFx(fixture.controller()::toggleRecord);
        awaitOnFx(() -> !fixture.controller().isPreparingTake(), "the new take's files were created");
        Path newTakeDirectory = onFx(() -> fixture.controller().activeTakeDirectory()).orElseThrow();
        assertThat(newTakeDirectory).as("Record starts a new take, not the rescued one").isNotEqualTo(takeDirectory);
        assertThat(onFx(() -> fixture.coordinator().getState())).isEqualTo(RecordState.RECORDING);
        assertThat(TakeManifest.read(TakeManifest.manifestPath(takeDirectory)).sealedBy())
                .as("the rescued take is untouched").contains(TakeManifest.SealedBy.DEVICE_LOSS);
    }

    private List<String> stillDisconnectedNotices() {
        return fixture.engineNotices().stream().filter(notice -> notice.contains("is still disconnected")).toList();
    }

    private static List<Path> filesUnder(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }
}
