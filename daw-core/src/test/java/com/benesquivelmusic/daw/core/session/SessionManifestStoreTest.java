package com.benesquivelmusic.daw.core.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SessionManifestStoreTest {

    @TempDir
    Path projectDir;

    private final SessionManifestStore store = new SessionManifestStore(ZoneOffset.UTC);

    @Test
    void slugSanitisesNames() {
        assertThat(SessionManifestStore.slug("Tracking day 2")).isEqualTo("tracking-day-2");
        assertThat(SessionManifestStore.slug("  Mix / touch-ups!! ")).isEqualTo("mix-touch-ups");
        assertThat(SessionManifestStore.slug("***")).isEqualTo("session");
    }

    @Test
    void manifestFileNameUsesStartDateAndSlug() {
        WorkingSession session = WorkingSession
                .start(Instant.parse("2026-03-21T09:00:00Z"), ZoneOffset.UTC)
                .withName("Tracking day 2");
        assertThat(store.manifestFileName(session))
                .isEqualTo("2026-03-21-tracking-day-2.session.xml");
    }

    @Test
    void writeThenReadRoundTrips() throws IOException {
        WorkingSession original = WorkingSession
                .start(Instant.parse("2026-03-21T09:00:00Z"), ZoneOffset.UTC)
                .withName("Tracking day 2")
                .withNotes("Re-amped the guitars")
                .withRecordedTime(Duration.ofMinutes(252))
                .withTake("take-17")
                .withTake("take-18")
                .withCheckpoint("checkpoint-141")
                .withJournalSegment("journal-2026-03-21-001.bin")
                .sealed(Instant.parse("2026-03-21T13:12:00Z"));

        Path file = store.write(projectDir, original);
        assertThat(file).exists();

        WorkingSession loaded = store.read(file);
        assertThat(loaded).isEqualTo(original);
    }

    @Test
    void listSessionsReturnsNewestFirst() throws IOException {
        WorkingSession day1 = WorkingSession
                .start(Instant.parse("2026-03-18T09:00:00Z"), ZoneOffset.UTC)
                .withName("Tracking day 1")
                .sealed(Instant.parse("2026-03-18T14:00:00Z"));
        WorkingSession day2 = WorkingSession
                .start(Instant.parse("2026-03-21T09:00:00Z"), ZoneOffset.UTC)
                .withName("Tracking day 2");
        store.write(projectDir, day1);
        store.write(projectDir, day2);

        List<WorkingSession> sessions = store.listSessions(projectDir);
        assertThat(sessions).extracting(WorkingSession::name)
                .containsExactly("Tracking day 2", "Tracking day 1");
    }

    @Test
    void listSessionsEmptyWhenNoDirectory() throws IOException {
        assertThat(store.listSessions(projectDir)).isEmpty();
    }

    @Test
    void corruptManifestIsSkipped() throws IOException {
        WorkingSession good = WorkingSession
                .start(Instant.parse("2026-03-21T09:00:00Z"), ZoneOffset.UTC);
        store.write(projectDir, good);
        Files.writeString(store.sessionsDirectory(projectDir).resolve("broken.session.xml"),
                "not xml at all");

        assertThat(store.listSessions(projectDir)).hasSize(1);
    }
}
