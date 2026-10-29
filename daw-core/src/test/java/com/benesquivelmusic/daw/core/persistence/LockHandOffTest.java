package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.SaveTestSupport.Gate;
import com.benesquivelmusic.daw.core.persistence.SaveTestSupport.RecordingMover;
import com.benesquivelmusic.daw.core.project.DawProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.HEAD;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.TIMEOUT_SECONDS;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.await;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.saved;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 333's lock hand-off (Persistence Integrity book §5.4, rejection
 * §9.14; Project Manager book §5.3): closing or replacing a project never
 * waits, and the old folder's {@code .project.lock} is released only after
 * everything this session still owes that folder has ended — a head write
 * already past its claim, a prune delete the checkpoint writer decided within
 * its claim just before {@code stop()}'s bound, and a heartbeat inside its
 * write of the lock file. Each is held in turn as the last to end: the lock
 * stays until it ends, and afterwards nothing in the folder changes and no
 * {@code .project.lock} is left. A named-snapshot copy queued on the save
 * writer before the close is owed work too.
 */
class LockHandOffTest {

    /** The project-manager calls that close or replace the open project. */
    enum Ending { CLOSE, OPEN_ANOTHER, CREATE_ANOTHER }

    /** The work owed in the old folder that the test lets end last. */
    enum LastToEnd { HEAD_WRITE, PRUNE_DELETE, HEARTBEAT }

    private static final InstantSource CLOCK = InstantSource.fixed(Instant.parse("2026-10-28T12:00:00Z"));
    private static final Predicate<Path> LOCK =
            target -> target.getFileName().toString().equals(ProjectLockManager.LOCK_FILE_NAME);

    @TempDir
    Path workspace;

    private final RecordingMover headMover = new RecordingMover();
    private final RecordingMover lockMover = new RecordingMover();
    private final HeldDeleter deleter = new HeldDeleter();
    private final List<Gate> gates = new ArrayList<>();
    private ProjectManager manager;

    @AfterEach
    void releaseEverything() throws Exception {
        gates.forEach(Gate::release);
        if (manager != null) {
            manager.closeProject();
            manager.whenRetiredLeasesReleased().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    static Stream<Arguments> endingsAndLastWork() {
        return Arrays.stream(Ending.values())
                .flatMap(ending -> Arrays.stream(LastToEnd.values()).map(last -> Arguments.of(ending, last)));
    }

    @ParameterizedTest(name = "{0}, {1} ends last")
    @MethodSource("endingsAndLastWork")
    void theOldFoldersLockIsReleasedOnlyAfterEveryWriteOwedThereHasEnded(Ending ending, LastToEnd last)
            throws Exception {
        Path songB = ending == Ending.OPEN_ANOTHER ? anotherProjectOnDisk() : null;
        ProjectLockManager lease = new ProjectLockManager(CLOCK, "alice", "studio", 1L,
                new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, lockMover));
        CheckpointManager checkpoints = new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 1, true),
                new AtomicFileWriter(), InstantSource.system(), deleter, Duration.ofMillis(300));
        manager = SaveTestSupport.manager(checkpoints, lease,
                new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, headMover),
                SaveTestSupport.SHORT_CHECKPOINT_WAIT);
        Path songA = manager.createProject("Song A", workspace).projectPath();
        String songAsLock = lease.currentLock().orElseThrow().lockId();
        DawProject project = project("Song A");
        saved(manager, project);

        // 1. A prune delete, decided within its claim before stop()'s bound: the cap is one checkpoint.
        Gate pruneHeld = hold(deleter.holdNext());
        CompletableFuture<Path> pruning = checkpoints.writeCheckpoint("<daw-project/>", 99L, CheckpointOrigin.USER);
        pruneHeld.awaitReached();
        // 2. A head write past its claim.
        project.createAudioTrack("Bass");
        project.markDirty();
        Gate headHeld = hold(headMover.holdNext(HEAD));
        CompletableFuture<SaveOutcome> heldSave = manager.save(project);
        headHeld.awaitReached();
        // 3. A heartbeat inside its write of the lock file.
        Gate heartbeatHeld = hold(lockMover.holdNext(LOCK));
        CompletableFuture<Boolean> heartbeat = new CompletableFuture<>();
        Thread.ofPlatform().name("test-heartbeat").daemon().start(() -> heartbeat.complete(lease.heartbeat()));
        heartbeatHeld.awaitReached();

        // The held work waits up to the whole timeout: a call that waited for it would not return within half.
        CompletableFuture.runAsync(() -> end(ending, songB)).get(TIMEOUT_SECONDS / 2, TimeUnit.SECONDS);

        assertThat(lockIdIn(songA)).as("Song A's lock stays while its owed work is held").hasValue(songAsLock);
        assertThat(manager.getLockManager()).as("the manager carries on with another lease").isNotSameAs(lease);
        for (LastToEnd other : LastToEnd.values()) {
            if (other != last) {
                end(other, heldSave, pruning, heartbeat, headHeld, pruneHeld, heartbeatHeld);
            }
        }
        assertThatThrownBy(() -> manager.whenRetiredLeasesReleased().get(300, TimeUnit.MILLISECONDS))
                .as("the release still waits for the " + last)
                .isInstanceOf(TimeoutException.class);
        assertThat(lockIdIn(songA)).as("Song A's lock stays until the " + last + " has ended").hasValue(songAsLock);

        end(last, heldSave, pruning, heartbeat, headHeld, pruneHeld, heartbeatHeld);
        manager.whenRetiredLeasesReleased().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).as("released once everything owed ended")
                .doesNotExist();
        Map<String, FileTime> released = listing(songA);
        assertThat(new CountDownLatch(1).await(500, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(listing(songA)).as("nothing of this session changes the folder after its lock is gone")
                .isEqualTo(released);
    }

    /**
     * Ruling FEAS-N12: a named-snapshot copy queued on the save writer before
     * the close or the replacement copies {@code project.daw} into the
     * folder's {@code snapshots/}, so it is owed work too — the lock stays
     * until it ran.
     */
    @ParameterizedTest
    @EnumSource(Ending.class)
    void aNamedSnapshotCopyQueuedBeforeTheCloseHoldsTheRelease(Ending ending) throws Exception {
        Path songB = ending == Ending.OPEN_ANOTHER ? anotherProjectOnDisk() : null;
        manager = new ProjectManager(new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true)), null,
                new ProjectLockManager(CLOCK, "alice", "studio", 1L));
        Path songA = manager.createProject("Song A", workspace).projectPath();
        CountDownLatch copying = new CountDownLatch(1);
        CountDownLatch copied = new CountDownLatch(1);
        CompletableFuture<Path> snapshot = manager.runAfterQueuedSaves(() -> {
            copying.countDown();
            if (!copied.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("the test never let the copy finish");
            }
            Path snapshots = Files.createDirectories(songA.resolve("snapshots"));
            return Files.copy(songA.resolve(ProjectManager.PROJECT_FILE_NAME), snapshots.resolve("Before.daw"));
        });
        assertThat(copying.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("the copy runs on the save writer").isTrue();

        end(ending, songB);

        assertThatThrownBy(() -> manager.whenRetiredLeasesReleased().get(300, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
        assertThat(songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).as("the lock stays while the copy runs")
                .exists();
        copied.countDown();
        assertThat(await(snapshot)).exists();
        manager.whenRetiredLeasesReleased().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).doesNotExist();
    }

    /**
     * Ruling OQ-1 #3 (story 333): work the caller still has writing into the
     * folder — its journal's seal or drain — is owed too; the lock stays until
     * it ends, and an end that failed counts as ended.
     */
    @Test
    void aJournalEndTheCallerStillOwesHoldsTheReleaseAndAFailedEndCountsAsEnded() throws Exception {
        manager = new ProjectManager(new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true)), null,
                new ProjectLockManager(CLOCK, "alice", "studio", 1L));
        Path songA = manager.createProject("Song A", workspace).projectPath();
        CompletableFuture<Void> journalEnded = new CompletableFuture<>();

        CompletableFuture<Void> released = manager.closeProject(journalEnded);

        assertThatThrownBy(() -> released.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        assertThat(songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).as("the lock stays while the seal runs")
                .exists();
        journalEnded.completeExceptionally(new IOException("injected seal failure"));
        assertThat(released.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("never exceptional").isNull();
        assertThat(songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).doesNotExist();
    }

    /**
     * Stage 2's review S1: a caller's stage that does not interoperate —
     * whose {@code toCompletableFuture()} throws, as its contract allows — is
     * adapted before the close changes anything, so the close still returns,
     * the project is closed, and the lock stays until that work ends.
     */
    @Test
    void aCallersStageThatDoesNotInteroperateNeverLeavesAHalfClosedProject() throws Exception {
        manager = new ProjectManager(new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true)), null,
                new ProjectLockManager(CLOCK, "alice", "studio", 1L));
        Path songA = manager.createProject("Song A", workspace).projectPath();
        CompletableFuture<Void> sealing = new CompletableFuture<>() {
            @Override
            public CompletableFuture<Void> toCompletableFuture() {
                throw new UnsupportedOperationException("this stage does not interoperate");
            }
        };

        CompletableFuture<Void> released = manager.closeProject(sealing);

        assertThat(manager.getCurrentProject()).as("the project is closed").isNull();
        assertThatThrownBy(() -> released.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        assertThat(songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).as("the lock stays while the seal runs")
                .exists();
        sealing.complete(null);
        assertThat(released.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNull();
        assertThat(songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).doesNotExist();
    }

    private void end(Ending ending, Path songB) {
        try {
            switch (ending) {
                case CLOSE -> manager.closeProject();
                case OPEN_ANOTHER -> manager.openProject(songB);
                case CREATE_ANOTHER -> manager.createProject("Song C", workspace);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** Lets {@code work} end, and waits until it has. */
    private static void end(LastToEnd work, CompletableFuture<SaveOutcome> heldSave, CompletableFuture<Path> pruning,
                            CompletableFuture<Boolean> heartbeat, Gate headHeld, Gate pruneHeld, Gate heartbeatHeld) {
        switch (work) {
            case HEAD_WRITE -> {
                headHeld.release();
                assertThat(await(heldSave)).as("the head write that had claimed its target finished")
                        .isInstanceOf(SaveOutcome.Saved.class);
            }
            case PRUNE_DELETE -> {
                pruneHeld.release();
                assertThat(await(pruning)).as("the checkpoint whose prune was held").exists();
            }
            case HEARTBEAT -> {
                heartbeatHeld.release();
                assertThat(await(heartbeat)).as("the heartbeat refreshed the lock it held").isTrue();
            }
        }
    }

    private Gate hold(Gate gate) {
        gates.add(gate);
        return gate;
    }

    private static Optional<String> lockIdIn(Path folder) {
        return ProjectLockManager.peekLock(folder).map(ProjectLock::lockId);
    }

    /** Every file under {@code folder}, by relative path, with its modification time. */
    private static Map<String, FileTime> listing(Path folder) throws IOException {
        Map<String, FileTime> files = new TreeMap<>();
        try (Stream<Path> entries = Files.walk(folder)) {
            for (Path entry : entries.filter(Files::isRegularFile).toList()) {
                files.put(folder.relativize(entry).toString(), Files.getLastModifiedTime(entry));
            }
        }
        return files;
    }

    /** Song B, made and released by a manager of its own; returns its folder. */
    private Path anotherProjectOnDisk() throws Exception {
        ProjectManager other = new ProjectManager(new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10,
                true)), null, new ProjectLockManager(CLOCK, "bob", "studio", 2L));
        Path songB = other.createProject("Song B", Files.createDirectories(workspace.resolve("elsewhere")))
                .projectPath();
        saved(other, project("Song B"));
        other.closeProject().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return songB;
    }

    private static DawProject project(String name) {
        DawProject project = new DawProject(name, AudioFormat.CD_QUALITY);
        project.createAudioTrack("Vox");
        project.markDirty();
        return project;
    }

    /** Deletes through the file system; the next delete can be held at a {@link Gate}. */
    private static final class HeldDeleter implements CheckpointManager.FileDeleter {

        private Gate gate;

        synchronized Gate holdNext() {
            gate = new Gate();
            return gate;
        }

        private synchronized Gate take() {
            Gate held = gate;
            gate = null;
            return held;
        }

        @Override
        public void delete(Path file) throws IOException {
            Gate held = take();
            if (held != null) {
                held.pass();
            }
            Files.delete(file);
        }
    }
}
