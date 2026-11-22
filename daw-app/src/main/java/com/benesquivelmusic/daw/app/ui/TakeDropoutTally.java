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
 * are, because they are per take and stay readable after it ended. They are
 * the counts of the take's files: in a punch take only losses that reach
 * into the punch region count (rulings E76, E86 and E87). Blocks
 * lost before the rings are the engine's monotonic count, baselined when the
 * take starts recording; while it records the figure grows with the count,
 * and when it stops the difference is latched, so losses of a later stream
 * never reach a finished take.</p>
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

    private final LongSupplier inputBlocksLostBeforeTheRings;
    private TakeCounts take;
    private boolean recording;
    private long lossBaseline;
    private long latchedLoss;

    /**
     * A tally that reads the engine's monotonic count of input blocks lost before the capture rings.
     *
     * @param inputBlocksLostBeforeTheRings that count
     */
    TakeDropoutTally(LongSupplier inputBlocksLostBeforeTheRings) {
        this.inputBlocksLostBeforeTheRings = Objects.requireNonNull(inputBlocksLostBeforeTheRings,
                "inputBlocksLostBeforeTheRings must not be null");
    }

    /**
     * A take started recording: its figures replace the last take's.
     *
     * @param counts the audio take's counts, or {@code null} for a MIDI-only take, which has no figures
     */
    void takeStarted(TakeCounts counts) {
        take = counts;
        recording = counts != null;
        lossBaseline = counts == null ? 0L : inputBlocksLostBeforeTheRings.getAsLong();
        latchedLoss = 0L;
    }

    /** The take stopped recording: what it lost before the rings is latched. */
    void takeStopped() {
        if (!recording) {
            return;
        }
        latchedLoss = lossSinceTheStart();
        recording = false;
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
        long lostBeforeTheRings = recording ? lossSinceTheStart() : latchedLoss;
        return Optional.of(new TakeDropouts(
                counts.overflowBlocks() + counts.starvedBlocks() + lostBeforeTheRings,
                counts.truncationEpisodes(), counts.truncatedFrames(), recording));
    }

    private long lossSinceTheStart() {
        return Math.max(0L, inputBlocksLostBeforeTheRings.getAsLong() - lossBaseline);
    }
}
