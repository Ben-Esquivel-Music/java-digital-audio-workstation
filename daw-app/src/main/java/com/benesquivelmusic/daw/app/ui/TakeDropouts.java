package com.benesquivelmusic.daw.app.ui;

/**
 * The dropout figures of one audio take (story 338, rulings E24, E45, E48
 * and E49): the take in flight, or the last one that ended.
 *
 * <p>The capture figures are those of the take's files: in a punch take only
 * what reaches into the punch region counts (rulings E76, E86 and E87); blocks
 * lost before the rings count over the whole time the take recorded.
 * {@code blocksLost} is every captured block the take lost or replaced by
 * silence it did not hear: blocks its capture rings dropped when full, each
 * counted when the gap it left reaches into the punch region; blocks the
 * render drive filled with silence because no captured block was there while
 * the input was live, clipped to the punch region, a run still open at a seal
 * that is not a device loss included (ruling E79); and blocks lost before the
 * rings — a full input ring or input queue on any input of the stream — while
 * the take recorded. Silence that an input's declared stop or a device loss's
 * tail covers is part of that event, not dropouts. {@code framesTruncated}
 * are the frames cut from blocks longer than a ring slot that fall inside the
 * punch region, in {@code truncationEpisodes} runs; each run with a frame
 * inside the region is one dropout.</p>
 *
 * @param blocksLost         the blocks lost or replaced by silence, {@code B}
 * @param truncationEpisodes the runs of truncated blocks
 * @param framesTruncated    the frames truncated, {@code F}
 * @param recording          whether the take is still recording
 */
public record TakeDropouts(long blocksLost, long truncationEpisodes, long framesTruncated, boolean recording) {

    /** Checks that no figure is negative. */
    public TakeDropouts {
        if (blocksLost < 0 || truncationEpisodes < 0 || framesTruncated < 0) {
            throw new IllegalArgumentException("dropout figures must not be negative: " + blocksLost + ", "
                    + truncationEpisodes + ", " + framesTruncated);
        }
    }

    /**
     * The take's dropouts, {@code K}: each lost block and each run of
     * truncated blocks.
     *
     * @return {@code blocksLost + truncationEpisodes}
     */
    public long dropouts() {
        return blocksLost + truncationEpisodes;
    }
}
