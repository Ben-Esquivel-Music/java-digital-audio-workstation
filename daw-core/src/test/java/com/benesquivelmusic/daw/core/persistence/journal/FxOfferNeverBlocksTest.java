package com.benesquivelmusic.daw.core.persistence.journal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 298 — Test 2. The FX-side proxy {@link JournalWriter#offer} returns
 * immediately and never drops, even when the writer thread's disk I/O is
 * stalled and the bounded queue has filled.
 *
 * <p>There is no JavaFX here — the "FX proxy" is simulated by the test thread
 * calling {@code offer(...)} and measuring the latency.</p>
 */
class FxOfferNeverBlocksTest {

    /** A syncer that blocks indefinitely (until released) to stall the writer thread. */
    private static final class StallingSyncer implements JournalSegment.Syncer {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch entered = new CountDownLatch(1);

        @Override
        public void force(FileChannel ch) throws IOException {
            entered.countDown();
            try {
                release.await();   // stall the writer thread inside the fsync
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ch.force(true);
        }
    }

    @Test
    void offerReturnsFastAndOverflowsWhenWriterStalls(@TempDir Path projectDir) throws Exception {
        ProjectContext ctx = ProjectContext.forProject(projectDir);
        StallingSyncer syncer = new StallingSyncer();
        // Tiny queue so it fills almost immediately once the writer stalls.
        JournalConfig config = new JournalConfig(4, 64L * 1024 * 1024, 16L * 1024 * 1024, 8);

        try (JournalWriter writer = new JournalWriter(ctx, config, true, null, syncer)) {
            writer.start();

            // Prime: first offer is accepted and the writer picks it up, entering
            // the stalling fsync. From here the writer makes no further progress.
            writer.offer("MixerEvent.MuteChanged", UUID.randomUUID(), null, Instant.now());
            assertThat(syncer.entered.await(5, TimeUnit.SECONDS))
                    .as("writer thread must enter the stalling fsync")
                    .isTrue();

            Set<OfferResult> seen = EnumSet.noneOf(OfferResult.class);
            int total = 200;
            long worstNanos = 0L;
            for (int i = 0; i < total; i++) {
                long t0 = System.nanoTime();
                OfferResult r = writer.offer("TrackEvent.Muted", UUID.randomUUID(), null, Instant.now());
                long dt = System.nanoTime() - t0;
                worstNanos = Math.max(worstNanos, dt);
                seen.add(r);
                // Never disabled, never an exception (we got here), never a drop.
                assertThat(r).isNotEqualTo(OfferResult.DISABLED);
            }

            // (a) offer is non-blocking — even the worst single call is well under 50 ms.
            assertThat(TimeUnit.NANOSECONDS.toMillis(worstNanos))
                    .as("offer() must never block on disk; worst single call < 50 ms")
                    .isLessThan(50L);

            // (b) once the queue filled, records landed in OVERFLOW (no drop, no block).
            assertThat(seen)
                    .as("a stalled writer must push records into the overflow buffer")
                    .contains(OfferResult.OVERFLOW);

            // Let the writer finish so close() is clean.
            syncer.release.countDown();
        }
    }
}
