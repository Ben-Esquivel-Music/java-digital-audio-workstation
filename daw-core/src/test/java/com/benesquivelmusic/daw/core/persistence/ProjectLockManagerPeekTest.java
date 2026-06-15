package com.benesquivelmusic.daw.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ProjectLockManager#peekLock(Path)} — the side-effect-free
 * lock peek the story-296 Welcome/Hub recover-scan uses. A new file (the
 * existing {@code ProjectLockManagerTest} is left untouched).
 */
class ProjectLockManagerPeekTest {

    @TempDir
    Path projectDir;

    /** A controllable {@link InstantSource}, mirroring ProjectLockManagerTest's helper. */
    private static final class FakeClock implements InstantSource {
        private Instant now;
        FakeClock(Instant start) { this.now = start; }
        @Override public Instant instant() { return now; }
        void advance(Duration by) { now = now.plus(by); }
    }

    @Test
    void peekReturnsHolderForPresentLock() throws IOException {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        ProjectLockManager holder = new ProjectLockManager(clock, "alice", "studio-mac", 4242L);
        holder.tryAcquire(projectDir);

        Optional<ProjectLock> peeked = ProjectLockManager.peekLock(projectDir);

        assertThat(peeked).isPresent();
        assertThat(peeked.get().user()).isEqualTo("alice");
        assertThat(peeked.get().hostname()).isEqualTo("studio-mac");
        assertThat(peeked.get().pid()).isEqualTo(4242L);
    }

    @Test
    void peekReturnsEmptyForUnlockedDirectoryAndCreatesNoLockFile() {
        Optional<ProjectLock> peeked = ProjectLockManager.peekLock(projectDir);

        assertThat(peeked).isEmpty();
        // The peek must NOT have written a sidecar (unlike tryAcquire).
        assertThat(projectDir.resolve(ProjectLockManager.LOCK_FILE_NAME))
                .doesNotExist();
    }

    @Test
    void peekDoesNotAlterAnExistingLock() throws IOException {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        ProjectLockManager holder = new ProjectLockManager(clock, "bob", "edit-suite", 7L);
        holder.tryAcquire(projectDir);

        Path lockFile = projectDir.resolve(ProjectLockManager.LOCK_FILE_NAME);
        byte[] before = Files.readAllBytes(lockFile);

        ProjectLockManager.peekLock(projectDir);
        ProjectLockManager.peekLock(projectDir);

        // Peeking is read-only: the sidecar's bytes are unchanged.
        assertThat(Files.readAllBytes(lockFile)).isEqualTo(before);
    }

    @Test
    void peekedLockIsClassifiableAsStale() throws IOException {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        ProjectLockManager holder = new ProjectLockManager(clock, "carol", "tracking-rig", 9L);
        holder.tryAcquire(projectDir);

        clock.advance(Duration.ofMinutes(11)); // past the 10-minute threshold

        Optional<ProjectLock> peeked = ProjectLockManager.peekLock(projectDir);

        assertThat(peeked).isPresent();
        assertThat(ProjectLockManager.isStale(peeked.get(), clock.instant())).isTrue();
    }
}
