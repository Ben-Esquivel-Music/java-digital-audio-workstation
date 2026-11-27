package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.recording.EarlySeal;
import com.benesquivelmusic.daw.core.recording.FinalManifestFailure;
import com.benesquivelmusic.daw.core.recording.TakeManifest;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Story 338, ruling E5: when the heartbeat watchdog declared the device lost
 * during a take, every report of the rescued take leads with
 * "&lt;name&gt; stopped responding during recording", followed by the tail the
 * removal's report has, unchanged; a removal keeps "disconnected during
 * recording" byte for byte. Each report site of the coordinator is reached
 * through a rescue the coordinator is told was a stall.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class StalledDeviceLossCopyTest {

    private static final EngineStateChange.LossCause STALLED = EngineStateChange.LossCause.STALLED;
    private static final EngineStateChange.LossCause REMOVED = EngineStateChange.LossCause.REMOVED;

    @TempDir
    Path projectDirectory;

    private final DeviceId lost = new DeviceId("Mock", "Silent Interface");
    private DeviceLossRecordingFixture fixture;

    @BeforeEach
    void openTheProject() throws Exception {
        fixture = DeviceLossRecordingFixture.withoutEngineBridge(projectDirectory);
    }

    @AfterEach
    void stopEverything() throws Exception {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void theStallLeadReplacesOnlyTheLeadAndTheRemovalsWordsStayAsTheyWere() {
        assertThat(RecordCoordinator.rescuedTakeMessage(lost, STALLED, 1, 1))
                .isEqualTo("Silent Interface stopped responding during recording."
                        + " The take was saved up to the dropout: 1 clip on 1 track.");
        assertThat(RecordCoordinator.rescuedTakeMessage(lost, STALLED, 0, 0))
                .isEqualTo("Silent Interface stopped responding during recording; nothing had reached disk yet.");
        assertThat(RecordCoordinator.rescuedTakeMessage(lost, REMOVED, 2, 2))
                .as("negative control: the removal's lead")
                .isEqualTo("Silent Interface was disconnected during recording."
                        + " The take was saved up to the dropout: 2 clips on 2 tracks.")
                .isEqualTo(RecordCoordinator.rescuedTakeMessage(lost, 2, 2));
        FinalManifestFailure failure = new FinalManifestFailure(new IllegalStateException("manifest build failed"),
                TakeManifest.SealedBy.DEVICE_LOSS);
        assertThat(RecordCoordinator.rescuedTakeManifestNotWrittenMessage(lost, STALLED, failure, 0, 0))
                .isEqualTo("Silent Interface stopped responding during recording; nothing had reached disk yet, and"
                        + " the take's manifest could not be written (IllegalStateException: manifest build failed)");
        assertThat(RecordCoordinator.rescuedTakeManifestNotWrittenMessage(lost, REMOVED, failure, 1, 1))
                .isEqualTo(RecordCoordinator.rescuedTakeManifestNotWrittenMessage(lost, failure, 1, 1));
        EarlySeal seal = new EarlySeal.WriteFailed(new IOException("injected lane write failure"), false);
        Path takeDirectory = projectDirectory.resolve("take-1");
        assertThat(RecordCoordinator.rescuedTakeSealedEarlyMessage(lost, STALLED, seal, 1, takeDirectory))
                .startsWith("Silent Interface stopped responding during recording. Saving the take ended early — ")
                .endsWith(RecordCoordinator.rescuedTakeSealedEarlyMessage(lost, seal, 1, takeDirectory)
                        .substring("Silent Interface was disconnected during recording".length()));
    }

    @Test
    void aStallRescuedTakeIsReportedWithTheStallLeadAndReview() throws Exception {
        fixture.recordABudgetedTakeUntilABlockIsOnDisk();
        int before = fixture.shownCount();

        loseTheDeviceToAStall();
        fixture.awaitRescued();

        assertThat(fixture.entriesSince(before))
                .extracting(NotificationEntry::level, NotificationEntry::message, NotificationEntry::actionLabel)
                .containsExactly(tuple(NotificationLevel.WARNING,
                        RecordCoordinator.rescuedTakeMessage(lost, STALLED, 1, 1), Optional.of("Review")));
        assertThat(DeviceLossRecordingFixture.onFx(() -> fixture.statusBar().getText()))
                .as("the status text is not changed by the cause")
                .isEqualTo(RecordCoordinator.deviceLostStatus(lost, true));
    }

    @Test
    void aStallRescueWhoseManifestCannotBeWrittenLeadsItsErrorWithTheStall() throws Exception {
        fixture.recordABudgetedTakeUntilABlockIsOnDisk();
        FinalManifestFailure notWritten = new FinalManifestFailure(
                new IllegalStateException("manifest build failed"), TakeManifest.SealedBy.DEVICE_LOSS);
        runOnFx(() -> fixture.coordinator().setStopSealOutcomeForTest(_ ->
                new TransportController.StopSealOutcome(Optional.empty(), Optional.of(notWritten))));
        int before = fixture.shownCount();

        loseTheDeviceToAStall();
        fixture.awaitPublished();

        assertThat(messages(before)).containsExactly(
                RecordCoordinator.rescuedTakeManifestNotWrittenMessage(lost, STALLED, notWritten, 1, 1));
    }

    @Test
    void aStallRescueSealedEarlyLeadsItsErrorWithTheStall() throws Exception {
        CompletableFuture<EarlySeal> earlySeal = new CompletableFuture<>();
        runOnFx(() -> fixture.controller().setEarlySealSignalForTest(_ -> earlySeal));
        Path takeDirectory = fixture.recordABudgetedTakeUntilABlockIsOnDisk();
        EarlySeal seal = new EarlySeal.WriteFailed(new IOException("injected lane write failure"), false);
        int before = fixture.shownCount();

        fixture.closeTheStreamAsTheLossDoes();
        runOnFx(() -> {
            fixture.coordinator().onDeviceLost(lost, STALLED);
            earlySeal.complete(seal);
        });
        fixture.awaitPublished();

        assertThat(messages(before)).containsExactly(
                RecordCoordinator.rescuedTakeSealedEarlyMessage(lost, STALLED, seal, 1, takeDirectory));
    }

    @Test
    void aStallRescueWhoseAudioCannotBeReadBackLeadsItsErrorWithTheStall() throws Exception {
        fixture.recordABudgetedTakeUntilABlockIsOnDisk();
        runOnFx(() -> fixture.coordinator().setStorageExecutorForTest(_ -> {
            throw new RejectedExecutionException("the storage executor is closed");
        }));
        int before = fixture.shownCount();

        loseTheDeviceToAStall();
        fixture.awaitPublished();

        String rescueMessage = RecordCoordinator.rescuedTakeMessage(lost, STALLED, 1, 1);
        assertThat(messages(before)).singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .startsWith(rescueMessage.substring(0, rescueMessage.length() - 1) + "; ")
                .contains("could not be loaded for playback");
    }

    private void loseTheDeviceToAStall() throws Exception {
        fixture.closeTheStreamAsTheLossDoes();
        runOnFx(() -> fixture.coordinator().onDeviceLost(lost, STALLED));
    }

    private List<String> messages(int before) {
        return fixture.entriesSince(before).stream().map(NotificationEntry::message).toList();
    }
}
