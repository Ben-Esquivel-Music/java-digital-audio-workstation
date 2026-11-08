package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.SaveTestSupport.Gate;
import com.benesquivelmusic.daw.core.persistence.SaveTestSupport.RecordingMover;
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.CHECKPOINT;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.HEAD;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.TIMEOUT_SECONDS;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.await;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.saved;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The exit protocol's non-blocking facts about the writers and the lock
 * (story 333; Persistence Integrity book §6.1, ruling M1):
 * {@link ProjectManager#whenWritersIdle()} completes once the saves queued
 * before it and every checkpoint job — of any generation, an ended one
 * included — are done; {@link ProjectManager#whenOpenGenerationIdle()} waits
 * for the open project's checkpoint generation only;
 * {@link ProjectManager#whenLockChecked()} reads the lock off the caller and
 * reports a steal without ever failing. None of them blocks its caller.
 */
class WritersIdleTest {

    private static final Duration HELD = Duration.ofMillis(300);

    @TempDir
    Path workspace;

    private final RecordingMover headMover = new RecordingMover();
    private final RecordingMover checkpointMover = new RecordingMover();
    private final List<Gate> gates = new ArrayList<>();
    private final List<Thread> lockReaders = new CopyOnWriteArrayList<>();
    private volatile IOException lockReadFailure;
    private ProjectManager manager;

    @AfterEach
    void releaseEverything() throws Exception {
        gates.forEach(Gate::release);
        if (manager != null) {
            manager.closeProject();
            manager.whenRetiredLeasesReleased().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Test
    void theWritersAreIdleOnceTheHeldSaveAndThenTheCheckpointOfAnEndedGenerationHaveLanded() throws Exception {
        openSongA();
        Gate checkpointHeld = hold(checkpointMover.holdNext(CHECKPOINT));
        CompletableFuture<Path> userCheckpoint = manager.getCheckpointManager()
                .writeCheckpoint("<daw-project/>", 7L, CheckpointOrigin.USER);
        checkpointHeld.awaitReached();
        Gate headHeld = hold(headMover.holdNext(HEAD));
        CompletableFuture<SaveOutcome> save = manager.save(project("Song A"));
        headHeld.awaitReached();
        manager.closeProject();

        CompletableFuture<Void> idle = manager.whenWritersIdle();

        assertStillWaiting(idle, "the save is held");
        headHeld.release();
        assertThat(await(save)).isInstanceOf(SaveOutcome.Saved.class);
        assertStillWaiting(idle, "the checkpoint of the ended generation is still held");
        checkpointHeld.release();
        assertThat(await(userCheckpoint)).exists();
        await(idle);
        assertThat(manager.getCheckpointManager().whenNoJobIsHeld()).isCompleted();
    }

    @Test
    void theWritersAreIdleWhenACheckpointHeldInThemFailsAndWithNothingBeingWritten() throws Exception {
        openSongA();
        await(manager.whenWritersIdle());
        IOException crash = new IOException("injected checkpoint failure");
        checkpointMover.failWith(CHECKPOINT, crash);
        Gate checkpointHeld = hold(checkpointMover.holdNext(CHECKPOINT));
        CompletableFuture<Path> failing = manager.getCheckpointManager()
                .writeCheckpoint("<daw-project/>", 8L, CheckpointOrigin.USER);
        checkpointHeld.awaitReached();

        CompletableFuture<Void> idle = manager.whenWritersIdle();

        assertStillWaiting(idle, "the checkpoint is held");
        checkpointHeld.release();
        assertThatThrownBy(() -> failing.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).hasCauseReference(crash);
        assertThat(idle.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("never exceptional").isNull();
    }

    @Test
    void theOpenGenerationIsIdleWithoutWaitingForAnotherFoldersHeldCheckpoint() throws Exception {
        assertThat(new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT)).whenOpenGenerationIdle())
                .as("no project open: idle at once").isCompleted();
        openSongA();
        Gate checkpointHeld = hold(checkpointMover.holdNext(CHECKPOINT));
        CompletableFuture<Path> held = manager.getCheckpointManager()
                .writeCheckpoint("<daw-project/>", 9L, CheckpointOrigin.USER);
        checkpointHeld.awaitReached();

        CompletableFuture<Void> songAIdle = manager.whenOpenGenerationIdle();
        assertStillWaiting(songAIdle, "Song A's generation holds the write");

        manager.createProject("Song B", workspace);
        await(manager.whenOpenGenerationIdle());
        assertThat(manager.getCheckpointManager().whenNoJobIsHeld())
                .as("fixture: Song A's write is still held").isNotDone();
        assertThat(songAIdle).as("Song A's generation still holds its write").isNotDone();

        checkpointHeld.release();
        assertThat(await(held)).exists();
        await(songAIdle);
        await(manager.getCheckpointManager().whenNoJobIsHeld());
    }

    @Test
    void theLockCheckReadsTheLockOffTheCallerAndReportsNothingWhileItIsOurs() throws Exception {
        openSongA();
        lockReaders.clear();

        Optional<LockStolenException> checked = manager.whenLockChecked().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(checked).isEmpty();
        assertThat(lockReaders).isNotEmpty()
                .allSatisfy(reader -> assertThat(reader.getName()).isEqualTo("daw-lock-check"));
    }

    @Test
    void theLockCheckReportsAnotherSessionsLockAsTheSteal() throws Exception {
        Path songA = openSongA();
        ProjectLockManager mallory = new ProjectLockManager(InstantSource.system(), "mallory", "elsewhere", 99L);
        ProjectLock theirs = mallory.forceAcquire(songA);

        Optional<LockStolenException> checked = manager.whenLockChecked().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(checked).hasValueSatisfying(stolen ->
                assertThat(stolen.currentHolder().lockId()).isEqualTo(theirs.lockId()));
        mallory.close();
    }

    @Test
    void theLockCheckReportsAMissingLockFileAsTheSteal() throws Exception {
        Path songA = openSongA();
        Files.delete(songA.resolve(ProjectLockManager.LOCK_FILE_NAME));

        Optional<LockStolenException> checked = manager.whenLockChecked().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(checked).hasValueSatisfying(stolen -> assertThat(stolen.currentHolder()).isNull());
    }

    @Test
    void theLockCheckReportsNothingWithNoLockHeldAndAWarningWhenTheLockCannotBeRead() throws Exception {
        manager = manager();
        assertThat(manager.whenLockChecked().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("nothing open").isEmpty();
        openSongA();
        IOException unreadable = new IOException("injected unreadable lock");
        lockReadFailure = unreadable;
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Handler recorder = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger log = Logger.getLogger(ProjectManager.class.getName());
        log.addHandler(recorder);
        try {
            assertThat(manager.whenLockChecked().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEmpty();
        } finally {
            log.removeHandler(recorder);
            lockReadFailure = null;
        }
        assertThat(warnings).singleElement().satisfies(warning -> {
            assertThat(warning.getMessage()).isEqualTo("The project lock could not be checked; it is taken as"
                    + " still ours");
            assertThat(warning.getThrown()).isSameAs(unreadable);
        });
    }

    /** Stage 2's review N7: a read-only open holds no lock, so there is nothing to lose and nothing reported. */
    @Test
    void theLockCheckReportsNothingForAReadOnlyOpen() throws Exception {
        Path songA = openSongA();
        manager.closeProject().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        ProjectLockManager mallory = new ProjectLockManager(InstantSource.system(), "mallory", "elsewhere", 99L);
        mallory.forceAcquire(songA);
        manager.setLockConflictHandler((lock, stale) -> LockConflictResolution.OPEN_READ_ONLY);
        try {
            manager.openProject(songA);
            assertThat(manager.isReadOnly()).as("fixture: opened read-only").isTrue();

            assertThat(manager.whenLockChecked().get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("another session's lock is no steal for a session that never held it").isEmpty();
        } finally {
            mallory.close();
        }
    }

    private Path openSongA() throws Exception {
        if (manager == null) {
            manager = manager();
        }
        Path songA = manager.createProject("Song A", workspace).projectPath();
        assertThat(manager.getCheckpointManager().awaitWriterIdle(Duration.ofSeconds(TIMEOUT_SECONDS)))
                .as("fixture: Song A's checkpoints were seeded").isTrue();
        saved(manager, project("Song A"));
        // Under load the save's own checkpoint can outlast the save's short wait for it (ruling J3); a hold armed
        // next would then catch that checkpoint instead of the test's. The fixture waits for it to land.
        assertThat(manager.getCheckpointManager().awaitWriterIdle(Duration.ofSeconds(TIMEOUT_SECONDS)))
                .as("fixture: the save's checkpoint landed").isTrue();
        return songA;
    }

    private ProjectManager manager() {
        ProjectLockManager lease = new ProjectLockManager(InstantSource.fixed(Instant.parse("2026-10-28T12:00:00Z")),
                "alice", "studio", 1L, new AtomicFileWriter(), lockFile -> {
                    lockReaders.add(Thread.currentThread());
                    IOException failure = lockReadFailure;
                    if (failure != null) {
                        throw failure;
                    }
                    return Files.readString(lockFile);
                });
        CheckpointManager checkpoints = new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true),
                new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, checkpointMover));
        return SaveTestSupport.manager(checkpoints, lease,
                new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, headMover),
                SaveTestSupport.SHORT_CHECKPOINT_WAIT);
    }

    private static void assertStillWaiting(CompletableFuture<Void> stage, String because) {
        assertThatThrownBy(() -> stage.get(HELD.toMillis(), TimeUnit.MILLISECONDS)).as(because)
                .isInstanceOf(TimeoutException.class);
    }

    private Gate hold(Gate gate) {
        gates.add(gate);
        return gate;
    }

    private static DawProject project(String name) {
        DawProject project = new DawProject(name, AudioFormat.CD_QUALITY);
        project.createAudioTrack("Vox");
        project.markDirty();
        return project;
    }
}
