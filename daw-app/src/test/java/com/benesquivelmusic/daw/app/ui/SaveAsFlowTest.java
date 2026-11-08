package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.JournalEnding.JournalEnd;
import com.benesquivelmusic.daw.app.ui.status.ProjectJournalCoordinator;
import com.benesquivelmusic.daw.app.ui.vm.ProjectVM;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.LockConflictResolution;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;
import com.benesquivelmusic.daw.core.persistence.SaveAsPlan;
import com.benesquivelmusic.daw.core.persistence.journal.JournalRecord;
import com.benesquivelmusic.daw.core.persistence.journal.JournalState;
import com.benesquivelmusic.daw.core.persistence.journal.ProjectContext;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.session.SessionManager;
import com.benesquivelmusic.daw.core.session.WorkingSession;
import com.benesquivelmusic.daw.core.snapshot.SnapshotEntry;
import com.benesquivelmusic.daw.core.snapshot.SnapshotKind;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import javafx.stage.Modality;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.GUARD;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, File ▸ Save As… (the studio engineer's rulings R5, R6, S5, S6,
 * Q1, Q2, Q6, ruling 2 OQ-1 and ruling 3 Q1; research "Session management:
 * Robust project save/load with undo/redo history"): over the real save
 * flow — a real project manager, checkpoints and atomic writer, the
 * controller's real pending switch, the real progress dialog — the open
 * project is copied into a new folder, which becomes the open project in
 * the same window: the same live model, its undo history going on, renamed
 * after its folder, clean, re-pointed at the copy's files; the original
 * folder is closed as Discard and close closes it, its {@code project.daw}
 * untouched and its lock released once its work ended. The refusals, the
 * cancels, the failures and the ruled texts; Continue when the original's
 * close hangs, which opens the copy with no journal; a copy whose lock is
 * lost between its check and its adoption, which opens under the lost lock.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class SaveAsFlowTest {

    /** The take Song A uses: 2 KiB, so the copy's sizes read "2 KB". */
    private static final int TAKE_BYTES = 2048;

    @TempDir
    Path workspace;

    private SaveFlowFixture fixture;
    private Path songA;
    private Path target;
    private DefaultEventBus bus;
    private ProjectJournalCoordinator journal;
    private SessionManager sessions;
    private java.util.prefs.Preferences preferences;

    @BeforeEach
    void songAWithATakeSavedThenEdited() throws Exception {
        preferences = java.util.prefs.Preferences.userRoot().node("save-as-flow-test-" + System.nanoTime());
        fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT,
                new com.benesquivelmusic.daw.core.persistence.RecentProjectsStore(preferences));
        songA = fixture.songA;
        target = workspace.resolve("Song A copy");
        Files.write(songA.resolve("audio").resolve("take.wav"), new byte[TAKE_BYTES]);
        runOnFx(() -> fixture.project.get().getTracks().getFirst()
                .addClip(new AudioClip("take", 0, 1, songA.resolve("audio").resolve("take.wav").toString())));
        fixture.save();
        fixture.settle();
        runOnFx(() -> fixture.project.get().markDirty());
    }

    @AfterEach
    void close() throws Exception {
        try {
            if (journal != null) {
                journal.drainWithoutSeal(null);
            }
            fixture.close();
        } finally {
            if (bus != null) {
                bus.close();
            }
            preferences.removeNode();
        }
    }

    @Test
    void theCopyBecomesTheOpenProjectInThisWindowWithItsUndoHistoryAndTheOriginalIsClosedAsDiscardClosesIt()
            throws Exception {
        byte[] originalHead = Files.readAllBytes(songA.resolve("project.daw"));
        int originalCheckpoints = SaveFlowFixture.checkpointFiles(songA).size();
        UndoManager undo = fixture.undoManager.get();
        DawProject live = fixture.project.get();
        fixture.saveAsTargets.choose(target);

        runOnFx(() -> fixture.lifecycle().onSaveProjectAs());
        fixture.awaitOnFx(() -> fixture.currentProjectIs(target) && fixture.lifecycle().closeReadiness().pending()
                instanceof ProjectLifecycleController.PendingSwitch.None, "the copy open and the Save As ended");

        assertThat(fixture.saveAsTargets.calls()).as("ruling R6: the original's parent, \"<folder> copy\"")
                .containsExactly(new SaveFlowFixture.SaveAsTargets.Call(workspace, "Song A copy"));
        assertThat(fixture.project.get()).as("the same live model: nothing reloaded").isSameAs(live);
        assertThat(fixture.undoManager.get()).as("its undo history goes on").isSameAs(undo);
        assertThat(onFx(live::getName)).as("named after the copy's folder (R6)").isEqualTo("Song A copy");
        assertThat(onFx(() -> live.getMetadata().projectPath())).isEqualTo(target);
        assertThat(onFx(live::isDirty)).as("the Save As is the save of those changes").isFalse();
        assertThat(onFx(() -> live.getTracks().getFirst().getClips().getFirst().getSourceFilePath()))
                .as("re-pointed at the copy's file").isEqualTo(target.resolve("audio").resolve("take.wav").toString());
        assertThat(fixture.projectManager.getLockManager().currentLock()).as("the copy's own lock").isPresent();
        assertThat(ProjectLockManager.peekLock(target)).contains(fixture.projectManager.getLockManager()
                .currentLock().orElseThrow());
        assertThat(Files.readAllBytes(target.resolve("audio").resolve("take.wav"))).hasSize(TAKE_BYTES);
        assertThat(Files.readString(target.resolve("project.daw"), StandardCharsets.UTF_8))
                .as("self-contained: no reference into the original").doesNotContain(songA.toString());
        assertThat(fixture.notifications()).extracting(NotificationEntry::level, NotificationEntry::message)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(NotificationLevel.INFO,
                        "Saved as “Song A copy” in " + workspace + "."));

        assertThat(Files.readAllBytes(songA.resolve("project.daw")))
                .as("the original's project.daw stays at its last save").isEqualTo(originalHead);
        assertThat(SaveFlowFixture.checkpointFiles(songA)).as("its closing checkpoint, trigger (ii): the edit")
                .hasSize(originalCheckpoints + 1);
        fixture.projectManager.whenRetiredLeasesReleased().get(GUARD.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).as("released once its work ended")
                .doesNotExist();
        assertThat(onFx(() -> fixture.saveAsFlow().progressDialog())).as("the progress dialog closed").isNull();
        assertThat(fixture.projectManager.getRecentProjectPaths()).as("stage-5 review S5: the copy in Recent Projects")
                .contains(target);
    }

    /**
     * Two Save As copies in a row, A to B, then B to C (PR #989 review
     * 5704604621): the second copy's adoption re-points the live references
     * at the files the newest copy took from B — B's own new file included,
     * which A never had — not at the files an older folder's held set names,
     * so every clip names C's file and C's {@code project.daw} names neither
     * earlier folder.
     */
    @Test
    void aSecondSaveAsRepointsOnlyTheFilesTheNewestCopyTook() throws Exception {
        DawProject live = fixture.project.get();
        fixture.saveAsTargets.choose(target);
        runOnFx(() -> fixture.lifecycle().onSaveProjectAs());
        awaitTheSaveAsEndedWithTheCopyOpenIn(target);
        fixture.projectManager.whenRetiredLeasesReleased().get(GUARD.toMillis(), TimeUnit.MILLISECONDS);
        Path secondTake = target.resolve("audio").resolve("second.wav");
        Files.write(secondTake, new byte[TAKE_BYTES]);
        runOnFx(() -> {
            live.getTracks().getFirst().addClip(new AudioClip("second", 1, 1, secondTake.toString()));
            live.markDirty();
        });
        Path songC = workspace.resolve("Song C");
        fixture.saveAsTargets.choose(songC);

        runOnFx(() -> fixture.lifecycle().onSaveProjectAs());
        awaitTheSaveAsEndedWithTheCopyOpenIn(songC);

        assertThat(onFx(() -> live.getTracks().getFirst().getClips().stream().map(AudioClip::getSourceFilePath)
                .toList())).as("every clip re-pointed at the newest copy's file, B's own new take included")
                .containsExactlyInAnyOrder(songC.resolve("audio").resolve("take.wav").toString(),
                        songC.resolve("audio").resolve("second.wav").toString());
        assertThat(Files.readAllBytes(songC.resolve("audio").resolve("second.wav"))).hasSize(TAKE_BYTES);
        assertThat(Files.readString(songC.resolve("project.daw"), StandardCharsets.UTF_8))
                .as("self-contained: no reference into either earlier folder")
                .doesNotContain(songA.toString()).doesNotContain(target.toString());
    }

    /** Waits, bounded, until the copy in {@code folder} is the open project and the Save As ended. */
    private void awaitTheSaveAsEndedWithTheCopyOpenIn(Path folder) throws Exception {
        fixture.awaitOnFx(() -> fixture.currentProjectIs(folder) && fixture.lifecycle().closeReadiness().pending()
                instanceof ProjectLifecycleController.PendingSwitch.None, "the copy open and the Save As ended");
    }

    @Test
    void theRulingsBoundsAreTenSecondsToContinueAndTwoSecondsForThePrefill() {
        assertThat(SaveAsFlow.ORIGINAL_STILL_CLOSING_DELAY).as("ruling S6: \"after 10 seconds\"")
                .isEqualTo(Duration.ofSeconds(10));
        assertThat(SaveAsFlow.PREFILL_BOUND).as("ruling R6: \"within about 2 s\"").isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void theStepLinesSayTheOriginalIsBeingClosedThenTheCopyOpenedWithNoCancel() throws Exception {
        SaveFlowFixture.Publishes.Hold closing = fixture.publishes.holdNext(
                path -> SaveFlowFixture.isCheckpoint(path) && path.startsWith(songA));
        HoldingTheAdoption worker = new HoldingTheAdoption();
        runOnFx(() -> fixture.saveAsFlow().setWorker(worker));
        fixture.saveAsTargets.choose(target);
        CompletionStage<SaveAsFlow.SaveAsResult> saveAs = start(SaveAsFlow.Prefill.COPY);

        closing.awaitHeld();
        fixture.pulse();
        SaveAsProgressDialog dialog = onFx(() -> fixture.saveAsFlow().progressDialog());
        assertThat(onFx(dialog::statusText)).as("ENG-S5: the head's keyed line never lands over it")
                .isEqualTo("Closing “Song A”…");
        assertThat(onFx(dialog::progress)).isEqualTo(1.0);
        assertThat(onFx(() -> dialog.cancelButton().isDisabled())).isTrue();
        closing.release();

        worker.awaitTheAdoption();
        fixture.pulse();
        assertThat(onFx(dialog::statusText)).isEqualTo("Opening “Song A copy”…");
        assertThat(onFx(() -> dialog.cancelButton().isDisabled())).isTrue();
        worker.releaseTheAdoption();

        assertThat(await(saveAs)).isEqualTo(new SaveAsFlow.Published(target));
    }

    @Test
    void aSecondSaveAsWhileACancelledCopyStillDeletesWhatItMadeIsRefusedAsStillFinishing() throws Exception {
        SaveFlowFixture.Publishes.Hold take = fixture.publishes.holdNext(path -> isInTheStaging(path, "take.wav"));
        fixture.saveAsTargets.choose(target);
        CompletionStage<SaveAsFlow.SaveAsResult> first = start(SaveAsFlow.Prefill.COPY);
        take.awaitHeld();
        runOnFx(() -> fixture.saveAsFlow().progressDialog().cancelButton().fire());

        assertThat(await(start(SaveAsFlow.Prefill.COPY))).as("the cancelled copy is still the switch pending")
                .isEqualTo(new SaveAsFlow.Refused());
        assertThat(fixture.notifications()).extracting(NotificationEntry::level, NotificationEntry::message)
                .as("ENG-S6: true while it copies and while a cancel cleans up").containsExactly(
                        org.assertj.core.groups.Tuple.tuple(NotificationLevel.INFO,
                                "Still finishing Save As — try again when it has finished."));
        take.release();

        assertThat(await(first)).isEqualTo(new SaveAsFlow.Cancelled());
        assertThat(target).doesNotExist();
    }

    @Test
    void aFailingSealOfTheOriginalIsLoggedAndTheCopyStillOpens() throws Exception {
        withTheJournal(this::sealThatThrows);
        List<java.util.logging.LogRecord> warnings = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.logging.Logger closerLog = java.util.logging.Logger.getLogger(ProjectFolderCloser.class.getName());
        java.util.logging.Handler recording = recordingWarnings(warnings);
        closerLog.addHandler(recording);
        fixture.saveAsTargets.choose(target);
        try {
            assertThat(await(start(SaveAsFlow.Prefill.COPY))).isEqualTo(new SaveAsFlow.Published(target));
        } finally {
            closerLog.removeHandler(recording);
        }

        assertThat(fixture.currentProjectIs(target)).as("design §4.2 R5, COV-S-13: the switch completes").isTrue();
        assertThat(fixture.projectManager.getLockManager().currentLock()).isPresent();
        assertThat(warnings).extracting(java.util.logging.LogRecord::getMessage)
                .anySatisfy(message -> assertThat(message).startsWith("The journal could not be ended"));
        assertThat(fixture.notifications()).extracting(NotificationEntry::level, NotificationEntry::message)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(NotificationLevel.INFO,
                        "Saved as “Song A copy” in " + workspace + "."));
        fixture.projectManager.whenRetiredLeasesReleased().get(GUARD.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).as("the original still released").doesNotExist();
    }

    /**
     * Story 333, the studio engineer's ruling on app fix pass 2a, item 5: the
     * original's close fails before its release began — a step of the closer
     * itself throws, here its seal's preparation — so the close answers
     * exceptionally and the original is still the open project: the copy is not
     * adopted over it. The original stays open, its lock held and its journal
     * drained unsealed; the copy stays published, is remembered in Recent
     * Projects and its lock is released; {@code saveAs.notOpened.paused} says
     * so, with the journaling paused — the throw came after the journal's
     * detach (the studio engineer's stage-8 S6).
     */
    @Test
    void anOriginalWhoseCloseFailsBeforeItsReleaseStaysOpenAndTheCopyIsRememberedNotOpened() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean throwOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        withTheJournal(real -> new JournalEnding() {
            @Override
            public void detach() {
                real.detach();
            }

            @Override
            public Runnable prepare(JournalEnd mode) {
                if (mode == JournalEnd.SEAL && throwOnce.compareAndSet(true, false)) {
                    throw new IllegalStateException("injected");
                }
                return real.prepare(mode);
            }
        });
        fixture.saveAsTargets.choose(target);

        assertThat(await(start(SaveAsFlow.Prefill.COPY))).isInstanceOf(SaveAsFlow.Failed.class);

        assertThat(fixture.notifications()).extracting(NotificationEntry::level, NotificationEntry::message)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(NotificationLevel.ERROR,
                        "Save As wrote “Song A copy” in " + workspace + " but could not open it: injected. “Song A”"
                                + " stays open, and journaled persistence is paused until this project is reopened;"
                                + " you can open the copy from Recent Projects."));
        assertThat(fixture.currentProjectIs(songA)).as("the original is still the open project").isTrue();
        fixture.projectManager.getLockManager().verifyHeld();
        assertThat(onFx(() -> fixture.project.get().getName())).isEqualTo("Song A");
        assertThat(target.resolve("project.daw")).as("the copy stays on disk").exists();
        fixture.awaitOnFx(() -> fixture.projectManager.getRecentProjectPaths().contains(target)
                && !Files.exists(target.resolve(ProjectLockManager.LOCK_FILE_NAME)),
                "the copy remembered and its lease released, off the FX thread");
        fixture.awaitOnFx(() -> !journal.isOpen(), "the original's journal drained");
        journal.drainWithoutSealFor(songA, null);
        assertThat(JournalState.records(ProjectContext.forProject(songA))).as("drained, never sealed")
                .noneMatch(JournalRecord::isSessionSeal);
    }

    /**
     * The studio engineer's stage-8 S6, ruling 4 Q3's P1 for Save As's
     * original: its close fails before the journal's detach — the detach
     * itself throws, once, before the real one — so the original's journal
     * is kept as it is, open and never drained, and {@code saveAs.notOpened}
     * says the copy was written but not opened, with no word of paused
     * journaling.
     */
    @Test
    void anOriginalWhoseCloseFailsBeforeItsDetachKeepsItsJournalOpen() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean throwOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicInteger prepared = new java.util.concurrent.atomic.AtomicInteger();
        withTheJournal(real -> new JournalEnding() {
            @Override
            public void detach() {
                if (throwOnce.compareAndSet(true, false)) {
                    throw new IllegalStateException("injected");
                }
                real.detach();
            }

            @Override
            public Runnable prepare(JournalEnd mode) {
                prepared.incrementAndGet();
                return real.prepare(mode);
            }
        });
        fixture.saveAsTargets.choose(target);

        assertThat(await(start(SaveAsFlow.Prefill.COPY))).isInstanceOf(SaveAsFlow.Failed.class);

        assertThat(fixture.notifications()).extracting(NotificationEntry::level, NotificationEntry::message)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(NotificationLevel.ERROR,
                        "Save As wrote “Song A copy” in " + workspace + " but could not open it: injected. “Song A”"
                                + " stays open; you can open the copy from Recent Projects."));
        assertThat(fixture.currentProjectIs(songA)).as("the original is still the open project").isTrue();
        fixture.awaitOnFx(() -> fixture.projectManager.getRecentProjectPaths().contains(target)
                && !Files.exists(target.resolve(ProjectLockManager.LOCK_FILE_NAME)),
                "the copy remembered and its lease released, off the FX thread");
        assertThat(onFx(journal::isOpen)).as("the original's journal is kept as it is").isTrue();
        assertThat(prepared).as("no drain was prepared").hasValue(0);
    }

    @Test
    void aProjectNeverSavedIsSavedAsTheFolderTypedAndOpensThere() throws Exception {
        fixture.projectManager.closeProject().get(GUARD.toMillis(), TimeUnit.MILLISECONDS);
        runOnFx(() -> {
            DawProject fresh = new DawProject("Untitled Project",
                    com.benesquivelmusic.daw.core.audio.AudioFormat.CD_QUALITY);
            fresh.createAudioTrack("Idea");
            fresh.markDirty();
            fixture.project.set(fresh);
        });
        Path typed = workspace.resolve("First Idea");
        fixture.saveAsTargets.choose(typed);

        assertThat(await(start(SaveAsFlow.Prefill.COPY))).isEqualTo(new SaveAsFlow.Published(typed));

        assertThat(fixture.currentProjectIs(typed)).as("stage-5 review N12: the no-folder plan, adopted").isTrue();
        assertThat(fixture.projectManager.getLockManager().currentLock()).as("under its own lock").isPresent();
        assertThat(ProjectLockManager.peekLock(typed)).isPresent();
        assertThat(onFx(() -> fixture.project.get().getName())).isEqualTo("First Idea");
        assertThat(onFx(() -> fixture.project.get().isDirty())).isFalse();
        assertThat(Files.readString(typed.resolve("project.daw"), StandardCharsets.UTF_8)).contains("Idea");
        assertThat(fixture.notifications()).extracting(NotificationEntry::message)
                .containsExactly("Saved as “First Idea” in " + workspace + ".");
    }

    @Test
    void aReadOnlyOriginalIsLeftExactlyAsItWasEvenWithASessionKeptForIt() throws Exception {
        fixture.projectManager.closeProject().get(GUARD.toMillis(), TimeUnit.MILLISECONDS);
        new ProjectLockManager(InstantSource.system(), "bob", "Mix-Room", 7L).forceAcquire(songA);
        fixture.projectManager.setLockConflictHandler((lock, stale) -> LockConflictResolution.OPEN_READ_ONLY);
        fixture.projectManager.openProject(songA);
        assertThat(fixture.projectManager.isReadOnly()).as("fixture: Song A is open read-only").isTrue();
        // A session kept for the read-only folder, as the composition root never keeps one: the close writes no
        // manifest into it all the same (stage-5 review N3 / ENG-N4: nothing is written by construction).
        bus = new DefaultEventBus();
        sessions = new SessionManager();
        journal = new ProjectJournalCoordinator(fixture.progress, onFx(NotificationBar::new), fixture.dispatcher,
                () -> true, () -> bus, fixture.checkpoints, sessions, InstantSource.system());
        WorkingSession keptForA = sessions.openSession(songA, Instant.now()).current();
        JournalEnding ending = new SessionJournalEnding(journal, sessions, () -> keptForA, () -> songA, () -> null,
                fixture::openFolder, InstantSource.system());
        runOnFx(() -> fixture.installSaveAs(journal, ending));
        String bobsLock = Files.readString(songA.resolve(ProjectLockManager.LOCK_FILE_NAME));
        java.util.Map<String, String> original = listing(songA);
        fixture.saveAsTargets.choose(target);

        assertThat(await(start(SaveAsFlow.Prefill.COPY))).isEqualTo(new SaveAsFlow.Published(target));

        assertThat(fixture.currentProjectIs(target)).isTrue();
        fixture.projectManager.whenRetiredLeasesReleased().get(GUARD.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(listing(songA)).as("R5: nothing written to or deleted from a read-only folder").isEqualTo(original);
        assertThat(Files.readString(songA.resolve(ProjectLockManager.LOCK_FILE_NAME)))
                .as("the other session's lock untouched").isEqualTo(bobsLock);
    }

    @Test
    void theProgressDialogIsModalAndShowsTheCopyInTheDiskCellsUnitsThenTheHeadWithNoCancel() throws Exception {
        SaveFlowFixture.Publishes.Hold take = fixture.publishes.holdNext(path -> isInTheStaging(path, "take.wav"));
        SaveFlowFixture.Publishes.Hold head = fixture.publishes.holdNext(path -> isInTheStaging(path, "project.daw"));
        fixture.saveAsTargets.choose(target);
        CompletionStage<SaveAsFlow.SaveAsResult> saveAs = start(SaveAsFlow.Prefill.COPY);

        take.awaitHeld();
        fixture.pulse();
        SaveAsProgressDialog dialog = onFx(() -> fixture.saveAsFlow().progressDialog());
        assertThat(onFx(dialog::getTitle)).isEqualTo("Save Project As");
        assertThat(onFx(dialog::getModality)).isEqualTo(Modality.APPLICATION_MODAL);
        assertThat(onFx(dialog::statusText)).isEqualTo("Copying audio to “Song A copy”… 0 KB of 2 KB");
        assertThat(onFx(() -> dialog.cancelButton().isDisabled())).isFalse();
        assertThat(onFx(() -> dialog.continueButton().isVisible())).isFalse();
        take.release();

        head.awaitHeld();
        fixture.pulse();
        assertThat(onFx(dialog::statusText)).isEqualTo("Writing project.daw…");
        assertThat(onFx(dialog::progress)).isEqualTo(1.0);
        assertThat(onFx(() -> dialog.cancelButton().isDisabled())).as("SKL-S8: no Cancel from the head on").isTrue();
        runOnFx(() -> dialog.cancelButton().fire());
        runOnFx(dialog::close);
        assertThat(onFx(dialog::isShowing)).as("Escape and the close box are vetoed").isTrue();
        head.release();

        assertThat(await(saveAs)).isEqualTo(new SaveAsFlow.Published(target));
        assertThat(fixture.currentProjectIs(target)).isTrue();
    }

    @Test
    void aCancelWhileTheFilesAreCopiedDeletesWhatItCreatedAndLeavesTheProjectAsItWas() throws Exception {
        SaveFlowFixture.Publishes.Hold take = fixture.publishes.holdNext(path -> isInTheStaging(path, "take.wav"));
        fixture.saveAsTargets.choose(target);
        CompletionStage<SaveAsFlow.SaveAsResult> saveAs = start(SaveAsFlow.Prefill.COPY);
        take.awaitHeld();
        SaveAsProgressDialog dialog = onFx(() -> fixture.saveAsFlow().progressDialog());

        runOnFx(() -> dialog.cancelButton().fire());
        assertThat(onFx(dialog::isShowing)).as("the dialog closes at once").isFalse();
        take.release();

        assertThat(await(saveAs)).isEqualTo(new SaveAsFlow.Cancelled());
        assertThat(fixture.notifications()).extracting(NotificationEntry::message)
                .containsExactly("Save As was cancelled — “Song A copy” was not created.");
        assertThat(target).doesNotExist();
        try (Stream<Path> left = Files.list(workspace)) {
            assertThat(left.map(path -> path.getFileName().toString())).noneMatch(name -> name.contains("saving-as"));
        }
        assertThat(fixture.currentProjectIs(songA)).isTrue();
        assertThat(onFx(() -> fixture.project.get().isDirty())).isTrue();
        assertThat(onFx(() -> fixture.lifecycle().closeReadiness().pending()))
                .isInstanceOf(ProjectLifecycleController.PendingSwitch.None.class);
    }

    @Test
    void aCancelWhileThePlanRunsEndsTheSaveAsAtOnceAndNeverWaitsForThePlansWorker() throws Exception {
        HeldWorker held = new HeldWorker();
        runOnFx(() -> fixture.saveAsFlow().setWorker(held));
        fixture.saveAsTargets.choose(target);
        CompletionStage<SaveAsFlow.SaveAsResult> saveAs = start(SaveAsFlow.Prefill.COPY);
        held.awaitSubmitted();
        SaveAsProgressDialog dialog = onFx(() -> fixture.saveAsFlow().progressDialog());
        assertThat(onFx(dialog::statusText)).as("stage-5 review B1 / ENG-S5: the plan's line, never blank")
                .isEqualTo("Checking what to copy into “Song A copy”…");
        assertThat(onFx(dialog::progress)).as("determinate, at 0").isEqualTo(0.0);
        assertThat(onFx(() -> dialog.cancelButton().isDisabled())).as("Cancel stays enabled").isFalse();

        runOnFx(() -> fixture.saveAsFlow().progressDialog().cancelButton().fire());

        assertThat(await(saveAs)).as("ended while the plan's worker has not even run").isEqualTo(
                new SaveAsFlow.Cancelled());
        assertThat(fixture.notifications()).extracting(NotificationEntry::message)
                .containsExactly("Save As was cancelled — “Song A copy” was not created.");
        assertThat(onFx(() -> fixture.lifecycle().closeReadiness().pending()))
                .isInstanceOf(ProjectLifecycleController.PendingSwitch.None.class);
        held.runAll();
        fixture.settle();
        assertThat(target).as("the plan's late result is dropped: nothing copied").doesNotExist();
        assertThat(fixture.notifications()).hasSize(1);
    }

    @Test
    void aCopyThatFailsSaysSoAndLeavesNothing() throws Exception {
        fixture.publishes.failNext(path -> isInTheStaging(path, "take.wav"), new IOException("the disk is full"));
        fixture.saveAsTargets.choose(target);

        assertThat(await(start(SaveAsFlow.Prefill.COPY))).isInstanceOf(SaveAsFlow.Failed.class);

        assertThat(fixture.notifications()).extracting(NotificationEntry::level, NotificationEntry::message)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(NotificationLevel.ERROR,
                        "Save As failed: the disk is full — “Song A copy” was not created, and the open project is"
                                + " unchanged."));
        assertThat(target).doesNotExist();
        assertThat(fixture.currentProjectIs(songA)).isTrue();
    }

    @Test
    void aSaveAsIsRefusedWhileRecordingAndWhileATakeIsWrittenAndAsksNothing() throws Exception {
        runOnFx(() -> fixture.lifecycle().setRecordingInFlightCheck(() -> true));
        assertThat(await(start(SaveAsFlow.Prefill.COPY))).isEqualTo(new SaveAsFlow.Refused());
        runOnFx(() -> {
            fixture.lifecycle().setRecordingInFlightCheck(() -> false);
            fixture.lifecycle().setTakeBeingWrittenCheck(() -> true);
        });
        assertThat(await(start(SaveAsFlow.Prefill.COPY))).isEqualTo(new SaveAsFlow.Refused());

        assertThat(fixture.notifications()).extracting(NotificationEntry::level, NotificationEntry::message)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(NotificationLevel.WARNING,
                                "Save As can't start while recording — stop the recording first."),
                        org.assertj.core.groups.Tuple.tuple(NotificationLevel.WARNING,
                                "Save As can't start until the last take has been written."));
        assertThat(fixture.saveAsTargets.calls()).isEmpty();
    }

    @Test
    void aSaveAsIsTheOneSwitchPendingWhileItsChooserIsOpen() throws Exception {
        AtomicReference<CompletionStage<SaveAsFlow.SaveAsResult>> second = new AtomicReference<>();
        AtomicReference<ProjectLifecycleController.PendingSwitch> pendingWhileChoosing = new AtomicReference<>();
        fixture.saveAsTargets.answer(_ -> {
            pendingWhileChoosing.set(fixture.lifecycle().closeReadiness().pending());
            second.set(fixture.saveAsFlow().start(SaveAsFlow.Prefill.COPY));
            fixture.lifecycle().onNewProject();
            return Optional.empty();
        });

        assertThat(await(start(SaveAsFlow.Prefill.COPY))).isEqualTo(new SaveAsFlow.ChooserCancelled());

        assertThat(pendingWhileChoosing.get()).isInstanceOf(ProjectLifecycleController.PendingSwitch.SaveAsChoosing.class);
        assertThat(await(second.get())).isEqualTo(new SaveAsFlow.Refused());
        assertThat(fixture.notifications()).extracting(NotificationEntry::message)
                .as("G2/L4: one INFO for repeated refusals").containsExactly(
                        "Save As's file dialog is still open — finish or cancel it first.");
        assertThat(fixture.notifications()).hasSize(1);
        assertThat(onFx(() -> fixture.lifecycle().closeReadiness().pending()))
                .isInstanceOf(ProjectLifecycleController.PendingSwitch.None.class);
    }

    @Test
    void aTakenNameIsRefusedAndTheChooserReopensWithTheFirstFreeName() throws Exception {
        Files.createDirectories(target.resolve("notes"));
        fixture.saveAsTargets.choose(target).cancel();

        assertThat(await(start(SaveAsFlow.Prefill.COPY))).isEqualTo(new SaveAsFlow.ChooserCancelled());

        assertThat(fixture.saveAsTargets.calls()).as("R6: the taken name is not pre-filled; Q6: reopened").containsExactly(
                new SaveFlowFixture.SaveAsTargets.Call(workspace, "Song A copy 2"),
                new SaveFlowFixture.SaveAsTargets.Call(workspace, "Song A copy 2"));
        assertThat(fixture.saveAsRefusals).containsExactly(
                "A folder named “Song A copy” already exists in " + workspace + ". Choose another name.");
        assertThat(fixture.currentProjectIs(songA)).isTrue();
    }

    @Test
    void aTargetInsideTheProjectIsRefusedAndTheChooserReopensInTheOriginalsParent() throws Exception {
        fixture.saveAsTargets.choose(songA.resolve("Inner")).cancel();

        assertThat(await(start(SaveAsFlow.Prefill.COPY))).isEqualTo(new SaveAsFlow.ChooserCancelled());

        assertThat(fixture.saveAsRefusals).containsExactly(
                "Save As can't save into the project's own folder. Choose a location outside “Song A”.");
        assertThat(fixture.saveAsTargets.calls()).last()
                .isEqualTo(new SaveFlowFixture.SaveAsTargets.Call(workspace, "Inner"));
        assertThat(songA.resolve("Inner")).doesNotExist();
    }

    @Test
    void theRefusalsSayWhatTheRulingsSayWithTheSizesInTheDiskCellsUnits() {
        assertThat(SaveAsFlow.refusalText(new SaveAsPlan.NotEnoughSpace(3_435_973_837L, 1_181_116_007L,
                Path.of("D:\\")), "Song A copy", "Song A"))
                .isEqualTo("Save As needs 3.2 GB free on " + Path.of("D:\\") + ", and it has 1.1 GB. Choose another"
                        + " location.");
        assertThat(SaveAsFlow.refusalText(new SaveAsPlan.NotEnoughSpace(3_435_973_837L, 3_420_000_000L,
                Path.of("D:\\")), "Song A copy", "Song A"))
                .as("Q2: the same text twice gets two decimals").isEqualTo("Save As needs 3.20 GB free on "
                        + Path.of("D:\\") + ", and it has 3.19 GB. Choose another location.");
    }

    @Test
    void missingFilesAreReportedInPlaceOfTheDoneNotification() throws Exception {
        runOnFx(() -> fixture.project.get().getTracks().getFirst()
                .addClip(new AudioClip("gone", 2, 1, songA.resolve("audio").resolve("gone.wav").toString())));
        fixture.saveAsTargets.choose(target);

        assertThat(await(start(SaveAsFlow.Prefill.COPY))).isEqualTo(new SaveAsFlow.Published(target));

        assertThat(fixture.notifications()).extracting(NotificationEntry::level, NotificationEntry::message)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(NotificationLevel.WARNING,
                        "Saved as “Song A copy”. 1 audio file the project uses was missing and was not copied; the"
                                + " copy still looks for it in " + songA + "."));
    }

    @Test
    void manyMissingFilesAreCounted() throws Exception {
        runOnFx(() -> {
            fixture.project.get().getTracks().getFirst()
                    .addClip(new AudioClip("gone", 2, 1, songA.resolve("audio").resolve("gone.wav").toString()));
            fixture.project.get().getTracks().getFirst()
                    .addClip(new AudioClip("lost", 4, 1, songA.resolve("audio").resolve("lost.wav").toString()));
        });
        fixture.saveAsTargets.choose(target);

        await(start(SaveAsFlow.Prefill.COPY));

        assertThat(fixture.notifications()).extracting(NotificationEntry::message).containsExactly(
                "Saved as “Song A copy”. 2 audio files the project uses were missing and were not copied; the copy"
                        + " still looks for them in " + songA + ".");
    }

    @Test
    void aCopyThatCannotBeLockedIsRememberedAndTheOpenProjectStaysAsItWas() throws Exception {
        fixture.projectManager.setLockConflictHandler((lock, stale) -> LockConflictResolution.OPEN_READ_ONLY);
        fixture.publishes.afterNext(path -> path.equals(target), published ->
                new ProjectLockManager(InstantSource.system(), "bob", "Mix-Room", 7L).forceAcquire(published));
        fixture.saveAsTargets.choose(target);

        assertThat(await(start(SaveAsFlow.Prefill.COPY))).isInstanceOf(SaveAsFlow.Failed.class);

        assertThat(fixture.notifications()).extracting(NotificationEntry::level, NotificationEntry::message)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(NotificationLevel.ERROR,
                        "Save As wrote “Song A copy” in " + workspace + " but could not open it: another session"
                                + " holds its lock. “Song A” stays open; you can open the copy from Recent Projects."));
        assertThat(fixture.currentProjectIs(songA)).as("ENG-S5: the open project unchanged").isTrue();
        assertThat(fixture.projectManager.getLockManager().currentLock()).isPresent();
        assertThat(onFx(() -> fixture.project.get().getName())).isEqualTo("Song A");
        assertThat(onFx(() -> fixture.project.get().isDirty())).isTrue();
        assertThat(target.resolve("project.daw")).as("the copy stays on disk").exists();
        assertThat(fixture.projectManager.getRecentProjectPaths()).as("stage-5 review S5: and in Recent Projects")
                .contains(target);
    }

    @Test
    void aCopyWhoseLockWasTakenBeforeItCouldBeOpenedNamesTheHolder() {
        com.benesquivelmusic.daw.core.persistence.ProjectLock alice = new com.benesquivelmusic.daw.core.persistence
                .ProjectLock("lock-1", "alice", "Studio-PC", 42L, Instant.EPOCH, Instant.EPOCH);

        assertThat(SaveAsFlow.notOpenedReason(new com.benesquivelmusic.daw.core.persistence.LockStolenException(
                "Lock has been taken over", alice))).as("ENG-N5").isEqualTo("alice on Studio-PC (process 42) took its lock");
        assertThat(SaveAsFlow.notOpenedReason(new com.benesquivelmusic.daw.core.persistence.LockStolenException(
                "Lock file has been deleted; another session may be writing", null))).isEqualTo("its lock was removed");
        assertThat(SaveAsFlow.notOpenedReason(new IOException("another session holds its lock")))
                .as("any other cause as it reads").isEqualTo("another session holds its lock");
    }

    @Test
    void theCopyOpensCleanAndItsRenameMarksNothingDirtyWithTheViewModelListening() throws Exception {
        ProjectVM vm = onFx(() -> new ProjectVM(fixture.project.get(), fixture.dispatcher));
        long revision = onFx(() -> fixture.project.get().getEditRevision());
        fixture.saveAsTargets.choose(target);

        await(start(SaveAsFlow.Prefill.COPY));
        fixture.pulse();

        assertThat(onFx(() -> fixture.project.get().getEditRevision()))
                .as("E3-N9: nothing marked the project dirty after its capture").isEqualTo(revision);
        assertThat(onFx(() -> fixture.project.get().isDirty())).isFalse();
        assertThat(onFx(() -> vm.dirtyProperty().get())).isFalse();
        assertThat(onFx(() -> vm.nameProperty().get())).isEqualTo("Song A copy");
        runOnFx(vm::dispose);
    }

    @Test
    void aSnapshotTheCopyTookFromTheOriginalRestoresUnderTheCopysName() throws Exception {
        runOnFx(() -> fixture.lifecycle().createNamedSnapshot("Before"));
        fixture.settle();
        fixture.awaitOnFx(() -> Files.isDirectory(songA.resolve("snapshots")), "the named snapshot");
        fixture.saveAsTargets.choose(target);
        await(start(SaveAsFlow.Prefill.COPY));

        runOnFx(() -> fixture.snapshots().registerCurrentProjectDirectory());
        SnapshotEntry copied = onFx(() -> fixture.snapshots().service().getEntries().stream()
                .filter(entry -> entry.kind() == SnapshotKind.NAMED_SNAPSHOT).findFirst().orElseThrow());
        runOnFx(() -> fixture.snapshots().restoreEntry(copied));
        fixture.settle();

        assertThat(onFx(() -> fixture.project.get().getName()))
                .as("E3-N7: a restore keeps the open project's name").isEqualTo("Song A copy");
    }

    @Test
    void theExitsSaveAsPublishesAndRemembersTheCopyAndOpensNothing() throws Exception {
        AtomicReference<Integer> stops = new AtomicReference<>(0);
        fixture.saveAsTargets.choose(target);
        AtomicReference<CompletionStage<SaveAsFlow.SaveAsResult>> saveAs = new AtomicReference<>();

        runOnFx(() -> saveAs.set(fixture.saveAsFlow().publishCopyBeforeClosing(SaveAsFlow.Prefill.COPY,
                () -> stops.set(stops.get() + 1))));

        assertThat(await(saveAs.get())).isEqualTo(new SaveAsFlow.Published(target));
        assertThat(stops.get()).as("the playback stop right before the capture").isEqualTo(1);
        assertThat(target.resolve("project.daw")).exists();
        assertThat(target.resolve(ProjectLockManager.LOCK_FILE_NAME)).as("no lock: nothing opened").doesNotExist();
        assertThat(fixture.currentProjectIs(songA)).isTrue();
        assertThat(fixture.projectManager.getRecentProjectPaths()).contains(target);
        assertThat(onFx(() -> fixture.lifecycle().closeReadiness().pending()))
                .as("registers no switch of its own").isInstanceOf(ProjectLifecycleController.PendingSwitch.None.class);
    }

    // ── ruling 2 OQ-1: Continue ────────────────────────────────────────────

    @Test
    void continueWhileTheOriginalsSealIsHeldOpensTheCopyWithNoJournalAndReleasesTheOriginalOnlyAfterTheSeal()
            throws Exception {
        CountDownLatch sealReached = new CountDownLatch(1);
        CountDownLatch releaseTheSeal = new CountDownLatch(1);
        withTheJournal(real -> holdingTheSealOf(songA, real, sealReached, releaseTheSeal));
        byte[] originalManifest = manifestOf(songA);
        fixture.saveAsTargets.choose(target);
        try {
            CompletionStage<SaveAsFlow.SaveAsResult> saveAs = start(SaveAsFlow.Prefill.COPY);
            assertThat(sealReached.await(GUARD.toMillis(), TimeUnit.MILLISECONDS)).as("the seal is held").isTrue();
            pressContinueAfterTenSeconds();

            assertThat(await(saveAs)).isEqualTo(new SaveAsFlow.Published(target));
            assertThat(fixture.notifications()).extracting(NotificationEntry::level, NotificationEntry::message)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(NotificationLevel.ERROR, "Saved as “Song A"
                            + " copy” in " + workspace + "; journaled persistence is paused until this project is"
                            + " reopened. “Song A” was still being closed."));
            assertThat(target.resolve("journal")).as("no journal is opened for the copy").doesNotExist();
            assertThat(journal.currentProjectDirectory()).isNotEqualTo(target);
            assertThat(lastRecordOf(songA).isSessionSeal()).as("no seal marker while it is held").isFalse();
            assertThat(manifestOf(songA)).as("no manifest change in the original").isEqualTo(originalManifest);
            assertThat(songA.resolve(ProjectLockManager.LOCK_FILE_NAME))
                    .as("constraint 3: the lock stays until the held seal ends").exists();

            closeTheCopyWithTheHoldStillHeld();
        } finally {
            releaseTheSeal.countDown();
        }
        fixture.projectManager.whenRetiredLeasesReleased().get(GUARD.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).as("released once the seal ended")
                .doesNotExist();
        assertThat(JournalState.records(ProjectContext.forProject(songA)).stream()
                .filter(JournalRecord::isSessionSeal)).as("D7, confirmed: the seal that had begun ran to its end, once")
                .hasSize(1);
        assertThat(target.resolve("journal")).as("and nothing opened one for the copy later").doesNotExist();
    }

    @Test
    void continueWhileTheOriginalsClosingCheckpointIsHeldDrainsItsJournalUnsealedWithNoManifest() throws Exception {
        withTheJournal(UnaryOperator.identity());
        byte[] originalManifest = manifestOf(songA);
        SaveFlowFixture.Publishes.Hold closing = fixture.publishes.holdNext(
                path -> SaveFlowFixture.isCheckpoint(path) && path.startsWith(songA));
        fixture.saveAsTargets.choose(target);
        try {
            CompletionStage<SaveAsFlow.SaveAsResult> saveAs = start(SaveAsFlow.Prefill.COPY);
            closing.awaitHeld();
            pressContinueAfterTenSeconds();

            assertThat(await(saveAs)).isEqualTo(new SaveAsFlow.Published(target));
            assertThat(fixture.notifications()).extracting(NotificationEntry::message).containsExactly(
                    "Saved as “Song A copy” in " + workspace + "; journaled persistence is paused until this project"
                            + " is reopened. “Song A” was still being closed.");
            assertThat(target.resolve("journal")).doesNotExist();
            assertThat(songA.resolve(ProjectLockManager.LOCK_FILE_NAME))
                    .as("the lock stays while the held checkpoint may still write").exists();
            closeTheCopyWithTheHoldStillHeld();
        } finally {
            closing.release();
        }
        fixture.projectManager.whenRetiredLeasesReleased().get(GUARD.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).doesNotExist();
        assertThat(lastRecordOf(songA).isSessionSeal()).as("drained unsealed: no seal marker").isFalse();
        assertThat(manifestOf(songA)).as("and no manifest written").isEqualTo(originalManifest);
        assertThat(target.resolve("journal")).doesNotExist();
    }

    @Test
    void aJournalThatCannotOpenForTheCopySaysSoWithItsCause() throws Exception {
        runOnFx(() -> fixture.lifecycle().enableRecoveryGate(() -> true, () -> () -> { }, folder -> {
            throw new IllegalStateException("Journaled persistence could not start for " + folder + ": the disk");
        }));
        fixture.saveAsTargets.choose(target);

        assertThat(await(start(SaveAsFlow.Prefill.COPY))).isEqualTo(new SaveAsFlow.Published(target));

        assertThat(fixture.notifications()).extracting(NotificationEntry::level, NotificationEntry::message)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(NotificationLevel.ERROR,
                        "Saved as “Song A copy” in " + workspace + "; journaled persistence is paused until this"
                                + " project is reopened. Journaled persistence could not start for " + target
                                + ": the disk"));
        assertThat(fixture.currentProjectIs(target)).isTrue();
    }

    @Test
    void theCopysJournalOpensAndTheOriginalIsSealed() throws Exception {
        withTheJournal(UnaryOperator.identity());
        fixture.saveAsTargets.choose(target);

        assertThat(await(start(SaveAsFlow.Prefill.COPY))).isEqualTo(new SaveAsFlow.Published(target));

        assertThat(journal.currentProjectDirectory()).as("a fresh journal for the copy").isEqualTo(target);
        assertThat(lastRecordOf(songA).isSessionSeal()).as("the original sealed (R3: after its marker)").isTrue();
        assertThat(JournalState.needsRecovery(ProjectContext.forProject(songA)))
                .as("the original reopens plainly").isFalse();
    }

    // ── ruling 3 Q1: the copy's lock lost between its check and its adoption ──

    @Test
    void aLockTakenOverWhileTheOriginalClosesOpensTheCopyUnderTheLostLockWithOneError() throws Exception {
        withTheJournal(UnaryOperator.identity());
        runOnFx(() -> fixture.project.get().getTracks().getFirst()
                .addClip(new AudioClip("gone", 2, 1, songA.resolve("audio").resolve("gone.wav").toString())));
        SaveFlowFixture.Publishes.Hold closing = fixture.publishes.holdNext(
                path -> SaveFlowFixture.isCheckpoint(path) && path.startsWith(songA));
        fixture.saveAsTargets.choose(target);
        CompletionStage<SaveAsFlow.SaveAsResult> saveAs = start(SaveAsFlow.Prefill.COPY);
        closing.awaitHeld();
        new ProjectLockManager(InstantSource.system(), "alice", "Studio-PC", 42L).forceAcquire(target);
        closing.release();

        assertThat(await(saveAs)).isEqualTo(new SaveAsFlow.Published(target));

        List<NotificationEntry> shown = fixture.notifications();
        String lost = "Saved as “Song A copy” in " + workspace + ", but alice on Studio-PC (process 42) took it over"
                + " before it could open here, so changes made here can't be saved to it. Use Save As… to keep them"
                + " in a new project.";
        assertThat(shown).extracting(NotificationEntry::level, NotificationEntry::message).containsExactly(
                org.assertj.core.groups.Tuple.tuple(NotificationLevel.WARNING,
                        "Saved as “Song A copy”. 1 audio file the project uses was missing and was not copied; the"
                                + " copy still looks for it in " + songA + "."),
                org.assertj.core.groups.Tuple.tuple(NotificationLevel.ERROR, lost));
        assertThat(onFx(() -> fixture.notificationBar().getCurrentLevel()))
                .as("stage-5 review S1 / ENG-S3: the one toast left showing is the ERROR, with its action")
                .isEqualTo(NotificationLevel.ERROR);
        assertThat(onFx(() -> fixture.notificationBar().getMessage())).isEqualTo(lost);
        NotificationEntry error = shown.getLast();
        assertThat(error.actionLabel()).contains("Save As…");
        assertThat(fixture.currentProjectIs(target)).isTrue();
        assertThat(target.resolve("journal")).as("constraint 6: no journal for the copy").doesNotExist();
        assertThat(target.resolve("sessions")).doesNotExist();
        assertThat(entriesOf(target.resolve("checkpoints"))).as("no checkpoint under the lost lock").isEmpty();
        LockStatusIndicator indicator = onFx(LockStatusIndicator::new);
        runOnFx(() -> indicator.refresh(fixture.projectManager.getLockManager()));
        assertThat(onFx(indicator::getText)).isEqualTo("⚠ Lock stolen");
        assertThat(onFx(() -> fixture.project.get().getName())).isEqualTo("Song A copy");
        assertThat(onFx(() -> fixture.project.get().isDirty())).as("B's head is the capture").isFalse();
        assertThat(onFx(() -> fixture.project.get().getTracks().getFirst().getClips().getFirst().getSourceFilePath()))
                .startsWith(target.toString());

        fixture.save();
        fixture.settle();
        fixture.awaitOnFx(() -> fixture.takeoverHeaders.size() == 1, "Ctrl+S's takeover dialog");
        assertThat(fixture.takeoverHeaders).containsExactly(
                "“Song A copy” was taken over by alice on Studio-PC (process 42).");

        fixture.saveAsTargets.cancel();
        runOnFx(() -> error.action().orElseThrow().run());
        fixture.awaitOnFx(() -> fixture.saveAsTargets.calls().size() == 2, "the takeover Save As's chooser");
        SaveFlowFixture.SaveAsTargets.Call takeover = fixture.saveAsTargets.calls().getLast();
        assertThat(takeover.directory()).isEqualTo(workspace);
        assertThat(takeover.name()).matches("Song A copy-takeover-\\d{8}-\\d{6}");
        fixture.awaitOnFx(() -> fixture.lifecycle().closeReadiness().pending()
                instanceof ProjectLifecycleController.PendingSwitch.None, "the takeover Save As ended");

        fixture.projectManager.closeProject().get(GUARD.toMillis(), TimeUnit.MILLISECONDS);
        Path songC = fixture.anotherProjectOnDisk("Song C");
        java.util.concurrent.CompletableFuture<Object> opened = new java.util.concurrent.CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                opened.complete(fixture.projectManager.openProject(songC));
            } catch (Throwable notOpened) {
                opened.completeExceptionally(notOpened);
            }
        });
        opened.get(GUARD.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(fixture.currentProjectIs(songC)).as("fixture: another project is open").isTrue();
        runOnFx(() -> error.action().orElseThrow().run());
        fixture.settle();
        assertThat(fixture.saveAsTargets.calls()).as("stage-5 review S3 / ENG-N1: in another project the"
                + " Notifications history's action does nothing").hasSize(2);
        assertThat(onFx(() -> fixture.lifecycle().closeReadiness().pending()))
                .isInstanceOf(ProjectLifecycleController.PendingSwitch.None.class);
    }

    @Test
    void continueWithAMissingFileShowsTheWarningThenTheOneErrorWhichTheBarKeeps() throws Exception {
        runOnFx(() -> fixture.project.get().getTracks().getFirst()
                .addClip(new AudioClip("gone", 2, 1, songA.resolve("audio").resolve("gone.wav").toString())));
        SaveFlowFixture.Publishes.Hold closing = fixture.publishes.holdNext(
                path -> SaveFlowFixture.isCheckpoint(path) && path.startsWith(songA));
        fixture.saveAsTargets.choose(target);
        try {
            CompletionStage<SaveAsFlow.SaveAsResult> saveAs = start(SaveAsFlow.Prefill.COPY);
            closing.awaitHeld();
            pressContinueAfterTenSeconds();
            assertThat(await(saveAs)).isEqualTo(new SaveAsFlow.Published(target));
        } finally {
            closing.release();
        }

        String continued = "Saved as “Song A copy” in " + workspace + "; journaled persistence is paused until this"
                + " project is reopened. “Song A” was still being closed.";
        assertThat(fixture.notifications()).extracting(NotificationEntry::level, NotificationEntry::message)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(NotificationLevel.WARNING,
                                "Saved as “Song A copy”. 1 audio file the project uses was missing and was not copied;"
                                        + " the copy still looks for it in " + songA + "."),
                        org.assertj.core.groups.Tuple.tuple(NotificationLevel.ERROR, continued));
        assertThat(onFx(() -> fixture.notificationBar().getCurrentLevel())).isEqualTo(NotificationLevel.ERROR);
        assertThat(onFx(() -> fixture.notificationBar().getMessage())).isEqualTo(continued);
    }

    @Test
    void continueWhileTheOriginalsLockIsStillBeingReadClosesItWithADrainAlone() throws Exception {
        withTheJournal(UnaryOperator.identity());
        byte[] originalManifest = manifestOf(songA);
        byte[] originalHead = Files.readAllBytes(songA.resolve("project.daw"));
        int originalCheckpoints = SaveFlowFixture.checkpointFiles(songA).size();
        java.util.concurrent.CompletableFuture<Optional<com.benesquivelmusic.daw.core.persistence.LockStolenException>>
                heldCheck = new java.util.concurrent.CompletableFuture<>();
        runOnFx(() -> fixture.saveAsFlow().setOriginalLockCheck(() -> heldCheck));
        fixture.saveAsTargets.choose(target);
        CompletionStage<SaveAsFlow.SaveAsResult> saveAs = start(SaveAsFlow.Prefill.COPY);
        fixture.awaitOnFx(() -> fixture.saveAsFlow().progressDialog() != null
                && "Closing “Song A”…".equals(fixture.saveAsFlow().progressDialog().statusText()),
                "the original's close begun, its lock still being read");

        pressContinueAfterTenSeconds();

        assertThat(await(saveAs)).as("ENG-S1: Continue is offered while the lock is read")
                .isEqualTo(new SaveAsFlow.Published(target));
        assertThat(fixture.notifications()).extracting(NotificationEntry::level, NotificationEntry::message)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(NotificationLevel.ERROR, "Saved as “Song A"
                        + " copy” in " + workspace + "; journaled persistence is paused until this project is"
                        + " reopened. “Song A” was still being closed."));
        fixture.projectManager.whenRetiredLeasesReleased().get(GUARD.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).as("released once the drain ended")
                .doesNotExist();
        assertThat(SaveFlowFixture.checkpointFiles(songA)).as("no closing checkpoint").hasSize(originalCheckpoints);
        assertThat(lastRecordOf(songA).isSessionSeal()).as("no seal marker: drained, never sealed").isFalse();
        assertThat(manifestOf(songA)).as("no manifest change").isEqualTo(originalManifest);
        assertThat(Files.readAllBytes(songA.resolve("project.daw"))).isEqualTo(originalHead);
        assertThat(target.resolve("journal")).as("no journal for the copy").doesNotExist();

        heldCheck.complete(Optional.empty());
        fixture.settle();
        assertThat(fixture.notifications()).as("the late answer starts no second close").hasSize(1);
        assertThat(SaveFlowFixture.checkpointFiles(songA)).hasSize(originalCheckpoints);
        assertThat(fixture.currentProjectIs(target)).isTrue();
    }

    /**
     * The app fix pass 1 review, S2 (ENG5-S1's guard): Continue closed the
     * original while its lock was still being read, and the copy's adoption
     * is under way when the read's late answer comes — the run has not ended,
     * but it continued, so the answer is dropped: no second close, no second
     * adoption of the one lease, the copy open and locked, Song A unchanged.
     */
    @Test
    void theOriginalsLateLockAnswerDuringTheCopysAdoptionAfterContinueStartsNothing() throws Exception {
        withTheJournal(UnaryOperator.identity());
        int originalCheckpoints = SaveFlowFixture.checkpointFiles(songA).size();
        java.util.concurrent.CompletableFuture<Optional<com.benesquivelmusic.daw.core.persistence.LockStolenException>>
                heldCheck = new java.util.concurrent.CompletableFuture<>();
        HoldingTheAdoption worker = new HoldingTheAdoption();
        runOnFx(() -> {
            fixture.saveAsFlow().setOriginalLockCheck(() -> heldCheck);
            fixture.saveAsFlow().setWorker(worker);
        });
        fixture.saveAsTargets.choose(target);
        CompletionStage<SaveAsFlow.SaveAsResult> saveAs = start(SaveAsFlow.Prefill.COPY);
        fixture.awaitOnFx(() -> fixture.saveAsFlow().progressDialog() != null
                && "Closing “Song A”…".equals(fixture.saveAsFlow().progressDialog().statusText()),
                "the original's close begun, its lock still being read");
        pressContinueAfterTenSeconds();
        worker.awaitTheAdoption();

        heldCheck.complete(Optional.empty());
        fixture.settle();
        worker.releaseTheAdoption();

        assertThat(await(saveAs)).isEqualTo(new SaveAsFlow.Published(target));
        fixture.settle();
        assertThat(fixture.notifications()).extracting(NotificationEntry::level, NotificationEntry::message)
                .as("exactly the one Continue ERROR").containsExactly(org.assertj.core.groups.Tuple.tuple(
                        NotificationLevel.ERROR, "Saved as “Song A copy” in " + workspace + "; journaled persistence"
                                + " is paused until this project is reopened. “Song A” was still being closed."));
        assertThat(fixture.currentProjectIs(target)).isTrue();
        assertThat(fixture.projectManager.getLockManager().currentLock()).isPresent();
        assertThat(ProjectLockManager.peekLock(target)).as("the copy's lock, adopted once").isPresent();
        assertThat(SaveFlowFixture.checkpointFiles(songA)).as("Song A unchanged").hasSize(originalCheckpoints);
    }

    /**
     * The app fix pass 1 review, N2 (ENG5-N6): an adoption the worker refuses
     * after the original was closed — reachable through the worker seam —
     * says the copy was written but not opened while the original was
     * already closed, logged SEVERE; the copy is remembered in Recent
     * Projects and its lease released off the FX thread, a virtual thread of
     * its own when the worker refuses that too.
     */
    @Test
    void anAdoptionTheWorkerRefusesAfterTheOriginalClosedSaysSoRemembersTheCopyAndReleasesItsLease()
            throws Exception {
        java.util.concurrent.atomic.AtomicInteger submitted = new java.util.concurrent.atomic.AtomicInteger();
        runOnFx(() -> fixture.saveAsFlow().setWorker(task -> {
            if (submitted.incrementAndGet() >= 2) {
                throw new java.util.concurrent.RejectedExecutionException("the worker is shut down");
            }
            Thread.ofVirtual().start(task);
        }));
        fixture.saveAsTargets.choose(target);

        SaveAsFlow.SaveAsResult result = await(start(SaveAsFlow.Prefill.COPY));

        assertThat(result).isInstanceOf(SaveAsFlow.Failed.class);
        fixture.awaitOnFx(() -> !Files.exists(target.resolve(ProjectLockManager.LOCK_FILE_NAME)),
                "the copy's lease released");
        assertThat(fixture.notifications()).extracting(NotificationEntry::level, NotificationEntry::message)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(NotificationLevel.ERROR, "Save As wrote “Song A"
                        + " copy” in " + workspace + " but could not open it: the worker is shut down. “Song A” was"
                        + " already closed; you can open the copy from Recent Projects."));
        assertThat(fixture.projectManager.getRecentProjectPaths()).as("remembered first").contains(target);
        assertThat(submitted).as("the adoption, then its release, each refused by the worker").hasValueGreaterThan(2);
    }

    /**
     * The app fix pass 1 review, N5: a Save As of a project never saved has
     * no original folder to close, so the step line between the copy and the
     * adoption is the head's — past the copy, Cancel disabled — never the
     * planning line the dropped keyed post leaves behind.
     */
    @Test
    void aNeverSavedOriginalShowsTheHeadsLinePastTheCopyWhileItsCloseRuns() throws Exception {
        fixture.projectManager.closeProject().get(GUARD.toMillis(), TimeUnit.MILLISECONDS);
        runOnFx(() -> {
            DawProject sketch = new DawProject("Sketch", com.benesquivelmusic.daw.core.audio.AudioFormat.CD_QUALITY);
            sketch.createAudioTrack("Idea");
            sketch.markDirty();
            fixture.project.set(sketch);
        });
        java.util.concurrent.CompletableFuture<Optional<com.benesquivelmusic.daw.core.persistence.LockStolenException>>
                heldCheck = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.CountDownLatch asked = new java.util.concurrent.CountDownLatch(1);
        runOnFx(() -> fixture.saveAsFlow().setOriginalLockCheck(() -> {
            asked.countDown();
            return heldCheck;
        }));
        Path sketchCopy = workspace.resolve("Sketch");
        fixture.saveAsTargets.choose(sketchCopy);
        CompletionStage<SaveAsFlow.SaveAsResult> saveAs = start(SaveAsFlow.Prefill.COPY);
        try {
            assertThat(asked.await(GUARD.toMillis(), TimeUnit.MILLISECONDS)).as("the copy published").isTrue();
            SaveAsProgressDialog dialog = onFx(() -> fixture.saveAsFlow().progressDialog());
            assertThat(onFx(dialog::statusText)).as("the head's line, not the planning line")
                    .isEqualTo("Writing project.daw…");
            assertThat(onFx(() -> dialog.cancelButton().isDisabled())).as("past the copy: no Cancel").isTrue();
        } finally {
            heldCheck.complete(Optional.empty());
        }
        assertThat(await(saveAs)).isEqualTo(new SaveAsFlow.Published(sketchCopy));
    }

    @Test
    void aLockFileRemovedAfterContinueOpensTheCopyWithTheNoHolderErrorAlone() throws Exception {
        SaveFlowFixture.Publishes.Hold closing = fixture.publishes.holdNext(
                path -> SaveFlowFixture.isCheckpoint(path) && path.startsWith(songA));
        fixture.saveAsTargets.choose(target);
        try {
            CompletionStage<SaveAsFlow.SaveAsResult> saveAs = start(SaveAsFlow.Prefill.COPY);
            closing.awaitHeld();
            Files.delete(target.resolve(ProjectLockManager.LOCK_FILE_NAME));
            pressContinueAfterTenSeconds();

            assertThat(await(saveAs)).isEqualTo(new SaveAsFlow.Published(target));
        } finally {
            closing.release();
        }

        assertThat(fixture.notifications()).extracting(NotificationEntry::level, NotificationEntry::message)
                .as("one ERROR: the lost lock's replaces Continue's").containsExactly(
                        org.assertj.core.groups.Tuple.tuple(NotificationLevel.ERROR, "Saved as “Song A copy” in "
                                + workspace + ", but its lock was removed before it could open here, so another"
                                + " session may have taken it over and changes made here can't be saved to it. Use"
                                + " Save As… to keep them in a new project."));
    }

    @Test
    void aLockCheckThatCannotReadTheCopysLockOpensItUnverifiedWithThePausedJournalError() throws Exception {
        withTheJournal(UnaryOperator.identity());
        SaveFlowFixture.Publishes.Hold closing = fixture.publishes.holdNext(
                path -> SaveFlowFixture.isCheckpoint(path) && path.startsWith(songA));
        fixture.saveAsTargets.choose(target);
        CompletionStage<SaveAsFlow.SaveAsResult> saveAs = start(SaveAsFlow.Prefill.COPY);
        closing.awaitHeld();
        Path lock = target.resolve(ProjectLockManager.LOCK_FILE_NAME);
        Files.delete(lock);
        Files.createDirectory(lock);
        List<java.util.logging.LogRecord> warnings = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.logging.Logger managerLog = java.util.logging.Logger.getLogger(
                com.benesquivelmusic.daw.core.persistence.ProjectManager.class.getName());
        java.util.logging.Handler recording = recordingWarnings(warnings);
        managerLog.addHandler(recording);
        try {
            closing.release();
            assertThat(await(saveAs)).isEqualTo(new SaveAsFlow.Published(target));
        } finally {
            managerLog.removeHandler(recording);
        }

        assertThat(fixture.notifications()).extracting(NotificationEntry::level).containsExactly(NotificationLevel.ERROR);
        Throwable unreadable = warnings.stream().filter(record -> record.getThrown() != null
                        && record.getMessage().startsWith("The copy in " + target + " is open,"))
                .map(java.util.logging.LogRecord::getThrown).findFirst().orElseThrow();
        assertThat(fixture.notifications().getFirst().message()).as("stage-5 review N6: <cause> is the check's own")
                .isEqualTo("Saved as “Song A copy” in " + workspace + "; journaled persistence is paused until this"
                        + " project is reopened. " + ProjectLifecycleController.failureReason(unreadable))
                .doesNotContain("java.");
        assertThat(target.resolve("journal")).as("no journal: its lock is not known to be ours").doesNotExist();
        assertThat(fixture.currentProjectIs(target)).isTrue();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private CompletionStage<SaveAsFlow.SaveAsResult> start(SaveAsFlow.Prefill prefill) throws Exception {
        AtomicReference<CompletionStage<SaveAsFlow.SaveAsResult>> stage = new AtomicReference<>();
        runOnFx(() -> stage.set(fixture.saveAsFlow().start(prefill)));
        return stage.get();
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(GUARD.toMillis() * 2, TimeUnit.MILLISECONDS);
    }

    /** Whether {@code path} is the file {@code name} anywhere inside a Save As staging folder. */
    private static boolean isInTheStaging(Path path, String name) {
        return path.getFileName().toString().equals(name) && path.toString().contains(".saving-as.tmp");
    }

    /** Fires the 10 s of ruling S6, checks the ruled note, and presses Continue. */
    private void pressContinueAfterTenSeconds() throws Exception {
        fixture.pulse();
        runOnFx(() -> fixture.saveAsTimers.fire(SaveAsFlow.ORIGINAL_STILL_CLOSING_DELAY));
        SaveAsProgressDialog dialog = onFx(() -> fixture.saveAsFlow().progressDialog());
        assertThat(onFx(dialog::statusText)).as("stage-5 review B1 / ENG-S5: the original is being closed")
                .isEqualTo("Closing “Song A”…");
        assertThat(onFx(dialog::noteText)).isEqualTo("“Song A copy” is saved, but “Song A” is still being closed"
                + " after 10 seconds. The disk may not be responding. If you continue, the next open of “Song A” may"
                + " offer to recover this session.");
        assertThat(onFx(() -> dialog.continueButton().isVisible())).isTrue();
        assertThat(onFx(() -> dialog.continueButton().isDefaultButton())).as("never the default").isFalse();
        runOnFx(() -> dialog.continueButton().fire());
    }

    /**
     * The copy's own close, as Close Project closes it, while the original's
     * hold is still held: it does not wait on it (ruling 2 OQ-1, constraint 7).
     */
    private void closeTheCopyWithTheHoldStillHeld() throws Exception {
        AtomicReference<ProjectFolderCloser.Closing> closing = new AtomicReference<>();
        runOnFx(() -> closing.set(fixture.folderCloser().close(new ProjectFolderCloser.Plan(
                ProjectFolderCloser.ClosingCheckpoint.IF_RECORDS_OUTSIDE_A_CAPTURE, Optional.of(JournalEnd.SEAL),
                ProjectFolderCloser.WriterScope.OPEN_GENERATION, false, false), () -> true,
                ProjectFolderCloser.Steps.NONE)));
        assertThat(await(closing.get().result())).isInstanceOf(ProjectFolderCloser.Released.class);
        fixture.awaitOnFx(() -> !Files.exists(target.resolve(ProjectLockManager.LOCK_FILE_NAME)),
                "the copy's lock released while the original's hold is held");
    }

    /** Journaling on: a real coordinator whose journal of Song A is open, and Song A's working session. */
    private void withTheJournal(UnaryOperator<JournalEnding> wrap) throws Exception {
        bus = new DefaultEventBus();
        sessions = new SessionManager();
        journal = new ProjectJournalCoordinator(fixture.progress, onFx(NotificationBar::new), fixture.dispatcher,
                () -> true, () -> bus, fixture.checkpoints, sessions, InstantSource.system());
        runOnFx(() -> fixture.lifecycle().enableRecoveryGate(() -> true, () -> {
            Path journalDirectory = journal.currentProjectDirectory();
            journal.detachFromBus();
            return () -> journal.closeCurrentFor(journalDirectory, null);
        }, journal::openForRequired));
        journal.openFor(songA);
        WorkingSession sessionOfA = sessions.openSession(songA, Instant.now()).current();
        JournalEnding ending = wrap.apply(new SessionJournalEnding(journal, sessions, () -> sessionOfA,
                () -> songA, () -> null, fixture::openFolder, InstantSource.system()));
        runOnFx(() -> fixture.installSaveAs(journal, ending));
    }

    /** {@code real}, whose seal of {@code folder} waits, once it began, until {@code release}. */
    private JournalEnding holdingTheSealOf(Path folder, JournalEnding real, CountDownLatch reached,
                                           CountDownLatch release) {
        return new JournalEnding() {
            @Override
            public void detach() {
                real.detach();
            }

            @Override
            public Runnable prepare(JournalEnd mode) {
                Runnable end = real.prepare(mode);
                boolean hold = mode == JournalEnd.SEAL && folder.equals(fixture.openFolder());
                return () -> {
                    if (hold) {
                        reached.countDown();
                        try {
                            release.await(GUARD.toMillis() * 3, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    end.run();
                };
            }
        };
    }

    private static JournalRecord lastRecordOf(Path folder) throws IOException {
        List<JournalRecord> records = JournalState.records(ProjectContext.forProject(folder));
        assertThat(records).as("the journal of %s", folder).isNotEmpty();
        return records.getLast();
    }

    /** The bytes of every session manifest of {@code folder}, concatenated in name order. */
    private static byte[] manifestOf(Path folder) throws IOException {
        Path manifests = folder.resolve("sessions");
        if (!Files.isDirectory(manifests)) {
            return new byte[0];
        }
        try (Stream<Path> files = Files.list(manifests)) {
            java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
            for (Path file : files.sorted().toList()) {
                all.write(Files.readAllBytes(file));
            }
            return all.toByteArray();
        }
    }

    private static List<Path> entriesOf(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.toList();
        }
    }

    /**
     * {@code real}, whose SEAL fails as the coordinator's does when the disk
     * refuses the seal: the journal is closed unsealed — no seal marker, no
     * manifest — and the failure is thrown.
     */
    private JournalEnding sealThatThrows(JournalEnding real) {
        return new JournalEnding() {
            @Override
            public void detach() {
                real.detach();
            }

            @Override
            public Runnable prepare(JournalEnd mode) {
                if (mode != JournalEnd.SEAL) {
                    return real.prepare(mode);
                }
                Runnable closedUnsealed = real.prepare(JournalEnd.DRAIN_WITHOUT_MANIFEST);
                return () -> {
                    closedUnsealed.run();
                    throw new java.io.UncheckedIOException("Project journal could not be sealed",
                            new IOException("the journal disk refused the seal"));
                };
            }
        };
    }

    /** A handler that records every WARNING it is given into {@code warnings}. */
    private static java.util.logging.Handler recordingWarnings(List<java.util.logging.LogRecord> warnings) {
        return new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord logRecord) {
                if (logRecord.getLevel() == java.util.logging.Level.WARNING) {
                    warnings.add(logRecord);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
    }

    /** Every file and folder under {@code folder}, by relative path, with its size and last-modified time. */
    private static java.util.Map<String, String> listing(Path folder) throws IOException {
        java.util.Map<String, String> entries = new java.util.TreeMap<>();
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path entry : walk.toList()) {
                entries.put(folder.relativize(entry).toString(), (Files.isDirectory(entry) ? "dir" : Files.size(entry))
                        + " @ " + Files.getLastModifiedTime(entry));
            }
        }
        return entries;
    }

    /**
     * The worker of the Save As, running each task on a virtual thread of
     * its own but for the second one — the adoption, after the plan and the
     * copy — which it holds until the test says so.
     */
    private static final class HoldingTheAdoption implements Executor {
        private final java.util.concurrent.atomic.AtomicInteger submitted = new java.util.concurrent.atomic.AtomicInteger();
        private final CountDownLatch adoptionSubmitted = new CountDownLatch(1);
        private final CountDownLatch adoptionReleased = new CountDownLatch(1);

        @Override
        public void execute(Runnable task) {
            if (submitted.incrementAndGet() == 2) {
                adoptionSubmitted.countDown();
                Thread.ofVirtual().start(() -> {
                    try {
                        if (adoptionReleased.await(GUARD.toMillis() * 3, TimeUnit.MILLISECONDS)) {
                            task.run();
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
                return;
            }
            Thread.ofVirtual().start(task);
        }

        void awaitTheAdoption() throws InterruptedException {
            assertThat(adoptionSubmitted.await(GUARD.toMillis(), TimeUnit.MILLISECONDS)).as("the adoption submitted")
                    .isTrue();
        }

        void releaseTheAdoption() {
            adoptionReleased.countDown();
        }
    }

    /** A worker that runs nothing until the test says so. */
    private static final class HeldWorker implements Executor {
        private final List<Runnable> tasks = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final CountDownLatch submitted = new CountDownLatch(1);

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
            submitted.countDown();
        }

        void awaitSubmitted() throws InterruptedException {
            assertThat(submitted.await(GUARD.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        }

        void runAll() throws InterruptedException {
            Thread runner = Thread.ofVirtual().start(() -> tasks.forEach(Runnable::run));
            runner.join(GUARD.toMillis());
        }
    }
}
