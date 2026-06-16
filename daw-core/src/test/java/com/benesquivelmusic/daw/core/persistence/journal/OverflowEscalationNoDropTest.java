package com.benesquivelmusic.daw.core.persistence.journal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 298 — Test 3. When the writer's disk I/O stalls and the overflow byte
 * budget is exceeded, the journal escalates to
 * {@link Backpressure#UNRESPONSIVE} without dropping; a subsequent
 * {@link JournalWriter#forceFlush()} drains everything on a virtual thread that
 * differs from the caller, and count-in equals count-persisted.
 */
class OverflowEscalationNoDropTest {

    /** Stalls the writer until released, then performs real durability. */
    private static final class StallingSyncer implements JournalSegment.Syncer {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch entered = new CountDownLatch(1);

        @Override
        public void force(FileChannel ch) throws IOException {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ch.force(true);
        }
    }

    private static final class RecordingListener implements JournalListener {
        final List<Backpressure> levels = new CopyOnWriteArrayList<>();

        @Override
        public void onBackpressure(Backpressure level) {
            levels.add(level);
        }
    }

    @Test
    void escalatesToUnresponsiveThenForceFlushDrainsEverything(@TempDir Path projectDir)
            throws Exception {
        ProjectContext ctx = ProjectContext.forProject(projectDir);
        StallingSyncer syncer = new StallingSyncer();
        RecordingListener listener = new RecordingListener();
        // Tiny queue and a small overflow byte budget so escalation is quick.
        long overflowMax = 8 * 1024;   // 8 KiB
        JournalConfig config = new JournalConfig(2, overflowMax, 16L * 1024 * 1024, 8);

        int offered = 0;
        try (JournalWriter writer = new JournalWriter(ctx, config, true, listener, syncer)) {
            writer.start();

            // Prime so the writer thread enters the stalling fsync and stops draining.
            writer.offer("MixerEvent.MuteChanged", UUID.randomUUID(), bytes(200), Instant.now());
            offered++;
            assertThat(syncer.entered.await(5, TimeUnit.SECONDS)).isTrue();

            // Offer enough ~200-byte records to blow past the 8 KiB overflow budget.
            boolean escalatedSeen = false;
            for (int i = 0; i < 300 && !escalatedSeen; i++) {
                OfferResult r = writer.offer("TrackEvent.Muted", UUID.randomUUID(),
                        bytes(200), Instant.now());
                offered++;
                if (r == OfferResult.ESCALATED) {
                    escalatedSeen = true;
                }
            }

            assertThat(escalatedSeen)
                    .as("offer must report ESCALATED once the overflow budget is exceeded")
                    .isTrue();
            assertThat(listener.levels)
                    .as("listener must observe BUFFERING then UNRESPONSIVE")
                    .contains(Backpressure.BUFFERING, Backpressure.UNRESPONSIVE);

            // Un-stall, then force a synchronous flush.
            syncer.release.countDown();
            Thread caller = Thread.currentThread();
            Thread flusher = writer.forceFlush();

            // (a) the flush ran on a virtual thread that is not the caller.
            assertThat(flusher.isVirtual())
                    .as("forceFlush must drain on a virtual thread")
                    .isTrue();
            assertThat(flusher)
                    .as("forceFlush must not run on the caller's thread")
                    .isNotSameAs(caller);

            // (b) count-in == count-persisted (NO DROP).
            long persisted = totalPersisted(ctx.journalDirectory());
            assertThat(persisted)
                    .as("every offered record must be persisted — the journal never drops")
                    .isEqualTo(offered);
        }

        // Even after close, the durable count is exactly what was offered.
        assertThat(totalPersisted(ctx.journalDirectory())).isEqualTo(offered);
    }

    private static byte[] bytes(int n) {
        return new byte[n];
    }

    private static long totalPersisted(Path journalDir) throws IOException {
        long total = 0L;
        for (Path segment : RecoveryScanner.listSegments(journalDir)) {
            total += JournalSegment.readAll(segment).size();
        }
        return total;
    }
}
