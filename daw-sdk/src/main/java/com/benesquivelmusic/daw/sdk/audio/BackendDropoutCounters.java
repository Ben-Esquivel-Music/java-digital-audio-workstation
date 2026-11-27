package com.benesquivelmusic.daw.sdk.audio;

import java.util.Objects;

/**
 * The gaps a backend observed on its own side of the audio path, pulled
 * through {@link AudioBackend#dropoutCounters()}: periods its device callback
 * found no rendered block to play, rendered blocks it threw away, the driver's
 * own overload and resync messages, and captured input blocks it dropped.
 *
 * <p>Every count is monotonic for the life of the backend object. A backend
 * that rebuilds a per-stream helper (an ASIO buffer-switch bridge) on every
 * open folds that helper's counts into its own when the helper is torn down,
 * so a reopen never makes a count go backwards.</p>
 *
 * <p>{@code inputBlocksDropped} counts only the losses NOT already reported
 * through {@link BackendThreadCounters#inputBlocksLost()}: a reader that adds
 * the two counts each loss once. It is never a block a publisher dropped for
 * one subscriber (story 338, ruling E208), nor a block
 * {@link AudioBackend#failedCallbackInputBlocks()} counts.</p>
 *
 * <p>This is a value: building one allocates, so a backend builds it on the
 * caller's thread, never on a real-time thread.</p>
 *
 * @param outputStarvedPeriods     device callback periods that found the
 *                                 output ring empty after the stream had
 *                                 played its first rendered block, and so
 *                                 played silence; {@code >= 0}
 * @param outputDiscardedBlocks    rendered blocks the backend discarded
 *                                 unplayed: refused by a full output ring, or
 *                                 (ASIO) queued behind a newer block that a
 *                                 device callback played instead;
 *                                 {@code >= 0}
 * @param driverOverloads          overload messages the driver sent (ASIO's
 *                                 {@code kAsioOverload}); {@code >= 0}
 * @param driverResyncs            resync requests the driver sent (ASIO's
 *                                 {@code kAsioResyncRequest}); {@code >= 0}
 * @param inputBlocksDropped       captured input blocks the backend lost
 *                                 before it could publish them, so that no
 *                                 subscriber received them (an input ring
 *                                 that overflowed); never a block its
 *                                 publisher dropped for one subscriber;
 *                                 {@code >= 0}
 * @param reportsOutputStarvation  whether this backend can observe output
 *                                 starvation at all; {@code false} means
 *                                 {@code outputStarvedPeriods} is zero because
 *                                 the driver gives no such signal, not because
 *                                 none happened
 */
public record BackendDropoutCounters(long outputStarvedPeriods,
                                     long outputDiscardedBlocks,
                                     long driverOverloads,
                                     long driverResyncs,
                                     long inputBlocksDropped,
                                     boolean reportsOutputStarvation) {

    /**
     * Zero counts from a backend that cannot observe output starvation: the
     * default of {@link AudioBackend#dropoutCounters()}.
     */
    public static final BackendDropoutCounters NONE =
            new BackendDropoutCounters(0L, 0L, 0L, 0L, 0L, false);

    public BackendDropoutCounters {
        requireNonNegative(outputStarvedPeriods, "outputStarvedPeriods");
        requireNonNegative(outputDiscardedBlocks, "outputDiscardedBlocks");
        requireNonNegative(driverOverloads, "driverOverloads");
        requireNonNegative(driverResyncs, "driverResyncs");
        requireNonNegative(inputBlocksDropped, "inputBlocksDropped");
    }

    /**
     * The sum of this report and {@code other}: each count added, and output
     * starvation reported when either side reports it. For a backend that
     * wraps another and keeps counters of its own.
     *
     * @param other the report to add; must not be null
     * @return the sum; never {@code null}
     */
    public BackendDropoutCounters plus(BackendDropoutCounters other) {
        Objects.requireNonNull(other, "other must not be null");
        return new BackendDropoutCounters(
                outputStarvedPeriods + other.outputStarvedPeriods,
                outputDiscardedBlocks + other.outputDiscardedBlocks,
                driverOverloads + other.driverOverloads,
                driverResyncs + other.driverResyncs,
                inputBlocksDropped + other.inputBlocksDropped,
                reportsOutputStarvation || other.reportsOutputStarvation);
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must not be negative: " + value);
        }
    }
}
