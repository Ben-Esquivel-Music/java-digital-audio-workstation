package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.status.ProjectJournalCoordinator;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.LockStatus;
import com.benesquivelmusic.daw.core.persistence.LockStolenException;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;
import com.benesquivelmusic.daw.core.persistence.journal.JournalRecord;
import com.benesquivelmusic.daw.core.persistence.journal.JournalState;
import com.benesquivelmusic.daw.core.persistence.journal.ProjectContext;
import com.benesquivelmusic.daw.core.session.SessionManager;
import com.benesquivelmusic.daw.core.session.WorkingSession;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, Goals — Tests, "Stolen-lock" — the Ctrl+S half (the studio
 * engineer's ruling R7; Persistence Integrity book §5.4 step 3, rejection
 * §9.14): a save that meets a lock another session took over is caught as
 * itself, never as an I/O failure, and asks the blocking takeover dialog in
 * place of story 331's ERROR — "Save As…" (the default) or "Not now". Not now
 * leaves the project dirty with its head failure standing, says nothing more,
 * and every later save asks again. Save As… keeps the work in a new project
 * pre-filled {@code <folder>-takeover-<stamp>} beside the original, which is
 * left to the other session: nothing is written to or deleted from it.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class StolenLockSaveAsFlowTest {

    @TempDir
    Path workspace;

    private SaveFlowFixture fixture;
    private Path songA;

    @BeforeEach
    void songAWithATakeSavedThenEdited() throws Exception {
        fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT);
        songA = fixture.songA;
        Files.write(songA.resolve("audio").resolve("take.wav"), new byte[1024]);
        runOnFx(() -> fixture.project.get().getTracks().getFirst()
                .addClip(new AudioClip("take", 0, 1, songA.resolve("audio").resolve("take.wav").toString())));
        fixture.save();
        fixture.settle();
        runOnFx(() -> {
            fixture.project.get().createAudioTrack("Bass");
            fixture.project.get().markDirty();
        });
    }

    @AfterEach
    void close() throws Exception {
        fixture.close();
    }

    @Test
    void aSaveThatMeetsAStolenLockAsksTheTakeoverDialogEveryTimeAndNotNowLeavesTheProjectAsItIs() throws Exception {
        assertThat(onFx(() -> fixture.lifecycle().closeReadiness().lockLost())).as("fixture: the lock is ours")
                .isFalse();
        new ProjectLockManager(InstantSource.system(), "alice", "Studio-PC", 42L).forceAcquire(songA);

        fixture.save();
        fixture.settle();
        fixture.awaitOnFx(() -> fixture.takeoverHeaders.size() == 1, "the takeover dialog");

        assertThat(fixture.takeoverHeaders).containsExactly("“Song A” was taken over by alice on Studio-PC (process 42).");
        assertThat(fixture.takeoverContents).containsExactly("Nothing was written to its folder, and your changes are"
                + " still open here. Save them as a new project to keep them; the original folder is left to the"
                + " other session.");
        assertThat(fixture.takeoverButtons).containsExactly(List.of("Save As…", "Not now"));
        assertThat(fixture.takeoverDefaults).containsExactly("Save As…");
        assertThat(fixture.notifications()).as("no ERROR toast: the dialog replaces it").isEmpty();
        assertThat(onFx(() -> fixture.project.get().isDirty())).as("Not now leaves the project dirty").isTrue();
        assertThat(onFx(() -> fixture.lifecycle().closeReadiness().headFailure()))
                .as("its head failure stands").containsInstanceOf(LockStolenException.class);
        assertThat(fixture.projectManager.getLockManager().status()).as("the lock cell's ⚠ Lock stolen")
                .isEqualTo(LockStatus.STOLEN);
        assertThat(onFx(() -> fixture.lifecycle().closeReadiness().lockLost()))
                .as("the studio engineer's stage-4 S8: the close knows the lock is lost, with no lock I/O").isTrue();

        fixture.save();
        fixture.settle();
        fixture.awaitOnFx(() -> fixture.takeoverHeaders.size() == 2, "the dialog again: never rate-limited");
        assertThat(fixture.notifications()).isEmpty();
        assertThat(fixture.saveAsTargets.calls()).as("Not now asks for no copy").isEmpty();
    }

    @Test
    void aLockFileRemovedByAnotherSessionAsksTheDialogWithNoHolder() throws Exception {
        Files.delete(songA.resolve(ProjectLockManager.LOCK_FILE_NAME));

        fixture.save();
        fixture.settle();
        fixture.awaitOnFx(() -> fixture.takeoverHeaders.size() == 1, "the takeover dialog");

        assertThat(fixture.takeoverHeaders).containsExactly(
                "The lock of “Song A” was removed, so another session may have taken the project over.");
        assertThat(fixture.notifications()).isEmpty();
    }

    @Test
    void saveAsFromTheTakeoverDialogKeepsTheWorkInANewProjectAndLeavesTheOriginalToTheOtherSession()
            throws Exception {
        new ProjectLockManager(InstantSource.system(), "alice", "Studio-PC", 42L).forceAcquire(songA);
        String foreignLock = Files.readString(songA.resolve(ProjectLockManager.LOCK_FILE_NAME));
        Map<String, FileTime> original = listing(songA);
        fixture.takeoverAnswer = "Save As…";
        fixture.saveAsTargets.answer(call -> Optional.of(call.directory().resolve(call.name())));

        fixture.save();
        fixture.settle();
        fixture.awaitOnFx(() -> fixture.saveAsTargets.calls().size() == 1 && fixture.lifecycle().closeReadiness()
                .pending() instanceof ProjectLifecycleController.PendingSwitch.None
                && !fixture.currentProjectIs(songA), "the takeover Save As");

        SaveFlowFixture.SaveAsTargets.Call call = fixture.saveAsTargets.calls().getFirst();
        assertThat(call.directory()).as("R6: the original's parent").isEqualTo(workspace);
        assertThat(call.name()).matches("Song A-takeover-\\d{8}-\\d{6}");
        Path copy = workspace.resolve(call.name());
        assertThat(fixture.currentProjectIs(copy)).as("the copy is the open project").isTrue();
        assertThat(fixture.projectManager.getLockManager().status()).isEqualTo(LockStatus.HELD);
        String head = Files.readString(copy.resolve("project.daw"), StandardCharsets.UTF_8);
        assertThat(head).as("the edit is in the copy").contains("Bass").doesNotContain(songA.toString());
        assertThat(copy.resolve("audio").resolve("take.wav")).exists();
        assertThat(onFx(() -> fixture.project.get().isDirty())).isFalse();
        assertThat(fixture.notifications()).extracting(NotificationEntry::message).as("no 331 ERROR")
                .noneMatch(message -> message.startsWith("Save failed"))
                .containsExactly("Saved as “" + call.name() + "” in " + workspace + ".");

        fixture.projectManager.whenRetiredLeasesReleased().get(SaveFlowFixture.GUARD.toMillis(),
                java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThat(listing(songA)).as("nothing written to or deleted from the original").isEqualTo(original);
        assertThat(Files.readString(songA.resolve(ProjectLockManager.LOCK_FILE_NAME)))
                .as("the other session's lock untouched").isEqualTo(foreignLock);
    }

    /**
     * The studio engineer's stage-5 N7: the takeover Save As with the journal
     * on — the original, whose lock another session took, is closed with its
     * journal drained into this session's own segment and nothing else: no
     * seal marker, no manifest change, no checkpoint, no head change, and no
     * other entry of the folder touched.
     */
    @Test
    void theTakeoverSaveAsWithTheJournalOnDrainsTheOriginalsJournalAndSealsNothing() throws Exception {
        DefaultEventBus bus = new DefaultEventBus();
        SessionManager sessions = new SessionManager();
        ProjectJournalCoordinator journal = new ProjectJournalCoordinator(fixture.progress, onFx(NotificationBar::new),
                fixture.dispatcher, () -> true, () -> bus, fixture.checkpoints, sessions, InstantSource.system());
        try {
            runOnFx(() -> fixture.lifecycle().enableRecoveryGate(() -> true, () -> {
                Path journalDirectory = journal.currentProjectDirectory();
                journal.detachFromBus();
                return () -> journal.closeCurrentFor(journalDirectory, null);
            }, journal::openForRequired));
            journal.openFor(songA);
            WorkingSession sessionOfA = sessions.openSession(songA, Instant.now()).current();
            runOnFx(() -> fixture.installSaveAs(journal, new SessionJournalEnding(journal, sessions, () -> sessionOfA,
                    () -> songA, () -> null, fixture::openFolder, InstantSource.system())));
            new ProjectLockManager(InstantSource.system(), "alice", "Studio-PC", 42L).forceAcquire(songA);
            byte[] head = Files.readAllBytes(songA.resolve("project.daw"));
            Map<String, FileTime> original = listing(songA);
            List<String> segments = journalFiles(songA);
            int checkpoints = SaveFlowFixture.checkpointFiles(songA).size();
            fixture.takeoverAnswer = "Save As…";
            fixture.saveAsTargets.answer(call -> Optional.of(call.directory().resolve(call.name())));

            fixture.save();
            fixture.settle();
            fixture.awaitOnFx(() -> fixture.saveAsTargets.calls().size() == 1 && fixture.lifecycle().closeReadiness()
                    .pending() instanceof ProjectLifecycleController.PendingSwitch.None
                    && !fixture.currentProjectIs(songA), "the takeover Save As");
            fixture.projectManager.whenRetiredLeasesReleased().get(SaveFlowFixture.GUARD.toMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS);

            Map<String, FileTime> after = listing(songA);
            original.keySet().removeIf(StolenLockSaveAsFlowTest::isTheJournals);
            after.keySet().removeIf(StolenLockSaveAsFlowTest::isTheJournals);
            assertThat(after).as("nothing else written to or deleted from the original").isEqualTo(original);
            assertThat(journalFiles(songA)).as("no journal file made: the drain goes into this session's segment")
                    .isEqualTo(segments);
            List<JournalRecord> records = JournalState.records(ProjectContext.forProject(songA));
            assertThat(records).as("no seal marker: a folder another session owns is never certified")
                    .noneMatch(JournalRecord::isSessionSeal);
            assertThat(SaveFlowFixture.checkpointFiles(songA)).as("no checkpoint").hasSize(checkpoints);
            assertThat(Files.readAllBytes(songA.resolve("project.daw"))).as("no head change").isEqualTo(head);
        } finally {
            journal.drainWithoutSeal(null);
            bus.close();
        }
    }

    /** Whether a listing entry is the journal folder or a file in it, which the drain may touch. */
    private static boolean isTheJournals(String relative) {
        return relative.equals("journal") || relative.startsWith("journal" + java.io.File.separator);
    }

    /** The names of the files in {@code folder}'s {@code journal/}, sorted. */
    private static List<String> journalFiles(Path folder) throws IOException {
        Path journal = folder.resolve("journal");
        if (!Files.isDirectory(journal)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(journal)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    /** Every file and folder under {@code folder}, by relative path, with its last-modified time. */
    private static Map<String, FileTime> listing(Path folder) throws IOException {
        Map<String, FileTime> entries = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path entry : walk.toList()) {
                entries.put(folder.relativize(entry).toString(), Files.getLastModifiedTime(entry));
            }
        }
        return entries;
    }
}
