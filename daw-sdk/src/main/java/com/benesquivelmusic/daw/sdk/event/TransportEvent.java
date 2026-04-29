package com.benesquivelmusic.daw.sdk.event;

import java.time.Instant;
import java.util.Objects;

/**
 * Transport-state event hierarchy.
 *
 * <p>{@code TransportEvent} is the closed set of events emitted by the
 * playback engine when the transport changes state. Consumers
 * exhaustively pattern-match on the variants below; adding a new
 * variant forces every {@code switch} to be updated at compile time.</p>
 *
 * <p>Per the {@link DawEvent} contract, records carry only the minimal
 * identifying data &mdash; the post-change state is read from the
 * current {@link com.benesquivelmusic.daw.sdk.model.Project Project}
 * snapshot.</p>
 */
public sealed interface TransportEvent extends DawEvent
        permits TransportEvent.Started,
                TransportEvent.Stopped,
                TransportEvent.Seeked,
                TransportEvent.TempoChanged,
                TransportEvent.LoopChanged {

    /** Returns the wall-clock instant at which this event was produced. */
    @Override
    Instant timestamp();

    /**
     * Emitted when the transport begins playback or recording.
     *
     * @param positionFrames sample-frame position at which playback started; must be non-negative
     * @param timestamp      wall-clock instant of the event
     */
    record Started(long positionFrames, Instant timestamp) implements TransportEvent {
        public Started {
            if (positionFrames < 0L) {
                throw new IllegalArgumentException(
                        "positionFrames must be non-negative: " + positionFrames);
            }
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * Emitted when the transport stops playback or recording.
     *
     * @param positionFrames sample-frame position at which playback stopped; must be non-negative
     * @param timestamp      wall-clock instant of the event
     */
    record Stopped(long positionFrames, Instant timestamp) implements TransportEvent {
        public Stopped {
            if (positionFrames < 0L) {
                throw new IllegalArgumentException(
                        "positionFrames must be non-negative: " + positionFrames);
            }
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * Emitted when the play head jumps to a new position without
     * starting or stopping the transport.
     *
     * @param fromFrames previous sample-frame position; must be non-negative
     * @param toFrames   new sample-frame position; must be non-negative
     * @param timestamp  wall-clock instant of the event
     */
    record Seeked(long fromFrames, long toFrames, Instant timestamp) implements TransportEvent {
        public Seeked {
            if (fromFrames < 0L) {
                throw new IllegalArgumentException(
                        "fromFrames must be non-negative: " + fromFrames);
            }
            if (toFrames < 0L) {
                throw new IllegalArgumentException(
                        "toFrames must be non-negative: " + toFrames);
            }
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * Emitted when the project tempo changes.
     *
     * @param previousBpm previous tempo in beats per minute; must be positive and finite
     * @param newBpm      new tempo in beats per minute; must be positive and finite
     * @param timestamp   wall-clock instant of the event
     */
    record TempoChanged(double previousBpm, double newBpm, Instant timestamp) implements TransportEvent {
        public TempoChanged {
            if (Double.isNaN(previousBpm) || !Double.isFinite(previousBpm) || previousBpm <= 0.0) {
                throw new IllegalArgumentException(
                        "previousBpm must be positive and finite: " + previousBpm);
            }
            if (Double.isNaN(newBpm) || !Double.isFinite(newBpm) || newBpm <= 0.0) {
                throw new IllegalArgumentException(
                        "newBpm must be positive and finite: " + newBpm);
            }
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * Emitted when the loop region or loop-enabled flag changes.
     *
     * @param enabled    whether loop playback is now enabled
     * @param startFrames loop start position in sample frames; must be non-negative
     * @param endFrames   loop end position in sample frames; must be {@code >= startFrames}
     * @param timestamp  wall-clock instant of the event
     */
    record LoopChanged(boolean enabled, long startFrames, long endFrames, Instant timestamp)
            implements TransportEvent {
        public LoopChanged {
            if (startFrames < 0L) {
                throw new IllegalArgumentException(
                        "startFrames must be non-negative: " + startFrames);
            }
            if (endFrames < startFrames) {
                throw new IllegalArgumentException(
                        "endFrames must be >= startFrames: " + endFrames + " < " + startFrames);
            }
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }
}
