package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.recording.RecordState;
import com.benesquivelmusic.daw.core.recording.TakeManifest;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Story 327, book §5.5: each step of the rescue sequence completes
 * regardless of a later step's failure. A notification step that throws
 * still leaves the take sealed on disk, published and registered; a
 * registration step that throws still leaves it sealed and its clips
 * published, and the WARNING still shown; neither failure reaches the FX
 * thread's uncaught handler. And a rescue whose own seal fails on disk —
 * a file already sits where the first segment is to be sealed — is
 * reported once as the ERROR of a failed finish, with no word of a saved
 * take, through DEVICE_LOST to ABORTED, and is not registered. The loss is
 * reported to the coordinator directly here; the production wiring is
 * pinned by {@code DeviceLossRescuesTheTakeThroughTheEngineBridgeTest}.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class DeviceLossRescueStepIndependenceTest {

    private static final String ALREADY_THERE = "not a segment of this take";

    @TempDir
    Path projectDirectory;

    private final DeviceId lost = new DeviceId("Mock", "Yanked Interface");
    private final List<Throwable> uncaughtOnFx = new CopyOnWriteArrayList<>();
    private DeviceLossRecordingFixture fixture;
    private Thread.UncaughtExceptionHandler previousHandler;

    @BeforeEach
    void watchTheFxThreadAndOpenTheProject() throws Exception {
        runOnFx(() -> {
            previousHandler = Thread.currentThread().getUncaughtExceptionHandler();
            Thread.currentThread().setUncaughtExceptionHandler((_, failure) -> uncaughtOnFx.add(failure));
        });
        fixture = DeviceLossRecordingFixture.withoutEngineBridge(projectDirectory);
    }

    @AfterEach
    void stopEverything() throws Exception {
        try {
            if (fixture != null) {
                fixture.close();
            }
        } finally {
            runOnFx(() -> Thread.currentThread().setUncaughtExceptionHandler(previousHandler));
        }
    }

    @Test
    void aFailingNotificationStepStillLeavesTheTakeSealedAndRegistered() throws Exception {
        runOnFx(() -> fixture.coordinator().setRescuedTakeNotificationForTest((message, review) -> {
            throw new IllegalStateException("the notification bar is broken");
        }));
        Path takeDirectory = fixture.recordUntilABlockIsOnDisk();
        int before = fixture.shownCount();

        fixture.closeTheStreamAsTheLossDoes();
        runOnFx(() -> fixture.coordinator().onDeviceLost(lost));
        fixture.awaitPublished();

        assertSealedByDeviceLoss(takeDirectory);
        assertThat(onFx(() -> fixture.coordinator().rescuedTakeStore()).orElseThrow().rescuedTakes())
                .extracting(IncompleteTakeStore.RescuedTake::reason, IncompleteTakeStore.RescuedTake::takeDirectory,
                        IncompleteTakeStore.RescuedTake::deviceName)
                .containsExactly(tuple(TakeManifest.SealedBy.DEVICE_LOSS, takeDirectory.toAbsolutePath().normalize(),
                        Optional.of(lost.name())));
        assertThat(onFx(() -> List.copyOf(fixture.guitar().getClips()))).hasSize(1);
        assertThat(onFx(() -> fixture.coordinator().getState())).isEqualTo(RecordState.DEVICE_LOST);
        assertThat(onFx(() -> fixture.statusBar().getText()))
                .isEqualTo(RecordCoordinator.deviceLostStatus(lost, true));
        assertThat(fixture.entriesSince(before)).as("the broken step showed nothing, and nothing else was shown")
                .isEmpty();
        assertThat(uncaughtOnFx).as("the failure was logged, not propagated").isEmpty();
    }

    @Test
    void aFailingRegistrationStepStillLeavesTheTakeSealedAndItsClipsPublished() throws Exception {
        runOnFx(() -> fixture.coordinator().setRescuedTakeRegistrationForTest(take -> {
            throw new IllegalStateException("the registry is broken");
        }));
        Path takeDirectory = fixture.recordUntilABlockIsOnDisk();
        int before = fixture.shownCount();

        fixture.closeTheStreamAsTheLossDoes();
        runOnFx(() -> fixture.coordinator().onDeviceLost(lost));
        fixture.awaitPublished();

        assertSealedByDeviceLoss(takeDirectory);
        assertThat(onFx(() -> List.copyOf(fixture.guitar().getClips()))).as("the clip is published").hasSize(1);
        assertThat(onFx(() -> fixture.project().isDirty())).isTrue();
        assertThat(onFx(() -> fixture.coordinator().rescuedTakeStore()).orElseThrow().isEmpty())
                .as("nothing reached the registry").isTrue();
        assertThat(onFx(() -> fixture.coordinator().getState())).isEqualTo(RecordState.DEVICE_LOST);
        assertThat(fixture.entriesSince(before)).as("the WARNING after the broken step is still shown")
                .extracting(NotificationEntry::level, NotificationEntry::message, NotificationEntry::actionLabel)
                .containsExactly(tuple(NotificationLevel.WARNING, RecordCoordinator.rescuedTakeMessage(lost, 1, 1),
                        Optional.of("Review")));
        assertThat(uncaughtOnFx).as("the failure was logged, not propagated").isEmpty();
    }

    @Test
    void aRescueWhoseSealFailsIsReportedAsAnErrorAndNeverAsSaved() throws Exception {
        Path takeDirectory = fixture.recordUntilABlockIsOnDisk();
        Path sealedPath = takeDirectory.resolve(fixture.guitar().getId()).resolve("segment-000.wav");
        Files.writeString(sealedPath, ALREADY_THERE, StandardCharsets.UTF_8);
        List<RecordState> states = new ArrayList<>();
        runOnFx(() -> fixture.coordinator().stateProperty().addListener((_, _, state) -> states.add(state)));
        int before = fixture.shownCount();

        fixture.closeTheStreamAsTheLossDoes();
        runOnFx(() -> fixture.coordinator().onDeviceLost(lost));
        fixture.awaitPublished();

        String report = "Recording stopped — finishing the take on disk failed (sealed segment already exists;"
                + " a segment is never overwritten); one or more segment files that could not be finished are"
                + " left under audio/takes/" + takeDirectory.getFileName() + " (1 clip created)";
        assertThat(fixture.entriesSince(before)).as("one ERROR, no WARNING, no SUCCESS")
                .extracting(NotificationEntry::level, NotificationEntry::message, NotificationEntry::actionLabel)
                .containsExactly(tuple(NotificationLevel.ERROR, report, Optional.empty()));
        assertThat(report).doesNotContainIgnoringCase("saved").doesNotContainIgnoringCase("preserved");
        assertThat(onFx(() -> fixture.statusBar().getText())).isEqualTo(report);
        List<RecordState> seen = onFx(() -> List.copyOf(states));
        assertThat(seen).as("DEVICE_LOST, then ABORTED, then settled")
                .containsSubsequence(RecordState.DEVICE_LOST, RecordState.ABORTED)
                .endsWith(RecordState.IDLE);
        assertThat(onFx(() -> fixture.coordinator().rescuedTakeStore()).orElseThrow().isEmpty())
                .as("a take whose seal failed is not a rescued take").isTrue();
        assertThat(onFx(() -> List.copyOf(fixture.guitar().getClips()))).as("the clip is published all the same")
                .hasSize(1);
        assertThat(fixture.firstSegmentPart(takeDirectory)).as("the segment is left as its .part").isRegularFile();
        assertThat(Files.readString(sealedPath, StandardCharsets.UTF_8)).isEqualTo(ALREADY_THERE);
        TakeManifest manifest = TakeManifest.read(TakeManifest.manifestPath(takeDirectory));
        assertThat(manifest.sealStatus()).isEqualTo(TakeManifest.SealStatus.ABORTED);
        assertThat(manifest.sealedBy()).as("the lane that threw names the write failure, not the device")
                .contains(TakeManifest.SealedBy.WRITE_FAILURE);
        assertThat(uncaughtOnFx).isEmpty();
    }

    /**
     * Fix round A1: the rescue is requested before any UI step. The fixture
     * cannot make a UI step throw ({@code Labeled.setText} is final and a
     * throwing property listener is routed to the uncaught handler, not
     * back into the caller), so the order itself is pinned: at the instant
     * the state becomes DEVICE_LOST the pipeline's stop has already been
     * requested (it is no longer active), no take is in flight any more,
     * and the take is already being written — which is what guarantees
     * that a UI step throwing after that point cannot skip the rescue.
     */
    @Test
    void theRescueIsRequestedBeforeTheStateBecomesDeviceLost() throws Exception {
        fixture.recordUntilABlockIsOnDisk();
        List<String> atTheTransition = new CopyOnWriteArrayList<>();
        runOnFx(() -> fixture.coordinator().stateProperty().addListener((_, _, state) -> {
            if (state == RecordState.DEVICE_LOST) {
                atTheTransition.add("pipelineActive=" + fixture.pipeline().isActive());
                atTheTransition.add("inFlight=" + fixture.controller().isRecordingInFlight());
                atTheTransition.add("beingWritten=" + fixture.controller().isTakeBeingWritten());
            }
        }));

        fixture.closeTheStreamAsTheLossDoes();
        runOnFx(() -> fixture.coordinator().onDeviceLost(lost));
        fixture.awaitPublished();

        assertThat(atTheTransition).as("seen once, at the DEVICE_LOST transition")
                .containsExactly("pipelineActive=false", "inFlight=false", "beingWritten=true");
        assertThat(uncaughtOnFx).isEmpty();
    }

    /**
     * Fix round A2: a rescue whose every lane sealed is a rescued take on
     * disk even when its audio cannot be read back afterwards — here the
     * storage executor refuses the read, the shape of an {@link Error} in
     * the storage task — so it is registered all the same, and its ERROR
     * leads with the device-loss wording, never with "Recording stopped".
     */
    @Test
    void aRescueWhoseAudioCannotBeReadBackIsStillRegisteredAndReportedAsADeviceLoss() throws Exception {
        Path takeDirectory = fixture.recordUntilABlockIsOnDisk();
        // Installed once the take records: the record start allocates the
        // take directory on the same executor.
        runOnFx(() -> fixture.coordinator().setStorageExecutorForTest(_ -> {
            throw new RejectedExecutionException("the storage executor is closed");
        }));
        List<RecordState> states = new ArrayList<>();
        runOnFx(() -> fixture.coordinator().stateProperty().addListener((_, _, state) -> states.add(state)));
        int before = fixture.shownCount();

        fixture.closeTheStreamAsTheLossDoes();
        runOnFx(() -> fixture.coordinator().onDeviceLost(lost));
        fixture.awaitPublished();

        assertSealedByDeviceLoss(takeDirectory);
        assertThat(onFx(() -> fixture.coordinator().rescuedTakeStore()).orElseThrow().rescuedTakes())
                .as("registered: every lane sealed")
                .extracting(IncompleteTakeStore.RescuedTake::reason, IncompleteTakeStore.RescuedTake::takeDirectory,
                        IncompleteTakeStore.RescuedTake::clipCount, IncompleteTakeStore.RescuedTake::sealedSegmentCount)
                .containsExactly(tuple(TakeManifest.SealedBy.DEVICE_LOSS, takeDirectory.toAbsolutePath().normalize(),
                        1, 1));
        List<NotificationEntry> entries = fixture.entriesSince(before);
        assertThat(entries).as("one ERROR, no WARNING").hasSize(1);
        NotificationEntry report = entries.getFirst();
        assertThat(report.level()).isEqualTo(NotificationLevel.ERROR);
        String rescueMessage = RecordCoordinator.rescuedTakeMessage(lost, 1, 1);
        assertThat(rescueMessage).as("premise: the standalone message ends with a period").endsWith(".");
        assertThat(report.message()).as("leads with the device-loss wording, its period dropped before the join")
                .startsWith(rescueMessage.substring(0, rescueMessage.length() - 1) + "; ")
                .doesNotContain(".;")
                .contains("could not be loaded for playback")
                .doesNotStartWith("Recording stopped");
        assertThat(onFx(() -> fixture.statusBar().getText())).isEqualTo(report.message());
        assertThat(onFx(() -> List.copyOf(fixture.guitar().getClips()))).as("the clip is published without audio")
                .hasSize(1);
        assertThat(onFx(() -> List.copyOf(states))).as("DEVICE_LOST, then ABORTED (rescue failure), then settled")
                .containsSubsequence(RecordState.DEVICE_LOST, RecordState.ABORTED).endsWith(RecordState.IDLE);
        assertThat(uncaughtOnFx).isEmpty();
    }

    private void assertSealedByDeviceLoss(Path takeDirectory) throws Exception {
        TakeManifest manifest = TakeManifest.read(TakeManifest.manifestPath(takeDirectory));
        assertThat(manifest.sealStatus()).isEqualTo(TakeManifest.SealStatus.ABORTED);
        assertThat(manifest.sealedBy()).contains(TakeManifest.SealedBy.DEVICE_LOSS);
        assertThat(manifest.sealedAt()).isPresent();
        assertThat(fixture.firstSegmentPart(takeDirectory)).doesNotExist();
        assertThat(takeDirectory.resolve(fixture.guitar().getId()).resolve("segment-000.wav")).isRegularFile();
    }
}
