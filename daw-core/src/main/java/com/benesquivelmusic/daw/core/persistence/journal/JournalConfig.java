package com.benesquivelmusic.daw.core.persistence.journal;

/**
 * Story 298 — tuning knobs for the write-ahead journal, with conservative
 * defaults suited to long recording sessions.
 *
 * @param queueCapacity   bounded in-memory hand-off queue capacity. The
 *                        producer ({@code offer}) never blocks on this;
 *                        once it fills, records spill to the overflow
 *                        deque. Must be {@code > 0}.
 * @param overflowMaxBytes the overflow byte budget after which the journal
 *                        escalates backpressure to
 *                        {@link Backpressure#UNRESPONSIVE} (disk is not
 *                        keeping up). Records are still buffered — never
 *                        dropped. Must be {@code > 0}.
 * @param segmentMaxBytes the maximum size of one {@code segment-NNN.bin}
 *                        file before the writer rotates to the next
 *                        segment. Must be {@code > 0}.
 * @param drainBatchSize  the maximum number of records the writer thread
 *                        drains and fsyncs as a single group. Must be
 *                        {@code > 0}.
 */
public record JournalConfig(
        int queueCapacity,
        long overflowMaxBytes,
        long segmentMaxBytes,
        int drainBatchSize) {

    /**
     * Sensible production defaults: a 4096-slot hand-off queue, a 64&nbsp;MB
     * overflow budget before the "unresponsive" escalation, 16&nbsp;MB
     * segments, and 256-record fsync groups.
     */
    public static final JournalConfig DEFAULT =
            new JournalConfig(4096, 64L * 1024 * 1024, 16L * 1024 * 1024, 256);

    /**
     * Canonical constructor — every knob must be strictly positive.
     */
    public JournalConfig {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be > 0: " + queueCapacity);
        }
        if (overflowMaxBytes <= 0L) {
            throw new IllegalArgumentException("overflowMaxBytes must be > 0: " + overflowMaxBytes);
        }
        if (segmentMaxBytes <= 0L) {
            throw new IllegalArgumentException("segmentMaxBytes must be > 0: " + segmentMaxBytes);
        }
        if (drainBatchSize <= 0) {
            throw new IllegalArgumentException("drainBatchSize must be > 0: " + drainBatchSize);
        }
    }
}
