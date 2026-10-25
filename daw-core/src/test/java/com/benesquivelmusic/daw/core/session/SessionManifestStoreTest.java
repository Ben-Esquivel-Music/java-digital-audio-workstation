package com.benesquivelmusic.daw.core.session;

import com.benesquivelmusic.daw.core.persistence.AtomicFileWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                .startsWith("2026-03-21-tracking-day-2-")
                .endsWith(".session.xml")
                .contains(session.id());
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
    void aManifestWhosePublishFailsKeepsThePreviousManifestAndLeavesNoTemp() throws IOException {
        WorkingSession session = WorkingSession
                .start(Instant.parse("2026-03-21T09:00:00Z"), ZoneOffset.UTC)
                .withName("Tracking day 2");
        Path manifest = store.write(projectDir, session);
        byte[] previous = Files.readAllBytes(manifest);
        IOException crash = new IOException("injected failure before the manifest's publish");
        SessionManifestStore failing = new SessionManifestStore(ZoneOffset.UTC, new AtomicFileWriter(
                AtomicFileWriter.TEMP_CHANNEL_OPENER, (temp, target, options) -> {
                    throw crash;
                }));

        assertThatThrownBy(() -> failing.write(projectDir, session.withTake("take-1"))).isSameAs(crash);

        assertThat(Files.readAllBytes(manifest)).isEqualTo(previous);
        try (Stream<Path> entries = Files.list(store.sessionsDirectory(projectDir))) {
            assertThat(entries).as("no temp beside the manifest").containsExactly(manifest);
        }
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
