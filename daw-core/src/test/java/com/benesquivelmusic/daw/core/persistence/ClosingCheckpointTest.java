package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.CountingClock;
import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.Recorder;
import com.benesquivelmusic.daw.core.project.DawProject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.TIMEOUT_SECONDS;
import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.checkpointNames;
import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.document;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The exit's closing checkpoint as the checkpoint manager writes it (story
 * 333, ruling R3; Persistence Integrity book §5.4): once requested it is
 * written — even when its revision is already checkpointed, and with
 * autosave disabled — by the running generation; it is refused when no
 * generation runs or its generation has ended, and by the generation's own
 * guard under a stolen lock; it counts toward the cap like any checkpoint;
 * and its fact carries the journal boundary of its capture. The project
 * manager's {@code writeClosingCheckpoint} captures the live project on the
 * caller and has it rendered and written by the open writable project's
 * generation — refused without one, read-only and after a close included —
 * and {@code isCheckpointed} tells whether the live revision is in a
 * checkpoint already (trigger (ii)).
 */
class ClosingCheckpointTest {

    private static final Duration INTERVAL = Duration.ofHours(1);

    @TempDir
    Path projectDir;

    private final List<CheckpointManager> managers = new ArrayList<>();
    private final List<ProjectLockManager> leases = new ArrayList<>();
    private final List<ProjectManager> projectManagers = new ArrayList<>();

    @AfterEach
    void stopEverything() throws Exception {
        for (ProjectManager projects : projectManagers) {
            projects.closeProject().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        managers.forEach(CheckpointManager::stop);
        for (ProjectLockManager lease : leases) {
            lease.close();
        }
    }

    @Test
    void aClosingCheckpointIsWrittenEvenWhenItsRevisionIsAlreadyCheckpointed() throws Exception {
        CheckpointManager manager = manager(new AutoSaveConfig(INTERVAL, 10, true));
        Recorder recorder = Recorder.on(manager);
        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Path afterSave = write(manager, 5, CheckpointOrigin.AFTER_SAVE);
        assertThat(write(manager, 5, CheckpointOrigin.AFTER_SAVE))
                .as("fixture: an ordinary request of a checkpointed revision names the file that holds it")
                .isEqualTo(afterSave);

        Path closing = write(manager, 5, CheckpointOrigin.CLOSING);

        assertThat(closing).isNotEqualTo(afterSave).hasContent(document(5));
        assertThat(checkpointNames(projectDir)).hasSize(2);
        assertThat(recorder.events(CheckpointEvent.Written.class))
                .extracting(CheckpointEvent.Written::origin, CheckpointEvent.Written::revision)
                .containsExactly(tuple(CheckpointOrigin.AFTER_SAVE, 5L), tuple(CheckpointOrigin.CLOSING, 5L));
    }

    @Test
    void aClosingCheckpointIsWrittenWithAutosaveDisabled() throws Exception {
        CheckpointManager manager = manager(new AutoSaveConfig(INTERVAL, 10, false));
        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        CompletableFuture<Path> afterSave = manager.writeCheckpoint(document(3), 3, CheckpointOrigin.AFTER_SAVE);
        assertThat(afterSave).as("fixture: an automatic checkpoint is refused while autosave is disabled")
                .isCompletedExceptionally();

        Path closing = write(manager, 3, CheckpointOrigin.CLOSING);

        assertThat(closing).hasContent(document(3));
        assertThat(checkpointNames(projectDir)).containsExactly(closing.getFileName().toString());
    }

    @Test
    void aClosingCheckpointIsRefusedWhenNoGenerationRunsOrItsGenerationHasEnded() throws Exception {
        CheckpointManager manager = manager(new AutoSaveConfig(INTERVAL, 10, true));
        assertRefused(manager.writeCheckpoint(document(1), 1, CheckpointOrigin.CLOSING), "Checkpoints are not running");

        CheckpointManager.Generation ended = manager.startGeneration(projectDir, () -> { });
        manager.stop();

        assertRefused(manager.writeCheckpoint(document(2), 2, CheckpointOrigin.CLOSING), "Checkpoints are not running");
        assertRefused(manager.writeCheckpoint(ended, document(3), 3, CheckpointOrigin.CLOSING),
                "stopped before this checkpoint was written");
        assertThat(checkpointNames(projectDir)).isEmpty();
    }

    @Test
    void aClosingCheckpointIsRefusedByItsGenerationsGuardUnderAStolenLock() throws Exception {
        InstantSource clock = InstantSource.fixed(Instant.parse("2026-10-28T12:00:00Z"));
        ProjectLockManager ours = lease(new ProjectLockManager(clock, "alice", "studio", 1L));
        ours.forceAcquire(projectDir);
        lease(new ProjectLockManager(clock, "mallory", "elsewhere", 2L)).forceAcquire(projectDir);
        CheckpointManager manager = manager(new AutoSaveConfig(INTERVAL, 10, true));
        Recorder recorder = Recorder.on(manager);
        manager.start(projectDir, ours::verifyHeld).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        CompletableFuture<Path> closing = manager.writeCheckpoint(document(4), 4, CheckpointOrigin.CLOSING);

        assertThatThrownBy(() -> closing.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(LockStolenException.class);
        assertThat(checkpointNames(projectDir)).as("nothing was written into the other session's folder").isEmpty();
        assertThat(recorder.events(CheckpointEvent.Failed.class)).singleElement()
                .satisfies(failed -> assertThat(failed.origin()).isEqualTo(CheckpointOrigin.CLOSING));
    }

    @Test
    void aClosingCheckpointCountsTowardTheCap() throws Exception {
        CheckpointManager manager = manager(new AutoSaveConfig(INTERVAL, 2, true));
        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Path oldest = write(manager, 1, CheckpointOrigin.AFTER_SAVE);
        Path newer = write(manager, 2, CheckpointOrigin.AFTER_SAVE);

        Path closing = write(manager, 3, CheckpointOrigin.CLOSING);

        assertThat(oldest).as("pruned to the cap of two").doesNotExist();
        assertThat(checkpointNames(projectDir))
                .containsExactly(newer.getFileName().toString(), closing.getFileName().toString());
    }

    @Test
    void itsWrittenFactCarriesTheJournalBoundaryOfItsCapture() throws Exception {
        CheckpointManager manager = manager(new AutoSaveConfig(INTERVAL, 10, true));
        Recorder recorder = Recorder.on(manager);
        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        Path closing = manager.writeCheckpoint(document(4), new CheckpointManager.Capture(4, OptionalLong.of(42)),
                CheckpointOrigin.CLOSING).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(recorder.events(CheckpointEvent.Written.class)).singleElement().satisfies(written -> {
            assertThat(written.origin()).isEqualTo(CheckpointOrigin.CLOSING);
            assertThat(written.file()).isEqualTo(closing);
            assertThat(written.revision()).isEqualTo(4L);
            assertThat(written.journalSequence()).hasValue(42L);
        });
    }

    // ── The project manager's closing checkpoint (story 333) ────────────────

    @Test
    void theProjectManagersClosingCheckpointHoldsTheLiveProjectEvenWhenItsRevisionIsCheckpointed() throws Exception {
        ProjectManager projects = projectManager(new AutoSaveConfig(INTERVAL, 10, true));
        Recorder recorder = Recorder.on(projects.getCheckpointManager());
        Path folder = projects.createProject("Song", projectDir).projectPath();
        DawProject project = project("Song");
        SaveOutcome.Saved saved = SaveTestSupport.saved(projects, project);
        assertThat(saved.checkpoint()).as("fixture: the save's checkpoint").isPresent();
        assertThat(projects.isCheckpointed(project)).as("the save's checkpoint holds its revision").isTrue();

        Path closing = projects.writeClosingCheckpoint(project).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(closing).isNotEqualTo(saved.checkpoint().get());
        assertThat(Files.readString(closing)).as("a capture of the live project, as the save rendered it")
                .isEqualTo(Files.readString(folder.resolve(ProjectManager.PROJECT_FILE_NAME)));
        assertThat(recorder.events(CheckpointEvent.Written.class))
                .extracting(CheckpointEvent.Written::origin)
                .containsExactly(CheckpointOrigin.AFTER_SAVE, CheckpointOrigin.CLOSING);
    }

    @Test
    void anEditAfterTheLastCheckpointIsNotCheckpointedUntilTheClosingCheckpointHoldsIt() throws Exception {
        ProjectManager projects = projectManager(new AutoSaveConfig(INTERVAL, 10, true));
        projects.createProject("Song", projectDir);
        DawProject project = project("Song");
        SaveTestSupport.saved(projects, project);
        project.createAudioTrack("Discarded bass");
        project.markDirty();

        assertThat(projects.isCheckpointed(project)).as("trigger (ii): the edit is in no checkpoint").isFalse();
        Path closing = projects.writeClosingCheckpoint(project).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(Files.readString(closing)).contains("Discarded bass");
        assertThat(projects.isCheckpointed(project)).isTrue();
    }

    @Test
    void theProjectManagersClosingCheckpointIsWrittenWithAutosaveDisabled() throws Exception {
        ProjectManager projects = projectManager(new AutoSaveConfig(INTERVAL, 10, false));
        projects.createProject("Song", projectDir);
        DawProject project = project("Song");
        assertThat(SaveTestSupport.saved(projects, project).checkpoint())
                .as("fixture: no checkpoint after a save while autosave is disabled (ruling J2)").isEmpty();
        assertThat(projects.isCheckpointed(project)).isFalse();

        Path closing = projects.writeClosingCheckpoint(project).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(closing).exists();
        assertThat(projects.isCheckpointed(project)).isTrue();
    }

    @Test
    void theProjectManagerRefusesAClosingCheckpointWithoutAWritableOpenProject() throws Exception {
        ProjectManager projects = projectManager(new AutoSaveConfig(INTERVAL, 10, true));
        DawProject project = project("Song");
        assertRefused(projects.writeClosingCheckpoint(project), "No writable project is open");
        assertThat(projects.isCheckpointed(project)).isFalse();

        Path folder = projects.createProject("Song", projectDir).projectPath();
        SaveTestSupport.saved(projects, project);
        projects.closeProject().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertRefused(projects.writeClosingCheckpoint(project), "No writable project is open");
        assertThat(projects.isCheckpointed(project)).as("after the close").isFalse();

        ProjectLockManager other = lease(new ProjectLockManager(InstantSource.system(), "mallory", "elsewhere", 9L));
        other.forceAcquire(folder);
        projects.setLockConflictHandler((_, _) -> LockConflictResolution.OPEN_READ_ONLY);
        projects.openProject(folder);
        assertThat(projects.isReadOnly()).as("fixture: a read-only open").isTrue();
        assertRefused(projects.writeClosingCheckpoint(projects.getCurrentDawProject()), "No writable project is open");
        assertThat(checkpointNames(folder)).as("only the save's checkpoint").hasSize(1);
    }

    @Test
    void theProjectManagersClosingCheckpointIsRefusedUnderAStolenLock() throws Exception {
        ProjectManager projects = projectManager(new AutoSaveConfig(INTERVAL, 10, true));
        Path folder = projects.createProject("Song", projectDir).projectPath();
        DawProject project = project("Song");
        SaveTestSupport.saved(projects, project);
        lease(new ProjectLockManager(InstantSource.system(), "mallory", "elsewhere", 9L)).forceAcquire(folder);

        CompletableFuture<Path> closing = projects.writeClosingCheckpoint(project);

        assertThatThrownBy(() -> closing.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(LockStolenException.class);
        assertThat(checkpointNames(folder)).as("only the save's checkpoint").hasSize(1);
    }

    @Test
    void theProjectManagersClosingCheckpointCarriesTheJournalBoundaryCapturedWithIt() throws Exception {
        ProjectManager projects = projectManager(new AutoSaveConfig(INTERVAL, 10, true));
        Recorder recorder = Recorder.on(projects.getCheckpointManager());
        projects.createProject("Song", projectDir);
        projects.getCheckpointManager().observeJournalSequence(() -> 77L);

        projects.writeClosingCheckpoint(project("Song")).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(recorder.events(CheckpointEvent.Written.class)).singleElement()
                .satisfies(written -> assertThat(written.journalSequence()).hasValue(77L));
    }

    private ProjectManager projectManager(AutoSaveConfig config) {
        CheckpointManager checkpoints = manager(config);
        ProjectManager projects = new ProjectManager(checkpoints, null,
                lease(new ProjectLockManager(InstantSource.system(), "alice", "studio", 1L)));
        projectManagers.add(projects);
        return projects;
    }

    private static DawProject project(String name) {
        DawProject project = new DawProject(name, AudioFormat.CD_QUALITY);
        project.createAudioTrack("Vox");
        project.markDirty();
        return project;
    }

    private static Path write(CheckpointManager manager, long revision, CheckpointOrigin origin) throws Exception {
        return manager.writeCheckpoint(document(revision), revision, origin).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static void assertRefused(CompletableFuture<Path> request, String because) {
        assertThat(request).failsWithin(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(request.exceptionNow()).isInstanceOf(IOException.class).hasMessageContaining(because);
    }

    private CheckpointManager manager(AutoSaveConfig config) {
        CheckpointManager manager = CheckpointTestSupport.manager(config, new AtomicFileWriter(), new CountingClock(),
                Files::delete, CheckpointManager.STOP_WAIT);
        managers.add(manager);
        return manager;
    }

    private ProjectLockManager lease(ProjectLockManager lease) {
        leases.add(lease);
        return lease;
    }
}
