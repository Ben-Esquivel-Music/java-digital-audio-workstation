package com.benesquivelmusic.daw.sdk.audio;

import java.time.Duration;
import java.util.Objects;

/**
 * Sealed interface describing a real-time audio callback anomaly
 * (an "xrun", for buffer under-run or over-run).
 *
 * <p>The audio engine emits {@code XrunEvent}s whenever the render
 * callback fails to meet its deadline or the graph reports an
 * abnormally high CPU load. User-interface layers subscribe to a
 * {@link java.util.concurrent.Flow.Publisher Flow.Publisher&lt;XrunEvent&gt;}
 * to surface these events in a counter, log, or notification — giving
 * the user first-class visibility into dropouts the way professional
 * DAWs (Pro Tools, Reaper, Logic, Ableton) do.</p>
 *
 * <p>This is a sealed algebraic data type (Java 17+) so consumers can
 * use exhaustive pattern-matching {@code switch} expressions.</p>
 */
public sealed interface XrunEvent
        permits XrunEvent.BufferLate,
                XrunEvent.BufferDropped,
                XrunEvent.GraphOverload {

    /**
     * Returns the audio-graph frame index at which this event occurred.
     * For events that are not tied to a single frame the value is
     * {@code -1}.
     */
    long frameIndex();

    /**
     * Emitted when a buffer was produced but took longer than its
     * deadline (under-run without full drop).
     *
     * @param frameIndex   the frame index of the late buffer
     * @param deadlineMiss how far past the deadline the buffer arrived;
     *                     must be non-negative
     */
    record BufferLate(long frameIndex, Duration deadlineMiss) implements XrunEvent {
        public BufferLate {
            Objects.requireNonNull(deadlineMiss, "deadlineMiss must not be null");
            if (deadlineMiss.isNegative()) {
                throw new IllegalArgumentException(
                        "deadlineMiss must not be negative: " + deadlineMiss);
            }
        }
    }

    /**
     * Emitted when a buffer could not be produced in time and the
     * backend had to drop or zero-fill the slot — a full dropout.
     *
     * @param frameIndex the frame index of the dropped buffer
     */
    record BufferDropped(long frameIndex) implements XrunEvent { }

    /**
     * Emitted when the processing graph as a whole exceeds the CPU
     * budget. Carries the offending node identifier (the node that
     * contributed the most to the overrun) so tooling can highlight
     * or bypass it.
     *
     * @param offendingNodeId identifier of the node that dominated the
     *                        over-budget buffer; must not be null
     * @param cpuFraction     measured CPU load as a fraction in
     *                        {@code [0.0, +∞)}; values above {@code 1.0}
     *                        indicate the deadline was missed
     */
    record GraphOverload(String offendingNodeId, double cpuFraction) implements XrunEvent {
        public GraphOverload {
            Objects.requireNonNull(offendingNodeId, "offendingNodeId must not be null");
            if (Double.isNaN(cpuFraction) || cpuFraction < 0.0) {
                throw new IllegalArgumentException(
                        "cpuFraction must be non-negative and finite: " + cpuFraction);
            }
        }

        /**
         * {@inheritDoc}
         *
         * <p>{@code GraphOverload} is not tied to a single frame, so it
         * always returns {@code -1}.</p>
         */
        @Override
        public long frameIndex() {
            return -1L;
        }
    }
}
