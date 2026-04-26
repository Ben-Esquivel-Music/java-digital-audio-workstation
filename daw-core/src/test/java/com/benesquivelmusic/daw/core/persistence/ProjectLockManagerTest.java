package com.benesquivelmusic.daw.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ProjectLockManagerTest {

    @TempDir
    Path projectDir;

    /** A controllable {@link InstantSource} so tests can advance time deterministically. */
    private static final class FakeClock implements InstantSource {
        private Instant now;
        FakeClock(Instant start) { this.now = start; }
        @Override public Instant instant() { return now; }
        void advance(Duration by) { now = now.plus(by); }
    }

    @Test
    void shouldAcquireLockOnFreshDirectory() throws IOException {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        ProjectLockManager mgr = new ProjectLockManager(clock, "alice", "studio-mac", 1234L);

        ProjectLockManager.AcquisitionResult result = mgr.tryAcquire(projectDir);

        assertThat(result.wasAcquired()).isTrue();
        assertThat(result.acquiredLock().user()).isEqualTo("alice");
        assertThat(result.acquiredLock().hostname()).isEqualTo("studio-mac");
        assertThat(result.acquiredLock().pid()).isEqualTo(1234L);
        assertThat(projectDir.resolve(ProjectLockManager.LOCK_FILE_NAME)).exists();
        assertThat(mgr.status()).isEqualTo(LockStatus.HELD);
    }

    @Test
    void shouldDetectExistingLockFromSecondSession() throws IOException {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        ProjectLockManager session1 = new ProjectLockManager(clock, "alice", "studio-mac", 1L);
        ProjectLockManager session2 = new ProjectLockManager(clock, "bob", "edit-suite", 2L);

        ProjectLockManager.AcquisitionResult r1 = session1.tryAcquire(projectDir);
        ProjectLockManager.AcquisitionResult r2 = session2.tryAcquire(projectDir);

        assertThat(r1.wasAcquired()).isTrue();
        assertThat(r2.wasAcquired()).isFalse();
        assertThat(r2.existingLock().user()).isEqualTo("alice");
        assertThat(r2.stale()).isFalse();
    }

    @Test
    void shouldDetectStaleLockAfterTenMinutes() throws IOException {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        ProjectLockManager session1 = new ProjectLockManager(clock, "alice", "studio-mac", 1L);
        session1.tryAcquire(projectDir);

        // Advance past the 10-minute stale threshold without a heartbeat.
        clock.advance(Duration.ofMinutes(11));

        ProjectLockManager session2 = new ProjectLockManager(clock, "bob", "edit-suite", 2L);
        ProjectLockManager.AcquisitionResult r2 = session2.tryAcquire(projectDir);

        assertThat(r2.wasAcquired()).isFalse();
        assertThat(r2.stale()).isTrue();
    }

    @Test
    void heartbeatShouldKeepLockFresh() throws IOException {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        ProjectLockManager mgr = new ProjectLockManager(clock, "alice", "host", 1L);
        mgr.tryAcquire(projectDir);

        clock.advance(Duration.ofMinutes(9));
        assertThat(mgr.heartbeat()).isTrue();
        clock.advance(Duration.ofMinutes(9));

        ProjectLockManager.AcquisitionResult r2 =
                new ProjectLockManager(clock, "bob", "h2", 2L).tryAcquire(projectDir);
        assertThat(r2.stale()).isFalse();
    }

    @Test
    void forceAcquireShouldStealAndAuditTrail() throws IOException {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        ProjectLockManager session1 = new ProjectLockManager(clock, "alice", "studio-mac", 1L);
        ProjectLockManager session2 = new ProjectLockManager(clock, "bob", "edit-suite", 2L);

        session1.tryAcquire(projectDir);
        session2.forceAcquire(projectDir);

        // Session 2 now owns the lock.
        assertThat(session2.status()).isEqualTo(LockStatus.HELD);
        assertThat(session2.currentLock()).isPresent()
                .get().extracting(ProjectLock::user).isEqualTo("bob");

        // Session 1 detects theft on next verify.
        assertThatThrownBy(session1::verifyOurs).isInstanceOf(LockStolenException.class);
        assertThat(session1.status()).isEqualTo(LockStatus.STOLEN);

        // Audit trail in project.log mentions the takeover.
        ProjectLog log = new ProjectLog(projectDir);
        List<String> lines = log.readAll();
        assertThat(lines).anyMatch(l -> l.contains("takeover")
                && l.contains("bob@edit-suite") && l.contains("alice@studio-mac"));
    }

    @Test
    void verifyOursShouldDetectDeletedLock() throws IOException {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        ProjectLockManager mgr = new ProjectLockManager(clock, "alice", "h", 1L);
        mgr.tryAcquire(projectDir);

        Files.deleteIfExists(projectDir.resolve(ProjectLockManager.LOCK_FILE_NAME));

        assertThatThrownBy(mgr::verifyOurs).isInstanceOf(LockStolenException.class);
        assertThat(mgr.status()).isEqualTo(LockStatus.STOLEN);
    }

    @Test
    void releaseShouldDeleteLockFile() throws IOException {
        ProjectLockManager mgr = new ProjectLockManager(InstantSource.system(), "alice", "h", 1L);
        mgr.tryAcquire(projectDir);
        assertThat(projectDir.resolve(ProjectLockManager.LOCK_FILE_NAME)).exists();

        mgr.release();

        assertThat(projectDir.resolve(ProjectLockManager.LOCK_FILE_NAME)).doesNotExist();
        assertThat(mgr.status()).isEqualTo(LockStatus.NONE);
    }

    @Test
    void releaseShouldNotDeleteLockBelongingToAnotherSession() throws IOException {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        ProjectLockManager session1 = new ProjectLockManager(clock, "alice", "h1", 1L);
        ProjectLockManager session2 = new ProjectLockManager(clock, "bob", "h2", 2L);

        session1.tryAcquire(projectDir);
        session2.forceAcquire(projectDir);
        // Session 1 should not delete session 2's lock when releasing.
        session1.release();

        assertThat(projectDir.resolve(ProjectLockManager.LOCK_FILE_NAME)).exists();
        assertThat(ProjectLockManager.readLock(projectDir.resolve(ProjectLockManager.LOCK_FILE_NAME)).user())
                .isEqualTo("bob");
    }

    @Test
    void openReadOnlyShouldNotWriteLock() {
        ProjectLockManager mgr = new ProjectLockManager(InstantSource.system(), "alice", "h", 1L);
        mgr.openReadOnly(projectDir);

        assertThat(projectDir.resolve(ProjectLockManager.LOCK_FILE_NAME)).doesNotExist();
        assertThat(mgr.status()).isEqualTo(LockStatus.READ_ONLY);
    }

    @Test
    void jsonRoundTripPreservesAllFields() {
        ProjectLock lock = ProjectLock.create("alice", "studio-mac", 1234L,
                Instant.parse("2026-01-01T00:00:00Z"));
        String json = ProjectLockManager.toJson(lock);
        ProjectLock parsed = ProjectLockManager.parseJson(json);
        assertThat(parsed).isEqualTo(lock);
    }
}
