package com.benesquivelmusic.daw.core.session;

import com.benesquivelmusic.daw.core.session.SessionManager.SessionOpenResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SessionManagerTest {

    @TempDir
    Path projectDir;

    private final SessionManager manager = new SessionManager(
            new SessionManifestStore(ZoneOffset.UTC), ZoneOffset.UTC, Duration.ofHours(4));

    @Test
    void firstOpenCreatesNewSession() throws IOException {
        SessionOpenResult result = manager.openSession(projectDir,
                Instant.parse("2026-03-21T09:00:00Z"));

        assertThat(result.continued()).isFalse();
        assertThat(result.sealed()).isEmpty();
        assertThat(result.current().isActive()).isTrue();
        assertThat(manager.store().listSessions(projectDir)).hasSize(1);
    }

    @Test
    void reopenWithinGapContinuesSameSession() throws IOException {
        SessionOpenResult first = manager.openSession(projectDir,
                Instant.parse("2026-03-21T09:00:00Z"));
        SessionOpenResult second = manager.openSession(projectDir,
                Instant.parse("2026-03-21T11:00:00Z"));

        assertThat(second.continued()).isTrue();
        assertThat(second.current().id()).isEqualTo(first.current().id());
        assertThat(second.sealed()).isEmpty();
    }

    @Test
    void reopenAfterGapSealsPriorAndCreatesNew() throws IOException {
        SessionOpenResult first = manager.openSession(projectDir,
                Instant.parse("2026-03-21T09:00:00Z"));
        SessionOpenResult next = manager.openSession(projectDir,
                Instant.parse("2026-03-22T09:00:00Z"));

        assertThat(next.continued()).isFalse();
        assertThat(next.sealed()).isPresent();
        assertThat(next.sealed().get().id()).isEqualTo(first.current().id());
        assertThat(next.sealed().get().isActive()).isFalse();
        assertThat(next.current().id()).isNotEqualTo(first.current().id());
        assertThat(manager.store().listSessions(projectDir)).hasSize(2);
    }

    @Test
    void aStaleSessionTheUserCannotWriteIsStillSealedAndANewSessionStarts() throws IOException {
        assumeTrue(Files.getFileStore(projectDir).supportsFileAttributeView(PosixFileAttributeView.class),
                "the file system has POSIX permission bits (Linux CI)");
        SessionOpenResult first = manager.openSession(projectDir, Instant.parse("2026-03-21T09:00:00Z"));
        Path sessions = manager.store().sessionsDirectory(projectDir);
        Path stale = sessions.resolve(manager.store().manifestFileName(first.current()));
        // Another user's session in a group-shared folder: this user may rename over it, not write it.
        Files.setPosixFilePermissions(stale, PosixFilePermissions.fromString("r--r--r--"));
        assumeFalse(Files.isWritable(stale), "a superuser may write a read-only file");

        SessionOpenResult next = manager.openSession(projectDir, Instant.parse("2026-03-22T09:00:00Z"));

        assertThat(next.continued()).isFalse();
        assertThat(next.sealed()).hasValueSatisfying(sealed ->
                assertThat(sealed.id()).isEqualTo(first.current().id()));
        assertThat(manager.store().read(stale).isActive()).as("the stale session is sealed on disk").isFalse();
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(stale)))
                .as("the replaced manifest keeps its bits").isEqualTo("r--r--r--");
        try (Stream<Path> entries = Files.list(sessions)) {
            assertThat(entries).as("the sealed and the new session, and no temp")
                    .containsExactlyInAnyOrder(stale, sessions.resolve(manager.store().manifestFileName(next.current())));
        }
    }

    @Test
    void closeSessionSealsManifest() throws IOException {
        SessionOpenResult open = manager.openSession(projectDir,
                Instant.parse("2026-03-21T09:00:00Z"));
        WorkingSession sealed = manager.closeSession(projectDir, open.current(),
                Instant.parse("2026-03-21T17:00:00Z"));

        assertThat(sealed.isActive()).isFalse();
        assertThat(manager.store().listSessions(projectDir))
                .allMatch(s -> !s.isActive());
    }

    @Test
    void historyFallsBackToCheckpointDatesWhenNoManifests() throws IOException {
        Path checkpoints = projectDir.resolve("checkpoints");
        Files.createDirectories(checkpoints);
        Files.writeString(checkpoints.resolve("checkpoint-001-20260321T090000.daw"), "a");
        Files.writeString(checkpoints.resolve("checkpoint-002-20260321T140000.daw"), "b");
        Files.writeString(checkpoints.resolve("checkpoint-003-20260322T100000.daw"), "c");

        List<WorkingSession> history = manager.history(projectDir);

        assertThat(history).hasSize(2);
        // Newest day first.
        assertThat(history.get(0).startTime())
                .isEqualTo(Instant.parse("2026-03-22T10:00:00Z"));
        assertThat(history.get(1).checkpointIds())
                .containsExactly("checkpoint-001-20260321T090000.daw",
                        "checkpoint-002-20260321T140000.daw");
        assertThat(history.get(1).startTime())
                .isEqualTo(Instant.parse("2026-03-21T09:00:00Z"));
        assertThat(history.get(1).endTime())
                .isEqualTo(Instant.parse("2026-03-21T14:00:00Z"));
    }

    @Test
    void historyPrefersManifestsOverCheckpoints() throws IOException {
        manager.openSession(projectDir, Instant.parse("2026-03-21T09:00:00Z"));
        Path checkpoints = projectDir.resolve("checkpoints");
        Files.createDirectories(checkpoints);
        Files.writeString(checkpoints.resolve("checkpoint-001-20200101T090000.daw"), "a");

        List<WorkingSession> history = manager.history(projectDir);
        assertThat(history).hasSize(1);
        assertThat(history.getFirst().startTime())
                .isEqualTo(Instant.parse("2026-03-21T09:00:00Z"));
    }
}
