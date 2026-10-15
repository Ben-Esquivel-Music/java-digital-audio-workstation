package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.recording.RecordState;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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
 * take, through DEVICE_LOST to ABORTED, and is not registered; so is a
 * rescue whose every lane sealed but whose final manifest write failed on
 * every attempt (PR #982 review): the manifest on disk still reads
 * streaming, so the take is no rescued take — one ERROR that leads with the
 * device-loss wording and names the manifest, never "take preserved"; and
 * so is a rescue the capture thread sealed early inside the rescue's own
 * stop (review round 2): its manifest does not read device-loss, so it is
 * reported, never registered. The
 * loss is reported to the coordinator directly here; the production wiring
 * is pinned by {@code DeviceLossRescuesTheTakeThroughTheEngineBridgeTest}.
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

    /**
     * No seam between the flush thread and the coordinator: the final
     * manifest write fails through the production wiring because a
     * non-empty directory sits where the manifest's staging file goes
     * ({@code take.manifest.tmp}) — opening the staging channel fails on
     * every attempt, on Windows and on Linux alike — so the manifest on
     * disk stays the take start's while every lane sealed.
     */
    @Test
    void aRescueWhoseManifestCannotBeWrittenIsReportedAsAnErrorAndNeverRegistered() throws Exception {
        Path takeDirectory = fixture.recordUntilABlockIsOnDisk();
        Path staging = takeDirectory.resolve(TakeManifest.FILE_NAME + TakeManifest.TMP_SUFFIX);
        Files.createDirectory(staging);
        Files.writeString(staging.resolve("occupied"), "the staging file cannot be opened", StandardCharsets.UTF_8);
        byte[] manifestBefore = Files.readAllBytes(TakeManifest.manifestPath(takeDirectory));
        List<RecordState> states = new ArrayList<>();
        runOnFx(() -> fixture.coordinator().stateProperty().addListener((_, _, state) -> states.add(state)));
        int before = fixture.shownCount();

        fixture.closeTheStreamAsTheLossDoes();
        runOnFx(() -> fixture.coordinator().onDeviceLost(lost));
        fixture.awaitPublished();

        List<NotificationEntry> entries = fixture.entriesSince(before);
        assertThat(entries).as("one ERROR, no WARNING, no SUCCESS").hasSize(1);
        NotificationEntry report = entries.getFirst();
        assertThat(report.level()).isEqualTo(NotificationLevel.ERROR);
        assertThat(report.actionLabel()).as("no Review: there is no rescued take to review").isEmpty();
        assertThat(report.message())
                .startsWith(lost.name() + " disconnected during recording. The audio was saved up to the dropout"
                        + " (1 clip on 1 track), but the take's manifest could not be written (")
                .endsWith("); take recovery rebuilds it from the segments")
                .doesNotContain("next project open")
                .doesNotContain(".;")
                .doesNotContainIgnoringCase("preserved")
                .doesNotStartWith("Recording stopped");
        assertThat(onFx(() -> fixture.statusBar().getText())).isEqualTo(report.message());
        assertThat(onFx(() -> List.copyOf(states))).as("DEVICE_LOST, then ABORTED (rescue failure), then settled")
                .containsSubsequence(RecordState.DEVICE_LOST, RecordState.ABORTED).endsWith(RecordState.IDLE);
        assertThat(onFx(() -> fixture.coordinator().rescuedTakeStore()).orElseThrow().isEmpty())
                .as("a take whose manifest was not written is not a rescued take").isTrue();
        assertThat(onFx(() -> List.copyOf(fixture.guitar().getClips()))).as("the clip is published all the same")
                .hasSize(1);
        assertThat(onFx(() -> fixture.project().isDirty())).isTrue();
        assertThat(takeDirectory.resolve(fixture.guitar().getId()).resolve("segment-000.wav"))
                .as("every lane sealed").isRegularFile();
        assertThat(fixture.firstSegmentPart(takeDirectory)).doesNotExist();
        assertThat(Files.readAllBytes(TakeManifest.manifestPath(takeDirectory)))
                .as("the manifest on disk is the take start's").isEqualTo(manifestBefore);
        assertThat(TakeManifest.read(TakeManifest.manifestPath(takeDirectory)).sealStatus())
                .isEqualTo(TakeManifest.SealStatus.STREAMING);
        assertThat(fixture.pipeline().finalManifestFailure()).as("the reason the manifest was to carry")
                .isPresent().get().extracting(FinalManifestFailure::intendedReason)
                .isEqualTo(TakeManifest.SealedBy.DEVICE_LOSS);
        assertThat(uncaughtOnFx).isEmpty();
    }

    /**
     * The same through the seam, with a reason this module cannot cause on
     * disk — the manifest's build threw — pinning the exact report: the
     * device-loss lead, the audio saved, the manifest not written with the
     * throwable's type and message, the rebuild by take recovery; with no
     * clip, no rebuild is promised — no track recorded a frame, the live
     * seal deleted the empty tails, and the scan has no segment to rebuild
     * the take from (PR #982 review round 2; story 327, Known limitations).
     */
    @Test
    void aRescueWhoseManifestFailureNamesItsReasonIsReportedAsAnErrorAndNeverRegistered() throws Exception {
        fixture.recordUntilABlockIsOnDisk();
        FinalManifestFailure notWritten = new FinalManifestFailure(
                new IllegalStateException("manifest build failed"), TakeManifest.SealedBy.DEVICE_LOSS);
        runOnFx(() -> fixture.coordinator().setStopSealOutcomeForTest(_ ->
                new TransportController.StopSealOutcome(Optional.empty(), Optional.of(notWritten))));
        List<RecordState> states = new ArrayList<>();
        runOnFx(() -> fixture.coordinator().stateProperty().addListener((_, _, state) -> states.add(state)));
        int before = fixture.shownCount();

        fixture.closeTheStreamAsTheLossDoes();
        runOnFx(() -> fixture.coordinator().onDeviceLost(lost));
        fixture.awaitPublished();

        String report = lost.name() + " disconnected during recording. The audio was saved up to the dropout"
                + " (1 clip on 1 track), but the take's manifest could not be written (IllegalStateException:"
                + " manifest build failed); take recovery rebuilds it from the segments";
        assertThat(RecordCoordinator.rescuedTakeManifestNotWrittenMessage(lost, notWritten, 1, 1)).isEqualTo(report);
        assertThat(RecordCoordinator.rescuedTakeManifestNotWrittenMessage(lost, notWritten, 0, 0))
                .as("with no clip, nothing had reached disk, and no rebuild is promised")
                .isEqualTo(lost.name() + " disconnected during recording; nothing had reached disk yet, and the"
                        + " take's manifest could not be written (IllegalStateException: manifest build failed)")
                .doesNotContain("rebuilds");
        assertThat(fixture.entriesSince(before)).as("one ERROR, no WARNING, no SUCCESS")
                .extracting(NotificationEntry::level, NotificationEntry::message, NotificationEntry::actionLabel)
                .containsExactly(tuple(NotificationLevel.ERROR, report, Optional.empty()));
        assertThat(onFx(() -> fixture.statusBar().getText())).isEqualTo(report);
        assertThat(onFx(() -> List.copyOf(states)))
                .containsSubsequence(RecordState.DEVICE_LOST, RecordState.ABORTED).endsWith(RecordState.IDLE);
        assertThat(onFx(() -> fixture.coordinator().rescuedTakeStore()).orElseThrow().isEmpty())
                .as("not registered").isTrue();
        assertThat(onFx(() -> List.copyOf(fixture.guitar().getClips()))).hasSize(1);
        assertThat(uncaughtOnFx).isEmpty();
    }

    /**
     * PR #982 review round 2: a stop requested first does not prevent an
     * early seal — an exhaustion, or a write failure applying a block the
     * rescue's own final sweep drains, completes the signal inside the
     * rescue — and such a take is no rescued take on disk: its manifest
     * reads write-failure or disk-exhaustion, not device-loss. The seal
     * outcome the pipeline reports is clean here (the real seal was clean;
     * the signal is the seam, completed on the FX turn of the loss right
     * after the rescue was requested, as the capture thread would complete
     * it in the final sweep), so the early seal alone keeps the take out of
     * the registry: one ERROR leading with the device-loss wording, never
     * "take preserved", DEVICE_LOST → ABORTED → IDLE, the clip published.
     */
    @Test
    void aRescueSealedEarlyInsideItsOwnStopIsReportedAsAnErrorAndNeverRegistered() throws Exception {
        CompletableFuture<EarlySeal> earlySeal = new CompletableFuture<>();
        runOnFx(() -> fixture.controller().setEarlySealSignalForTest(_ -> earlySeal));
        Path takeDirectory = fixture.recordUntilABlockIsOnDisk();
        EarlySeal seal = new EarlySeal.WriteFailed(new IOException("injected lane write failure"), false);
        List<RecordState> states = new ArrayList<>();
        runOnFx(() -> fixture.coordinator().stateProperty().addListener((_, _, state) -> states.add(state)));
        int before = fixture.shownCount();

        fixture.closeTheStreamAsTheLossDoes();
        // One FX turn: the rescue is requested, then the signal completes;
        // its dependent posts the auto-Stop to a later turn, which finds the
        // take already stopping and does nothing.
        runOnFx(() -> {
            fixture.coordinator().onDeviceLost(lost);
            earlySeal.complete(seal);
        });
        fixture.awaitPublished();

        String report = RecordCoordinator.rescuedTakeSealedEarlyMessage(lost, seal, 1, takeDirectory);
        assertThat(report)
                .isEqualTo(lost.name() + " disconnected during recording. Saving the take ended early — writing the"
                        + " take to disk failed (injected lane write failure); one or more segment files that could"
                        + " not be finished are left under audio/takes/" + takeDirectory.getFileName()
                        + " (1 clip created)")
                .startsWith(lost.name())
                .doesNotContainIgnoringCase("preserved")
                .doesNotContainIgnoringCase("saved")
                .doesNotStartWith("Recording stopped");
        assertThat(fixture.entriesSince(before)).as("one ERROR, no WARNING, no SUCCESS")
                .extracting(NotificationEntry::level, NotificationEntry::message, NotificationEntry::actionLabel)
                .containsExactly(tuple(NotificationLevel.ERROR, report, Optional.empty()));
        assertThat(onFx(() -> fixture.statusBar().getText())).isEqualTo(report);
        assertThat(onFx(() -> List.copyOf(states))).as("DEVICE_LOST, then ABORTED (rescue failure), then settled")
                .containsSubsequence(RecordState.DEVICE_LOST, RecordState.ABORTED).endsWith(RecordState.IDLE);
        assertThat(onFx(() -> fixture.coordinator().rescuedTakeStore()).orElseThrow().isEmpty())
                .as("a take sealed early is not a rescued take").isTrue();
        assertThat(onFx(() -> List.copyOf(fixture.guitar().getClips()))).as("the clip is published all the same")
                .hasSize(1);
        assertSealedByDeviceLoss(takeDirectory); // fixture: the real seal was clean; the signal alone decided
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
