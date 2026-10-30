package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PreparedProjectLeaseTest {
    @TempDir
    Path directory;

    private final List<ProjectManager> managers = new ArrayList<>();

    @AfterEach
    void releaseProjects() {
        // Each release reads and deletes a lock file: let it end before the temporary folder is deleted.
        managers.forEach(manager -> SaveTestSupport.await(manager.closeProject()));
    }

    @Test
    void cancelledDirectOpenKeepsTheOutgoingLeaseAndHeartbeat() throws Exception {
        ProjectManager outgoing = manager();
        Path first = outgoing.createProject("first", directory).projectPath();
        ProjectManager other = manager();
        Path second = other.createProject("second", directory).projectPath();
        ProjectLockManager lease = outgoing.getLockManager();
        ProjectLock captured = lease.currentLock().orElseThrow();
        ScheduledExecutorService heartbeat = heartbeatExecutor(lease);

        assertThatThrownBy(() -> outgoing.openProject(second)).isInstanceOf(ProjectLockedException.class);

        assertThat(outgoing.getCurrentProject().projectPath()).isEqualTo(first);
        assertThat(outgoing.getLockManager()).isSameAs(lease);
        assertThat(ProjectLockManager.peekLock(first)).contains(captured);
        assertThat(heartbeat.isShutdown()).isFalse();
        assertThat(lease.heartbeat()).isTrue();
        other.getLockManager().verifyHeld();
    }

    @Test
    void failedParseDisposesOnlyTheTargetLease() throws Exception {
        ProjectManager outgoing = manager();
        Path first = outgoing.createProject("first", directory).projectPath();
        ProjectLockManager lease = outgoing.getLockManager();
        Path malformed = Files.createDirectory(directory.resolve("malformed"));
        Files.writeString(malformed.resolve("project.daw"), "<daw-project invalid");
        AtomicReference<ScheduledExecutorService> targetHeartbeat = new AtomicReference<>();
        outgoing.setRecoveryGate((_, _, target) -> targetHeartbeat.set(heartbeatExecutor(target)));

        assertThatThrownBy(() -> outgoing.openProject(malformed)).isInstanceOf(IOException.class);

        assertThat(outgoing.getCurrentProject().projectPath()).isEqualTo(first);
        assertThat(outgoing.getLockManager()).isSameAs(lease);
        lease.verifyHeld();
        assertThat(ProjectLockManager.peekLock(malformed)).isEmpty();
        assertThat(targetHeartbeat.get().isShutdown()).isTrue();
    }

    @Test
    void failedRecentProjectPublicationKeepsTheOutgoingLease() throws Exception {
        Preferences preferences = Preferences.userRoot().node("prepared-project-test-" + UUID.randomUUID());
        ProjectManager outgoing = new ProjectManager(new CheckpointManager(
                new AutoSaveConfig(Duration.ofHours(1), 10, true)), new RecentProjectsStore(preferences));
        managers.add(outgoing);
        try {
            Path first = outgoing.createProject("first", directory).projectPath();
            ProjectLockManager lease = outgoing.getLockManager();
            Path target = unlockedProject("target");
            preferences.removeNode();

            assertThatThrownBy(() -> outgoing.openProject(target)).isInstanceOf(IllegalStateException.class);

            assertThat(outgoing.getCurrentProject().projectPath()).isEqualTo(first);
            assertThat(outgoing.getLockManager()).isSameAs(lease);
            lease.verifyHeld();
            assertThat(ProjectLockManager.peekLock(target)).isEmpty();
        } finally {
            if (preferences.nodeExists("")) preferences.removeNode();
        }
    }

    @Test
    void cancelledRecoveryDisposesItsHeartbeat() throws Exception {
        ProjectManager outgoing = manager();
        Path target = unlockedProject("target");
        AtomicReference<ScheduledExecutorService> heartbeat = new AtomicReference<>();
        outgoing.setRecoveryGate((_, _, lock) -> {
            heartbeat.set(heartbeatExecutor(lock));
            throw new IOException("Recovery cancelled");
        });

        assertThatThrownBy(() -> outgoing.prepareOpen(target)).isInstanceOf(IOException.class);

        assertThat(heartbeat.get().isShutdown()).isTrue();
        assertThat(ProjectLockManager.peekLock(target)).isEmpty();
    }

    @Test
    void closingAnUnappliedPreparationDisposesTheTargetScheduler() throws Exception {
        ProjectManager outgoing = manager();
        Path target = unlockedProject("target");
        ProjectManager.PreparedOpen prepared = outgoing.prepareOpen(target);
        ScheduledExecutorService heartbeat = heartbeatExecutor(prepared.lock());

        prepared.close();

        assertThat(heartbeat.isShutdown()).isTrue();
        assertThat(ProjectLockManager.peekLock(target)).isEmpty();
    }

    @Test
    void acceptedPreparationDisposesTheRetiredSchedulerAndTransfersOwnership() throws Exception {
        ProjectManager outgoing = manager();
        Path first = outgoing.createProject("first", directory).projectPath();
        ScheduledExecutorService retiredHeartbeat = heartbeatExecutor(outgoing.getLockManager());
        Path target = unlockedProject("target");
        ProjectManager.PreparedOpen prepared = outgoing.prepareOpen(target);
        ScheduledExecutorService targetHeartbeat = heartbeatExecutor(prepared.lock());

        outgoing.openProject(target, prepared);
        prepared.close();
        // The outgoing lease is retired: released off the caller once nothing is owed in its folder (story 333).
        outgoing.whenRetiredLeasesReleased().get(10, TimeUnit.SECONDS);

        assertThat(retiredHeartbeat.isShutdown()).isTrue();
        assertThat(ProjectLockManager.peekLock(first)).isEmpty();
        assertThat(outgoing.getLockManager()).isSameAs(prepared.lock());
        assertThat(targetHeartbeat.isShutdown()).isFalse();
        outgoing.getLockManager().verifyHeld();
        outgoing.closeProject().join();
        assertThat(targetHeartbeat.isShutdown()).isTrue();
    }

    /**
     * A target lease taken over after its preparation, before the open adopts
     * it (FEAS-S5): the adopt — after the parse and the Recent Projects
     * entry — is the open's last step that can fail, and it comes before the
     * fence, so the outgoing project stays open, keeps its lease and can
     * still be saved; nothing of it is retired (story 333).
     */
    @Test
    void aTargetLeaseStolenBeforeTheAdoptLeavesTheOutgoingProjectSaveable() throws Exception {
        ProjectManager outgoing = manager();
        Path first = outgoing.createProject("first", directory).projectPath();
        ProjectLockManager lease = outgoing.getLockManager();
        Path target = unlockedProject("target");
        ProjectManager.PreparedOpen prepared = outgoing.prepareOpen(target);
        ProjectLockManager thief = new ProjectLockManager(java.time.InstantSource.system(), "mallory", "elsewhere",
                99L);
        thief.forceAcquire(target);

        assertThatThrownBy(() -> outgoing.openProject(target, prepared)).isInstanceOf(LockStolenException.class);

        assertThat(outgoing.getCurrentProject().projectPath()).isEqualTo(first);
        assertThat(outgoing.getLockManager()).isSameAs(lease);
        lease.verifyHeld();
        assertThat(outgoing.whenRetiredLeasesReleased()).as("nothing was retired").isCompleted();
        DawProject project = new DawProject("first", AudioFormat.CD_QUALITY);
        assertThat(SaveTestSupport.saveAndWait(outgoing, project)).as("the outgoing project is still saveable")
                .isInstanceOf(SaveOutcome.Saved.class);
        thief.close();
    }

    @Test
    void lockedAccessNegotiatesWithoutRunningTheRecoveryGate() throws IOException {
        ProjectManager outgoing = manager();
        Path target = unlockedProject("target");
        outgoing.setRecoveryGate((_, _, _) -> { throw new AssertionError("Discard must not run recovery"); });

        try (ProjectManager.PreparedOpen prepared = outgoing.prepareLockedAccess(target)) {
            assertThat(prepared.readOnly()).isFalse();
            prepared.lock().verifyHeld();
        }
        assertThat(ProjectLockManager.peekLock(target)).isEmpty();
    }

    private ProjectManager manager() {
        ProjectManager manager = new ProjectManager(new CheckpointManager(
                new AutoSaveConfig(Duration.ofHours(1), 10, true)));
        managers.add(manager);
        return manager;
    }

    private Path unlockedProject(String name) throws IOException {
        ProjectManager creator = manager();
        Path target = creator.createProject(name, directory).projectPath();
        creator.closeProject().join();
        return target;
    }

    private static ScheduledExecutorService heartbeatExecutor(ProjectLockManager lock) {
        try {
            Field field = ProjectLockManager.class.getDeclaredField("ownedExecutor");
            field.setAccessible(true);
            return (ScheduledExecutorService) field.get(lock);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
