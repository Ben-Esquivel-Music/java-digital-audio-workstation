package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.recording.RecordingPipeline;

import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Keeps the {@link TakeDropouts} of the take in flight and of the last one
 * that ended (story 338, ruling E24): the record coordinator tells it when an
 * audio take starts and stops recording, and the status strip reads it.
 *
 * <p>The take's own counts — capture-ring overflows, starved blocks,
 * truncated frames and runs — are read from the take whenever the figures
 * are, because they are per take and stay readable after it ended; the
 * take's seal can still raise them after its Stop, with its trailing starved
 * run, the runs of dropped blocks it lines — those no applied block follows,
 * and those whose line waited for the input's starved run to end — and the
 * blocks a capture ring received after the final sweep (rulings E273, E275
 * and E281). They are the counts of the take's files: in a punch take only
 * losses that reach into the punch region count (rulings E76, E86 and E87).
 * Blocks lost before the rings are the engine's monotonic count,
 * baselined by the engine's read made as the take starts recording, so a
 * backend's report that fails at that read imports no earlier loss (ruling
 * E261), and they count over the whole time it records, a punch take's
 * pre-roll included (ruling E86); while it records, what it lost before the
 * rings grows with the count and never falls, and when it stops that is
 * latched, so losses of a later stream never reach a finished take (rulings
 * E214 and E245).</p>
 *
 * <p>A take that starts replaces the last one's figures, a MIDI-only take
 * included (it has none). {@link #clear()} forgets a take that ended; it
 * leaves a take still recording alone, whose figures its manifest
 * records.</p>
 *
 * <p>FX thread only; not thread-safe.</p>
 */
final class TakeDropoutTally {

    /** The take's own counts, read when the figures are. */
    interface TakeCounts {
        long overflowBlocks();

        long starvedBlocks();

        long truncatedFrames();

        long truncationEpisodes();

        /**
         * The counts of {@code pipeline}'s take.
         *
         * @param pipeline the take's recording pipeline
         * @return its counts, read live
         */
        static TakeCounts of(RecordingPipeline pipeline) {
            Objects.requireNonNull(pipeline, "pipeline must not be null");
            return new TakeCounts() {
                @Override
                public long overflowBlocks() {
                    return pipeline.getFileOverflowBlocks();
                }

                @Override
                public long starvedBlocks() {
                    return pipeline.getStarvedInputBlocks();
                }

                @Override
                public long truncatedFrames() {
                    return pipeline.getFileTruncatedFrames();
                }

                @Override
                public long truncationEpisodes() {
                    return pipeline.getFileTruncationEpisodes();
                }
            };
        }
    }

    private final LongSupplier lostAsATakeStarts;
    private final LongSupplier inputBlocksLostBeforeTheRings;
    private TakeCounts take;
    private boolean recording;
    private long lossBaseline;
    private long latchedLoss;
    /** The highest before-ring loss {@link #current()} showed for the take in flight. */
    private long lossShown;

    /**
     * A tally that reads the engine's monotonic count of input blocks lost before the capture rings.
     *
     * @param lostAsATakeStarts             that count as read when a take starts recording, which a backend's
     *                                      report that fails at that read keeps from importing earlier losses
     *                                      (ruling E261)
     * @param inputBlocksLostBeforeTheRings that count, for every other read
     */
    TakeDropoutTally(LongSupplier lostAsATakeStarts, LongSupplier inputBlocksLostBeforeTheRings) {
        this.lostAsATakeStarts = Objects.requireNonNull(lostAsATakeStarts, "lostAsATakeStarts must not be null");
        this.inputBlocksLostBeforeTheRings = Objects.requireNonNull(inputBlocksLostBeforeTheRings,
                "inputBlocksLostBeforeTheRings must not be null");
    }

    /**
     * A take started recording: its figures replace the last take's. The count of blocks lost before the rings, as
     * read when a take starts (ruling E261), is read before anything changes, so a read that throws leaves the last
     * take's figures as they were (rulings E214 and E245).
     *
     * @param counts the audio take's counts, or {@code null} for a MIDI-only take, which has no figures
     */
    void takeStarted(TakeCounts counts) {
        long baseline = counts == null ? 0L : lostAsATakeStarts.getAsLong();
        take = counts;
        recording = counts != null;
        lossBaseline = baseline;
        latchedLoss = 0L;
        lossShown = 0L;
    }

    /**
     * The take stopped recording: from here its figures are the last take's. What it lost before the rings is
     * latched at least at what the cell showed for it and raised by the count read now; a read that throws leaves
     * the shown value (rulings E92, E214 and E245).
     */
    void takeStopped() {
        if (!recording) {
            return;
        }
        recording = false;
        // Latched before the read: a read that throws leaves the figure the cell showed (ruling E245).
        latchedLoss = lossShown;
        latchedLoss = Math.max(lossShown, lossSinceTheStart());
    }

    /** Forgets the figures of a take that ended; a take still recording keeps its own. */
    void clear() {
        if (!recording) {
            take = null;
        }
    }

    /**
     * The figures of the take in flight, or of the last one that ended.
     *
     * @return the figures; empty before the first audio take, after a MIDI-only take started and after a clear
     */
    Optional<TakeDropouts> current() {
        TakeCounts counts = take;
        if (counts == null) {
            return Optional.empty();
        }
        long lostBeforeTheRings = recording ? lossShownNow() : latchedLoss;
        return Optional.of(new TakeDropouts(
                counts.overflowBlocks() + counts.starvedBlocks() + lostBeforeTheRings,
                counts.truncationEpisodes(), counts.truncatedFrames(), recording));
    }

    private long lossShownNow() {
        lossShown = Math.max(lossShown, lossSinceTheStart());
        return lossShown;
    }

    private long lossSinceTheStart() {
        return Math.max(0L, inputBlocksLostBeforeTheRings.getAsLong() - lossBaseline);
    }
}
