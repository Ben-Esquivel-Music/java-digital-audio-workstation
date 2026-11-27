package com.benesquivelmusic.daw.app.ui;

/**
 * The dropout figures of one audio take (story 338, rulings E24, E45, E48
 * and E49): the take in flight, or the last one that ended.
 *
 * <p>The capture figures are those of the take's files: in a punch take only
 * what reaches into the punch region counts (rulings E76, E86 and E87); blocks
 * lost before the rings count over the whole time the take recorded.
 * {@code blocksLost} is every captured block the take lost or replaced by
 * silence it did not hear: blocks its capture rings dropped when full that
 * carried audio a track records, and the input-starved ones among them where
 * an applied starved block in their place would count, each run counted when
 * the frames it would have held reach into the punch region, and, at the
 * person's Stop, such blocks a capture ring received or dropped that no block
 * the take applied follows, counted by their own extent (rulings E213, E242,
 * E273, E275 and E281); in a take sealed early only a run a block the take
 * kept follows counts, and at a device loss, of the drops no applied block
 * follows, only those the final sweep's look found, unless the take was
 * sealed early in that sweep (rulings E264, E274, E275 and E286); blocks the
 * render drive filled with
 * silence because no captured block was there while the input was live,
 * clipped to the punch region, a run still open at a seal that is not a
 * device loss included (ruling E79); and blocks lost before the rings on any
 * input of the stream — a full input ring or input queue, a block an input
 * thread could not build or publish, a block a publisher dropped for the
 * engine's capture subscriber, on an input opened beside the stream a failed
 * device callback, and on the stream's own device a failed device callback
 * that lost its period's input (rulings E25, E45, E206, E208 and E211) —
 * while the take recorded. Silence that an input's declared stop or a device
 * loss's tail covers is part of that event, not dropouts, and so is a block
 * of the silence after an input's declared stop that a full capture ring
 * dropped, unless a block of that input the take kept shows it live again
 * after it (ruling E302), or that a ring received or dropped after the final
 * sweep at the person's Stop (rulings E49, E207 and E277). {@code framesTruncated} are the frames
 * cut from blocks longer than a ring slot that fall inside the punch region,
 * in {@code truncationEpisodes} runs; each run with a frame inside the region
 * is one dropout.</p>
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
