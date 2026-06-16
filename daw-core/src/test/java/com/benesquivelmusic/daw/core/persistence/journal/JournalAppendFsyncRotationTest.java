package com.benesquivelmusic.daw.core.persistence.journal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Story 298 — Test 1. The {@link JournalWriter} drains its queue on a single
 * writer thread, fsyncs once per appended group, rotates segments at the size
 * cap, and rotates + resets the since-checkpoint counter on a checkpoint.
 */
class JournalAppendFsyncRotationTest {

    /** A counting + thread-recording fsync seam that still performs a real force(true). */
    private static final class CountingSyncer implements JournalSegment.Syncer {
        final AtomicInteger forceCalls = new AtomicInteger();
        final Set<String> writerThreadNames = ConcurrentHashMap.newKeySet();

        @Override
        public void force(FileChannel ch) throws IOException {
            forceCalls.incrementAndGet();
            writerThreadNames.add(Thread.currentThread().getName());
            ch.force(true);   // keep real durability
        }
    }

    @Test
    void singleWriterThread_forcePerGroup_rotation_and_checkpointReset(@TempDir Path projectDir)
            throws Exception {
        ProjectContext ctx = ProjectContext.forProject(projectDir);
        CountingSyncer syncer = new CountingSyncer();
        // Small segment cap so rotation triggers quickly; small batch so we
        // get many distinct fsync groups.
        JournalConfig config = new JournalConfig(4096, 64L * 1024 * 1024, 1024L, 8);

        int recordCount = 400;
        try (JournalWriter writer =
                     new JournalWriter(ctx, config, true, null, syncer)) {
            writer.start();
            for (int i = 0; i < recordCount; i++) {
                OfferResult r = writer.offer("MixerEvent.MuteChanged",
                        UUID.randomUUID(), payload(i), Instant.now());
                assertThat(r).isIn(OfferResult.QUEUED, OfferResult.OVERFLOW, OfferResult.ESCALATED);
            }
            // Wait for the writer to drain everything to disk.
            waitUntilQueuedAtLeast(writer, recordCount);

            // (a) exactly one writer thread drained the queue.
            assertThat(syncer.writerThreadNames)
                    .as("a single writer thread must perform every append")
                    .containsExactly("daw-journal-writer");

            // (b) at least one force(true) per group; groups <= ceil(count/batch)+rotations.
            assertThat(syncer.forceCalls.get())
                    .as("fsync must be called at least once per drained group")
                    .isGreaterThanOrEqualTo(recordCount / config.drainBatchSize());

            // (c) the segment rotated past segment-000.bin once the size cap was crossed.
            assertThat(Files.exists(ctx.journalDirectory().resolve("segment-001.bin")))
                    .as("segment must rotate to segment-001.bin past the size cap")
                    .isTrue();
            assertThat(writer.segmentFileNames())
                    .contains("segment-000.bin", "segment-001.bin");

            // (d) onCheckpointSucceeded() resets the since-checkpoint counter and rotates.
            int segmentsBefore = writer.segmentFileNames().size();
            writer.onCheckpointSucceeded();
            // Counter resets synchronously.
            assertThat(writer.queuedSinceCheckpoint())
                    .as("checkpoint must reset the since-checkpoint counter to 0")
                    .isZero();
            // Force a post-checkpoint append so the requested rotation lands.
            writer.offer("TrackEvent.Muted", UUID.randomUUID(), payload(0), Instant.now());
            waitUntilSegmentCountAtLeast(writer, segmentsBefore + 1);
            assertThat(writer.segmentFileNames().size())
                    .as("checkpoint must rotate to a fresh segment")
                    .isGreaterThan(segmentsBefore);
        }

        // Durability: read every record back across all segments.
        long persisted = totalPersisted(ctx.journalDirectory());
        assertThat(persisted)
                .as("every offered record (plus the post-checkpoint one) must be durable")
                .isEqualTo(recordCount + 1);
    }

    /**
     * Story 298 review — a persistently failing disk must not hang
     * {@link JournalWriter#close()}. The writer thread retries failed appends
     * forever during normal operation (never drop), but once {@code close()}
     * sets its {@code stopping} flag the retry loop must give up so the
     * {@code join()} — and the whole app shutdown — returns.
     */
    @Test
    void close_doesNotHang_whenDiskPersistentlyFails(@TempDir Path projectDir) {
        ProjectContext ctx = ProjectContext.forProject(projectDir);
        // A syncer that always fails: every group fsync throws, so every append
        // batch the writer thread attempts fails and is retried.
        JournalSegment.Syncer alwaysFails = ch -> {
            throw new IOException("simulated dead disk");
        };
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            JournalWriter writer =
                    new JournalWriter(ctx, JournalConfig.DEFAULT, true, null, alwaysFails);
            writer.start();
            for (int i = 0; i < 50; i++) {
                writer.offer("MixerEvent.MuteChanged", UUID.randomUUID(), payload(i), Instant.now());
            }
            // Must return promptly even though every append keeps failing.
            writer.close();
        });
    }

    private static byte[] payload(int i) {
        // ~120 bytes so the 1 KiB segment cap is crossed within a handful of records.
        byte[] b = new byte[120];
        b[0] = (byte) i;
        return b;
    }

    private static void waitUntilQueuedAtLeast(JournalWriter writer, int n) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (writer.queuedSinceCheckpoint() < n && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
    }

    private static void waitUntilSegmentCountAtLeast(JournalWriter writer, int n)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (writer.segmentFileNames().size() < n && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
    }

    private static long totalPersisted(Path journalDir) throws IOException {
        long total = 0L;
        for (Path segment : RecoveryScanner.listSegments(journalDir)) {
            List<JournalRecord> records = JournalSegment.readAll(segment);
            total += records.size();
        }
        return total;
    }
}
