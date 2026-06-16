package com.benesquivelmusic.daw.core.persistence.journal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 298 — Test 6. The core half of the "Use journaled persistence"
 * preference gate: with {@code enabled=false} the writer creates no journal
 * and recovery finds nothing; with {@code enabled=true} it writes
 * {@code segment-000.bin} containing the records. (daw-app reads the actual
 * SettingsModel boolean and passes it as {@code enabled}.)
 */
class JournaledPersistencePreferenceGateTest {

    @Test
    void disabled_createsNoJournalAndRecoveryIsEmpty(@TempDir Path projectDir) throws Exception {
        ProjectContext ctx = ProjectContext.forProject(projectDir);

        try (JournalWriter writer =
                     new JournalWriter(ctx, JournalConfig.DEFAULT, false, null)) {
            writer.start();   // no-op when disabled
            for (int i = 0; i < 10; i++) {
                OfferResult r = writer.offer("MixerEvent.MuteChanged",
                        UUID.randomUUID(), null, Instant.now());
                assertThat(r)
                        .as("a disabled writer must report DISABLED for every offer")
                        .isEqualTo(OfferResult.DISABLED);
            }
        }

        assertThat(Files.exists(ctx.journalDirectory()))
                .as("a disabled writer must not create the journal/ directory")
                .isFalse();

        Optional<RecoverySummary> summary = new RecoveryScanner().scan(ctx);
        assertThat(summary)
                .as("with no checkpoint and no journal, the scan must be empty")
                .isEmpty();
    }

    @Test
    void enabled_writesSegmentZeroWithRecords(@TempDir Path projectDir) throws Exception {
        ProjectContext ctx = ProjectContext.forProject(projectDir);

        int count = 12;
        try (JournalWriter writer =
                     new JournalWriter(ctx, JournalConfig.DEFAULT, true, null)) {
            writer.start();
            for (int i = 0; i < count; i++) {
                writer.offer("TrackEvent.Muted", UUID.randomUUID(), null, Instant.now());
            }
            waitUntilQueued(writer, count);
        }

        Path segmentZero = ctx.journalDirectory().resolve("segment-000.bin");
        assertThat(Files.exists(segmentZero))
                .as("an enabled writer must create segment-000.bin")
                .isTrue();

        List<JournalRecord> persisted = JournalSegment.readAll(segmentZero);
        assertThat(persisted)
                .as("segment-000.bin must contain every offered record")
                .hasSize(count);

        Optional<RecoverySummary> summary = new RecoveryScanner().scan(ctx);
        assertThat(summary).isPresent();
        assertThat(summary.orElseThrow().replayableEventCount()).isEqualTo(count);
        assertThat(summary.orElseThrow().segmentFileNames()).contains("segment-000.bin");
    }

    private static void waitUntilQueued(JournalWriter writer, int n) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (writer.queuedSinceCheckpoint() < n && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
    }
}
