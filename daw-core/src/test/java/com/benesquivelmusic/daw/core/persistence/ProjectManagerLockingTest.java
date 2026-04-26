package com.benesquivelmusic.daw.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests covering the {@link ProjectManager} + {@link ProjectLockManager}
 * collaboration described in the project-lock issue:
 * <ul>
 *   <li>two mock sessions opening the same project produce a conflict dialog;</li>
 *   <li>stale-lock detection triggers after a simulated clock advance;</li>
 *   <li>takeover leaves a clear audit trail in the project log.</li>
 * </ul>
 */
class ProjectManagerLockingTest {

    @TempDir
    Path tempDir;

    private static final class FakeClock implements InstantSource {
        Instant now;
        FakeClock(Instant start) { this.now = start; }
        @Override public Instant instant() { return now; }
        void advance(Duration by) { now = now.plus(by); }
    }

    private static ProjectManager newManager(ProjectLockManager lockManager) {
        AutoSaveConfig config = new AutoSaveConfig(Duration.ofHours(1), 10, true);
        CheckpointManager checkpoints = new CheckpointManager(config);
        return new ProjectManager(checkpoints, null, lockManager);
    }

    @Test
    void twoSessionsOpeningSameProjectInvokeConflictHandlerOnSecond() throws IOException {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        ProjectManager session1 = newManager(new ProjectLockManager(clock, "alice", "studio", 1L));
        ProjectManager session2 = newManager(new ProjectLockManager(clock, "bob", "edit-suite", 2L));

        ProjectMetadata md = session1.createProject("Shared Song", tempDir);

        AtomicReference<ProjectLock> seenHolder = new AtomicReference<>();
        AtomicReference<Boolean> seenStale = new AtomicReference<>();
        session2.setLockConflictHandler((existing, stale) -> {
            seenHolder.set(existing);
            seenStale.set(stale);
            return LockConflictResolution.CANCEL;
        });

        assertThatThrownBy(() -> session2.openProject(md.projectPath()))
                .isInstanceOf(ProjectLockedException.class);
        assertThat(seenHolder.get()).isNotNull();
        assertThat(seenHolder.get().user()).isEqualTo("alice");
        assertThat(seenHolder.get().hostname()).isEqualTo("studio");
        assertThat(seenStale.get()).isFalse();
    }

    @Test
    void staleLockAllowsTakeoverWithAuditTrailEntry() throws IOException {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        ProjectManager session1 = newManager(new ProjectLockManager(clock, "alice", "studio", 1L));
        ProjectManager session2 = newManager(new ProjectLockManager(clock, "bob", "edit-suite", 2L));

        ProjectMetadata md = session1.createProject("Shared Song", tempDir);

        // Session 1 silently dies — we just advance the clock past the staleness threshold.
        clock.advance(Duration.ofMinutes(11));

        AtomicReference<Boolean> sawStale = new AtomicReference<>();
        session2.setLockConflictHandler((existing, stale) -> {
            sawStale.set(stale);
            return LockConflictResolution.TAKE_OVER;
        });

        ProjectMetadata reopened = session2.openProject(md.projectPath());
        assertThat(reopened.name()).isEqualTo("Shared Song");
        assertThat(sawStale.get()).isTrue();
        assertThat(session2.getLockManager().status()).isEqualTo(LockStatus.HELD);

        ProjectLog log = new ProjectLog(md.projectPath());
        List<String> lines = log.readAll();
        assertThat(lines).anyMatch(l -> l.contains("takeover")
                && l.contains("bob@edit-suite") && l.contains("stale"));
    }

    @Test
    void readOnlyOpenLeavesOriginalLockIntactAndBlocksSave() throws IOException {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        ProjectManager session1 = newManager(new ProjectLockManager(clock, "alice", "studio", 1L));
        ProjectManager session2 = newManager(new ProjectLockManager(clock, "bob", "edit-suite", 2L));

        ProjectMetadata md = session1.createProject("Song", tempDir);
        session2.setLockConflictHandler((e, s) -> LockConflictResolution.OPEN_READ_ONLY);

        session2.openProject(md.projectPath());
        assertThat(session2.isReadOnly()).isTrue();
        assertThat(session2.getLockManager().status()).isEqualTo(LockStatus.READ_ONLY);

        // Save is forbidden in read-only mode — this is the "use Save As" hint.
        assertThatThrownBy(session2::saveProject)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("read-only");

        // Original holder's lock is still on disk and untouched.
        ProjectLock onDisk = ProjectLockManager.readLock(
                md.projectPath().resolve(ProjectLockManager.LOCK_FILE_NAME));
        assertThat(onDisk.user()).isEqualTo("alice");
    }

    @Test
    void saveAfterStolenLockThrowsLockStolenException() throws IOException {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        ProjectManager session1 = newManager(new ProjectLockManager(clock, "alice", "studio", 1L));
        ProjectLockManager thief = new ProjectLockManager(clock, "bob", "edit-suite", 2L);

        ProjectMetadata md = session1.createProject("Song", tempDir);
        thief.forceAcquire(md.projectPath());

        assertThatThrownBy(session1::saveProject).isInstanceOf(LockStolenException.class);
    }

    @Test
    void closeProjectReleasesLockSidecar() throws IOException {
        ProjectManager session1 = newManager(new ProjectLockManager(InstantSource.system(),
                "alice", "studio", 1L));
        ProjectMetadata md = session1.createProject("Song", tempDir);
        assertThat(md.projectPath().resolve(ProjectLockManager.LOCK_FILE_NAME)).exists();

        session1.closeProject();

        assertThat(md.projectPath().resolve(ProjectLockManager.LOCK_FILE_NAME)).doesNotExist();
    }
}
