package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;
import com.benesquivelmusic.daw.core.persistence.journal.JournalRecord;
import com.benesquivelmusic.daw.core.persistence.journal.JournalState;
import com.benesquivelmusic.daw.core.persistence.journal.ProjectContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, Goals — Tests, "Stolen-lock", the exit half (the studio
 * engineer's ruling R7; ruling 2 OQ-4; the stage-4 S8 ruling; ruling 3 Q1's
 * exit assertions; Persistence Integrity book §9.14): steal the lock, then
 * exit. The blocking takeover dialog appears — its exit variant, Save As…
 * (the default), Quit without saving, Cancel — in place of story 331's
 * "Save failed" toast; Save As… pre-fills {@code <folder>-takeover-<stamp>}
 * beside the original and preserves the work there, a self-contained copy
 * that is published and not opened; the original folder is left to the other
 * session — nothing written to it but this session's own journal segment,
 * the interim drain (ruling R7, R12 #7), no seal, no checkpoint, no head, the
 * other session's lock untouched. One fact gets one dialog: a queued save
 * that meets the steal hands it to the exit, a loss already known shows the
 * exit dialog in place of the unsaved-changes prompt, and a project with no
 * unsaved changes exits without a dialog, writing nothing.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class StolenLockAtExitTest {

    private static final String HOLDER = "“Song A” was taken over by alice on Studio-PC (process 42).";
    private static final String EXIT_CONTENT = "Nothing was written to its folder, and your changes are still open"
            + " here. Save them as a new project before closing, or close without them.";
    /** Ruling 4 Q1: the note of a lost lock's tail at an exit. */
    private static final String TAKEN_OVER_NOTE = "Another session holds “Song A”, and this session is still"
            + " finishing its last writes in the project's folder after 10 seconds. The disk may not be responding."
            + " If you quit anyway, the application ends without waiting for them, and nothing more of your work is"
            + " lost.";

    @TempDir
    Path workspace;

    private ExitFixture fixture;
    private Path songA;

    @BeforeEach
    void songAWithATakeSavedThenEdited() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        songA = fixture.songA;
        Files.write(songA.resolve("audio").resolve("take.wav"), new byte[1024]);
        runOnFx(() -> fixture.save.project.get().getTracks().getFirst()
                .addClip(new AudioClip("take", 0, 1, songA.resolve("audio").resolve("take.wav").toString())));
        fixture.save.save();
        fixture.save.settle();
        fixture.edit("Bass");
    }

    @AfterEach
    void close() throws Exception {
        fixture.close();
    }

    @Test
    void saveAndCloseUnderAStolenLockOffersTheExitsSaveAsWhichKeepsTheWorkInACopyAndLeavesTheOriginal()
            throws Exception {
        steal();
        String foreignLock = Files.readString(songA.resolve(ProjectLockManager.LOCK_FILE_NAME));
        byte[] head = Files.readAllBytes(songA.resolve("project.daw"));
        Map<String, FileTime> original = withoutThisSessionsSegment(listing(songA));
        int checkpoints = SaveFlowFixture.checkpointFiles(songA).size();
        fixture.save.promptAnswer = "Save and close";
        fixture.save.takeoverAnswer = "Save As…";
        fixture.save.saveAsTargets.answer(call -> Optional.of(call.directory().resolve(call.name())));

        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        assertThat(fixture.save.takeoverHeaders).containsExactly(HOLDER);
        assertThat(fixture.save.takeoverContents).containsExactly(EXIT_CONTENT);
        assertThat(fixture.save.takeoverButtons).containsExactly(List.of("Save As…", "Quit without saving", "Cancel"));
        assertThat(fixture.save.takeoverDefaults).containsExactly("Save As…");
        SaveFlowFixture.SaveAsTargets.Call call = fixture.save.saveAsTargets.calls().getFirst();
        assertThat(call.directory()).as("R6: the original's parent").isEqualTo(workspace);
        assertThat(call.name()).matches("Song A-takeover-\\d{8}-\\d{6}");
        Path copy = workspace.resolve(call.name());
        String copied = Files.readString(copy.resolve("project.daw"), StandardCharsets.UTF_8);
        assertThat(copied).as("the work is in the copy").contains("Bass");
        assertThat(copied).as("self-contained: nothing resolves under the original").doesNotContain(songA.toString());
        assertThat(copy.resolve("audio").resolve("take.wav")).exists();
        assertThat(copy.resolve("checkpoints")).doesNotExist();
        assertThat(copy.resolve("journal")).doesNotExist();
        assertThat(copy.resolve(ProjectLockManager.LOCK_FILE_NAME)).as("published, not opened").doesNotExist();
        assertThat(fixture.save.notifications()).extracting(NotificationEntry::message)
                .noneMatch(message -> message.contains("Save failed"));
        assertTheOriginalWasLeftToTheOtherSession(foreignLock, head, original, checkpoints);
    }

    @Test
    void quitWithoutSavingWritesNothingToTheOriginalAndExits() throws Exception {
        steal();
        String foreignLock = Files.readString(songA.resolve(ProjectLockManager.LOCK_FILE_NAME));
        byte[] head = Files.readAllBytes(songA.resolve("project.daw"));
        Map<String, FileTime> original = withoutThisSessionsSegment(listing(songA));
        int checkpoints = SaveFlowFixture.checkpointFiles(songA).size();
        fixture.save.promptAnswer = "Save and close";
        fixture.save.takeoverAnswer = "Quit without saving";

        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        assertThat(fixture.save.takeoverHeaders).containsExactly(HOLDER);
        assertThat(fixture.save.saveAsTargets.calls()).isEmpty();
        assertTheOriginalWasLeftToTheOtherSession(foreignLock, head, original, checkpoints);
    }

    @Test
    void aLockFileRemovedByAnotherSessionGivesTheDialogWithNoHolderAndCancelKeepsTheProjectOpen()
            throws Exception {
        Files.delete(songA.resolve(ProjectLockManager.LOCK_FILE_NAME));
        fixture.save.promptAnswer = "Save and close";
        fixture.save.takeoverAnswer = "Cancel";

        fixture.closeBox();
        fixture.await(() -> fixture.save.takeoverHeaders.size() == 1 && fixture.steps.size() == 3, "the Cancel");

        assertThat(fixture.save.takeoverHeaders).containsExactly(
                "The lock of “Song A” was removed, so another session may have taken the project over.");
        assertThat(fixture.stepNames()).as("FLUSH met the steal: R7's exit dialog, whose Cancel stops the exit")
                .containsExactly("INTERCEPT DONE", "GUARD DONE", "FLUSH CANCELLED");
        assertThat(fixture.save.notifications()).as("no Save failed toast").isEmpty();
        assertThat(onFx(() -> fixture.stage().isShowing())).isTrue();
        assertThat(onFx(() -> fixture.save.project.get().isDirty())).isTrue();
    }

    @Test
    void aStolenLockWithNoUnsavedChangesExitsWithoutADialogWritingNothing() throws Exception {
        fixture.save.save();
        fixture.save.settle();
        steal();
        String foreignLock = Files.readString(songA.resolve(ProjectLockManager.LOCK_FILE_NAME));
        byte[] head = Files.readAllBytes(songA.resolve("project.daw"));
        Map<String, FileTime> original = withoutThisSessionsSegment(listing(songA));
        int checkpoints = SaveFlowFixture.checkpointFiles(songA).size();

        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        assertThat(fixture.save.takeoverHeaders).as("no dialog").isEmpty();
        assertThat(fixture.unsavedPrompts).isEmpty();
        assertTheOriginalWasLeftToTheOtherSession(foreignLock, head, original, checkpoints);
    }

    /**
     * Ruling 2 OQ-4: a save queued before the exit meets the steal — one
     * dialog, the exit's; no R2 prompt — and each of its answers' outcomes
     * (constraints 4 and 5; the stage-6 review S4): Save As… publishes a copy
     * and leaves the original as it was when the dialog asked, then HIDE;
     * Quit without saving writes nothing to the original, then HIDE; Cancel
     * keeps the project open, and a later Ctrl+S shows the Ctrl+S variant —
     * the close's hold on the lost lock was let go.
     */
    @ParameterizedTest
    @ValueSource(strings = {"Save As…", "Quit without saving", "Cancel"})
    void aQueuedSaveThatMeetsTheStealHandsItToTheExitWhichShowsItsOwnDialogOnce(String answer) throws Exception {
        java.util.concurrent.atomic.AtomicReference<Map<String, FileTime>> whenAsked =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<byte[]> headWhenAsked = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger checkpointsWhenAsked = new java.util.concurrent.atomic.AtomicInteger();
        fixture.save.whileTheTakeoverAsks = () -> {
            try {
                whenAsked.compareAndSet(null, withoutThisSessionsSegment(listing(songA)));
                headWhenAsked.compareAndSet(null, Files.readAllBytes(songA.resolve("project.daw")));
                checkpointsWhenAsked.set(SaveFlowFixture.checkpointFiles(songA).size());
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        };
        if (answer.equals("Save As…")) {
            fixture.save.saveAsTargets.answer(call -> Optional.of(call.directory().resolve(call.name())));
        }
        CompletionStage<ExitCoordinator.CloseOutcome> outcome = exitDuringAStolenQueuedSave(
                () -> fixture.fileExit(), answer);

        assertThat(fixture.save.takeoverHeaders).as("exactly one takeover dialog").containsExactly(HOLDER);
        assertThat(fixture.save.takeoverContents).as("the exit variant").containsExactly(EXIT_CONTENT);
        assertThat(fixture.unsavedPrompts).as("no R2 prompt").isEmpty();
        if (answer.equals("Cancel")) {
            assertThat(outcome.toCompletableFuture().get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS))
                    .isEqualTo(new ExitCoordinator.CloseOutcome.Cancelled());
            assertThat(fixture.stepNames()).containsExactly("INTERCEPT DONE", "GUARD CANCELLED");
            assertThat(onFx(() -> fixture.save.project.get().isDirty())).isTrue();
            assertThat(fixture.save.notifications()).as("no toast").isEmpty();
            fixture.save.takeoverAnswer = "Not now";
            fixture.save.save();
            fixture.save.settle();
            fixture.await(() -> fixture.save.takeoverHeaders.size() == 2, "Ctrl+S's own dialog");
            assertThat(fixture.save.takeoverContents.getLast()).as("constraint 4: the Ctrl+S variant").isEqualTo(
                    "Nothing was written to its folder, and your changes are still open here. Save them as a new"
                            + " project to keep them; the original folder is left to the other session.");
            assertThat(fixture.save.takeoverButtons.getLast()).containsExactly("Save As…", "Not now");
            return;
        }
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
        if (answer.equals("Save As…")) {
            SaveFlowFixture.SaveAsTargets.Call call = fixture.save.saveAsTargets.calls().getFirst();
            Path copy = workspace.resolve(call.name());
            assertThat(Files.readString(copy.resolve("project.daw"), StandardCharsets.UTF_8)).contains("Keys");
            assertThat(copy.resolve(ProjectLockManager.LOCK_FILE_NAME)).as("published, not opened").doesNotExist();
        } else {
            assertThat(fixture.save.saveAsTargets.calls()).isEmpty();
        }
        fixture.save.projectManager.whenRetiredLeasesReleased().get(ExitFixture.GUARD.toMillis(),
                TimeUnit.MILLISECONDS);
        assertThat(withoutThisSessionsSegment(listing(songA))).as("the original as it was when the dialog asked")
                .isEqualTo(whenAsked.get());
        assertThat(Files.readAllBytes(songA.resolve("project.daw"))).isEqualTo(headWhenAsked.get());
        assertThat(SaveFlowFixture.checkpointFiles(songA)).hasSize(checkpointsWhenAsked.get());
        assertThat(JournalState.records(ProjectContext.forProject(songA))).noneMatch(JournalRecord::isSessionSeal);
        assertThat(fixture.terminalExits).hasValue(1);
    }

    /**
     * The studio engineer's stage-6 S1: a loss known only from the lease's
     * status — the lock replaced and a heartbeat that saw it, no head failure
     * — reads no lock at GUARD: exactly one takeover dialog, whose header
     * names no holder and claims no removal; no unsaved-changes prompt.
     */
    @Test
    void aLossKnownOnlyFromTheLeasesStatusShowsTheExitDialogWithTheLeaseLostHeaderAndReadsNoLock()
            throws Exception {
        steal();
        assertThat(fixture.save.projectManager.getLockManager().heartbeat()).as("fixture: the heartbeat saw it")
                .isFalse();
        assertThat(onFx(() -> fixture.lifecycle().closeReadiness().headFailure())).as("fixture: no head failure")
                .isEmpty();
        fixture.save.takeoverAnswer = "Cancel";

        fixture.closeBox();
        fixture.await(() -> fixture.save.takeoverHeaders.size() == 1 && fixture.steps.size() == 2, "the dialog");

        assertThat(fixture.save.takeoverHeaders).containsExactly("The lock of “Song A” is no longer held by this"
                + " session, so another session may have taken the project over.");
        assertThat(fixture.save.takeoverContents).containsExactly(EXIT_CONTENT);
        assertThat(fixture.unsavedPrompts).as("no Unsaved Changes prompt").isEmpty();
        assertThat(fixture.stepNames()).containsExactly("INTERCEPT DONE", "GUARD CANCELLED");
    }

    /**
     * Ruling 4 Q1 (c), the exit: Quit without saving under a stolen lock,
     * with one of Song A's checkpoint writes claimed before the steal and
     * held — the tail waits under the surface's clock (the stage-6 reviews'
     * B1/B2): "Writing checkpoint…", and at ten seconds
     * {@code close.note.takenOver} with Quit anyway.
     */
    @Test
    void quitWithoutSavingBehindAHeldCheckpointWriteWaitsUnderTheSurfaceWithTheTakenOverNote() throws Exception {
        SaveFlowFixture.Publishes.Hold claimed = fixture.save.publishes.holdNext(
                target -> SaveFlowFixture.isCheckpoint(target) && target.startsWith(songA));
        runOnFx(() -> fixture.save.snapshots().createCheckpointWithLabel("Claimed before the steal"));
        claimed.awaitHeld();
        try {
            steal();
            fixture.save.promptAnswer = "Save and close";
            fixture.save.takeoverAnswer = "Quit without saving";

            fixture.closeBox();
            fixture.await(() -> fixture.stepNames().contains("FLUSH SKIPPED")
                    && !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(), "the tail's wait clock");
            fixture.fire(ExitCoordinator.SURFACE_DELAY);
            ClosingProgressDialog surface = fixture.surface();
            assertThat(onFx(surface::stepText)).isEqualTo("Writing checkpoint…");
            fixture.fire(ExitCoordinator.NOTE_DELAY);
            assertThat(onFx(surface::noteText)).isEqualTo(TAKEN_OVER_NOTE);
            assertThat(onFx(() -> surface.offerButton().getText())).isEqualTo("Quit anyway");
        } finally {
            claimed.release();
        }
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
    }

    /**
     * The studio engineer's stage-6 N2: Quit anyway before the decision —
     * GUARD still waiting on a save queued before the steal — with the loss
     * already known from the lease's status drains the journal without a
     * manifest: nothing is folded into the session of a folder another
     * session holds (R7, D9).
     */
    @Test
    void quitAnywayBeforeTheDecisionUnderAKnownLossDrainsWithoutAManifest() throws Exception {
        SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.save.save();
        head.awaitHeld();
        steal();
        assertThat(fixture.save.projectManager.getLockManager().heartbeat()).as("fixture: the loss is known")
                .isFalse();
        Map<String, String> manifests = sessionManifestsOf(songA);
        assertThat(manifests).as("fixture: Song A's session is open").isNotEmpty();
        try {
            fixture.closeBox();
            fixture.await(() -> !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(), "the wait clock");
            fixture.fire(ExitCoordinator.SURFACE_DELAY);
            fixture.fire(ExitCoordinator.NOTE_DELAY);
            runOnFx(() -> fixture.surface().offerButton().fire());
            fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
            fixture.awaitTheJournalEnded();
        } finally {
            head.release();
        }
        fixture.save.settle();

        assertThat(sessionManifestsOf(songA)).as("no manifest written into a folder another session holds")
                .containsExactlyInAnyOrderEntriesOf(manifests);
        assertThat(JournalState.records(ProjectContext.forProject(songA))).noneMatch(JournalRecord::isSessionSeal);
    }

    /** The bytes of each file under {@code folder}'s {@code sessions/}, in hex, by its relative path. */
    private static Map<String, String> sessionManifestsOf(Path folder) throws Exception {
        Map<String, String> manifests = new TreeMap<>();
        Path sessions = folder.resolve("sessions");
        if (!Files.isDirectory(sessions)) {
            return manifests;
        }
        try (Stream<Path> walk = Files.walk(sessions)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                manifests.put(sessions.relativize(file).toString(),
                        java.util.HexFormat.of().formatHex(Files.readAllBytes(file)));
            }
        }
        return manifests;
    }

    /**
     * Ruling 4 Q1 (c), the exit, at the journal: its drain — submitted, not
     * yet begun — waited for with "Closing journal…" and the same note; and
     * a keep after the detach says only that journaling is paused,
     * {@code close.cancelled.sealed.exit}: no recovery evidence is promised
     * in a folder another session holds (refinement 2).
     */
    @Test
    void aKeepAfterTheDetachUnderAStolenLockSaysOnlyThatJournalingIsPaused() throws Exception {
        List<Runnable> notBegun = new java.util.concurrent.CopyOnWriteArrayList<>();
        fixture.save.sealWorker = notBegun::add;
        steal();
        fixture.save.promptAnswer = "Save and close";
        fixture.save.takeoverAnswer = "Quit without saving";

        fixture.closeBox();
        fixture.await(() -> notBegun.size() == 1, "the drain submitted, not begun");
        fixture.fire(ExitCoordinator.SURFACE_DELAY);
        ClosingProgressDialog surface = fixture.surface();
        assertThat(onFx(surface::stepText)).isEqualTo("Closing journal…");
        fixture.fire(ExitCoordinator.NOTE_DELAY);
        assertThat(onFx(surface::noteText)).isEqualTo(TAKEN_OVER_NOTE);

        runOnFx(() -> surface.keepButton().fire());

        assertThat(fixture.save.notifications()).extracting(NotificationEntry::message).containsExactly(
                "Exit cancelled. Journaled persistence is paused until this project is reopened.");
        assertThat(fixture.terminalExits).hasValue(0);
        notBegun.forEach(Runnable::run);
        fixture.awaitTheJournalEnded();
    }

    /**
     * Ruling 4 Q1 (c), Close Project through Close without saving: the
     * project variant of the note, no offer, shown only once Keep project
     * open can be pressed again — ten seconds after the detach (the studio
     * engineer's stage-6 S3).
     */
    @Test
    void closeWithoutSavingShowsTheTakenOverProjectNoteOnlyOnceKeepProjectOpenIsEnabled() throws Exception {
        List<Runnable> notBegun = new java.util.concurrent.CopyOnWriteArrayList<>();
        fixture.save.sealWorker = notBegun::add;
        steal();
        fixture.save.promptAnswer = "Save and close";
        fixture.save.takeoverAnswer = "Close without saving";

        fixture.closeProject();
        fixture.await(() -> notBegun.size() == 1, "the drain submitted, not begun");
        fixture.fire(ExitCoordinator.SURFACE_DELAY);
        ClosingProgressDialog surface = fixture.surface();
        runOnFx(() -> fixture.timers.fire(fixture.timers.pending(ExitCoordinator.NOTE_DELAY).getFirst()));
        assertThat(onFx(surface::noteText)).as("the wait clock's ten seconds alone: the keep is disabled").isEmpty();

        fixture.fire(ExitCoordinator.KEEP_AFTER_DETACH_DELAY);
        assertThat(onFx(surface::noteText)).isEqualTo("Another session holds “Song A”, and this session is still"
                + " finishing its last writes in the project's folder after 10 seconds. The disk may not be"
                + " responding. The project closes once they have finished; choose Keep project open to keep"
                + " working.");
        assertThat(onFx(() -> surface.offerButton().isVisible())).as("Close Project has no close-anyway").isFalse();
        assertThat(onFx(() -> surface.keepButton().isDisabled())).isFalse();
        notBegun.forEach(Runnable::run);
        fixture.await(() -> fixture.welcomes.get() == 1, "Close Project ended");
    }

    @Test
    void closeProjectDuringAStolenQueuedSaveOffersCloseWithoutSavingAndClosesWithNoWrite() throws Exception {
        exitDuringAStolenQueuedSave(() -> fixture.closeProject(), "Close without saving");
        fixture.await(() -> fixture.save.project.get().getName().equals("Untitled Project"), "Untitled");

        assertThat(fixture.save.takeoverButtons).containsExactly(List.of("Save As…", "Close without saving",
                "Cancel"));
        assertThat(fixture.unsavedPrompts).isEmpty();
        assertThat(JournalState.records(ProjectContext.forProject(songA))).noneMatch(JournalRecord::isSessionSeal);
    }

    @Test
    void dontQuitBeforeTheQueuedSaveReturnsLeavesTheSaveItsOwnCtrlSDialog() throws Exception {
        SaveFlowFixture.Publishes.Hold first = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.save.save();
        first.awaitHeld();
        fixture.edit("Keys");
        steal();
        fixture.save.save();
        fixture.closeBox();
        fixture.await(() -> !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(), "the wait clock");
        fixture.fire(ExitCoordinator.SURFACE_DELAY);
        runOnFx(() -> fixture.surface().keepButton().fire());
        first.release();
        fixture.save.settle();
        fixture.await(() -> fixture.save.takeoverHeaders.size() == 1, "the save's own dialog");

        assertThat(fixture.save.takeoverContents).as("Don't quit first: the Ctrl+S variant").containsExactly(
                "Nothing was written to its folder, and your changes are still open here. Save them as a new project"
                        + " to keep them; the original folder is left to the other session.");
        assertThat(fixture.save.takeoverButtons).containsExactly(List.of("Save As…", "Not now"));
    }

    /** The studio engineer's stage-4 S8: Ctrl+S, Not now, then the close box — exactly one dialog. */
    @Test
    void aLossAlreadyKnownShowsTheExitDialogInPlaceOfTheUnsavedChangesPrompt() throws Exception {
        steal();
        fixture.save.save();
        fixture.save.settle();
        fixture.await(() -> fixture.save.takeoverHeaders.size() == 1, "Ctrl+S's dialog");
        fixture.save.takeoverAnswer = "Cancel";

        fixture.closeBox();
        fixture.await(() -> fixture.save.takeoverHeaders.size() == 2 && fixture.steps.size() == 2, "the exit's");

        assertThat(fixture.save.takeoverContents).as("Ctrl+S's, then the exit's: one dialog for the exit")
                .containsExactly("Nothing was written to its folder, and your changes are still open here. Save"
                        + " them as a new project to keep them; the original folder is left to the other session.",
                        EXIT_CONTENT);
        assertThat(fixture.save.takeoverHeaders.getLast()).isEqualTo(HOLDER);
        assertThat(fixture.unsavedPrompts).as("no Unsaved Changes prompt").isEmpty();
        assertThat(fixture.stepNames()).containsExactly("INTERCEPT DONE", "GUARD CANCELLED");
    }

    /**
     * Ruling 3 Q1: a copy Save As adopted under a lock taken over while the
     * original closed — the close box with no edits exits without a dialog
     * and leaves the copy unchanged; after an edit it shows R7's exit
     * variant.
     */
    @Test
    void aCopyAdoptedUnderALostLockExitsWithNoWriteAndAnEditAsksTheExitDialog() throws Exception {
        Path target = workspace.resolve("Song A copy");
        SaveFlowFixture.Publishes.Hold closing = fixture.save.publishes.holdNext(
                path -> SaveFlowFixture.isCheckpoint(path) && path.startsWith(songA));
        fixture.save.saveAsTargets.choose(target);
        CompletionStage<SaveAsFlow.SaveAsResult> saveAs = onFx(() -> fixture.save.saveAsFlow()
                .start(SaveAsFlow.Prefill.COPY));
        closing.awaitHeld();
        new ProjectLockManager(InstantSource.system(), "alice", "Studio-PC", 42L).forceAcquire(target);
        closing.release();
        assertThat(saveAs.toCompletableFuture().get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .isEqualTo(new SaveAsFlow.Published(target));
        fixture.save.settle();
        assertThat(fixture.save.currentProjectIs(target)).isTrue();
        String foreignLock = Files.readString(target.resolve(ProjectLockManager.LOCK_FILE_NAME));
        fixture.edit("After the copy");
        fixture.save.takeoverAnswer = "Cancel";

        fixture.closeBox();
        fixture.await(() -> fixture.save.takeoverHeaders.size() == 1 && fixture.steps.size() == 2, "the dialog");
        assertThat(fixture.save.takeoverHeaders).containsExactly(
                "“Song A copy” was taken over by alice on Studio-PC (process 42).");
        assertThat(fixture.save.takeoverContents).containsExactly(EXIT_CONTENT);
        assertThat(fixture.unsavedPrompts).isEmpty();

        runOnFx(() -> fixture.save.project.get().markClean());
        Map<String, FileTime> copy = listing(target);
        fixture.steps.clear();
        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
        assertThat(fixture.save.takeoverHeaders).as("no edits: no dialog").hasSize(1);
        fixture.save.projectManager.whenRetiredLeasesReleased().get(ExitFixture.GUARD.toMillis(),
                TimeUnit.MILLISECONDS);
        assertThat(listing(target)).as("the copy is left unchanged").isEqualTo(copy);
        assertThat(Files.readString(target.resolve(ProjectLockManager.LOCK_FILE_NAME))).isEqualTo(foreignLock);
    }

    /**
     * The steal, a Ctrl+S held at its head write, a second Ctrl+S queued
     * behind it, then {@code door}; the held save is released, and the
     * queued one meets the steal. Answers the exit's dialog with
     * {@code answer}.
     */
    private CompletionStage<ExitCoordinator.CloseOutcome> exitDuringAStolenQueuedSave(
            ThrowingSupplier<CompletionStage<ExitCoordinator.CloseOutcome>> door, String answer) throws Exception {
        SaveFlowFixture.Publishes.Hold first = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.save.save();
        first.awaitHeld();
        fixture.edit("Keys");
        steal();
        fixture.save.save();
        fixture.save.takeoverAnswer = answer;
        CompletionStage<ExitCoordinator.CloseOutcome> outcome = door.get();
        fixture.await(() -> !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(), "the wait clock");
        first.release();
        fixture.await(() -> fixture.save.takeoverHeaders.size() == 1 && fixture.steps.size() >= 2,
                "the exit's dialog");
        fixture.save.settle();
        return outcome;
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private void steal() throws Exception {
        new ProjectLockManager(InstantSource.system(), "alice", "Studio-PC", 42L).forceAcquire(songA);
    }

    private void assertTheOriginalWasLeftToTheOtherSession(String foreignLock, byte[] head,
                                                          Map<String, FileTime> original, int checkpoints)
            throws Exception {
        fixture.save.projectManager.whenRetiredLeasesReleased().get(ExitFixture.GUARD.toMillis(),
                TimeUnit.MILLISECONDS);
        assertThat(withoutThisSessionsSegment(listing(songA)))
                .as("nothing written to or deleted from the original but this session's own journal segment"
                        + " (ruling R7's interim drain, R12 #7)").isEqualTo(original);
        assertThat(JournalState.records(ProjectContext.forProject(songA))).as("no seal: never certified")
                .noneMatch(JournalRecord::isSessionSeal);
        assertThat(SaveFlowFixture.checkpointFiles(songA)).as("no closing checkpoint").hasSize(checkpoints);
        assertThat(Files.readAllBytes(songA.resolve("project.daw"))).as("no head").isEqualTo(head);
        assertThat(Files.readString(songA.resolve(ProjectLockManager.LOCK_FILE_NAME)))
                .as("the other session's lock untouched").isEqualTo(foreignLock);
        assertThat(fixture.terminalExits).hasValue(1);
    }

    /**
     * {@code entries} but this session's own journal segment — the newest
     * {@code journal/segment-*}, the one its drain appends to (ruling R7's
     * interim, R12 #7) — and nothing else of {@code journal/}: a manifest, or
     * another segment, changed there still fails the listing (the stage-6
     * review N6).
     */
    private static Map<String, FileTime> withoutThisSessionsSegment(Map<String, FileTime> entries) {
        String segments = "journal" + java.io.File.separator + "segment-";
        entries.keySet().stream().filter(relative -> relative.startsWith(segments)).reduce((first, last) -> last)
                .ifPresent(entries::remove);
        return entries;
    }

    private static Map<String, FileTime> listing(Path folder) throws Exception {
        Map<String, FileTime> entries = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path entry : walk.toList()) {
                entries.put(folder.relativize(entry).toString(), Files.getLastModifiedTime(entry));
            }
        }
        return entries;
    }
}
