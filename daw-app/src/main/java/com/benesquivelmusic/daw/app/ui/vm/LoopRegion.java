package com.benesquivelmusic.daw.app.ui.vm;

/**
 * Immutable view-model projection of the transport's loop region.
 *
 * <p>{@link TransportVM} exposes the loop as one
 * {@link javafx.beans.property.ObjectProperty ObjectProperty&lt;LoopRegion&gt;}
 * so a control observes the enabled flag and both boundaries as a single atomic
 * value rather than three loosely-coupled properties (Control Synchronization
 * Design Book §3.3, §5.2 "Toggle loop → TransportVM.loopRegion").</p>
 *
 * @param enabled      whether loop playback is currently enabled
 * @param startInBeats loop start position in beats (must be {@code >= 0})
 * @param endInBeats   loop end position in beats (must be {@code > startInBeats})
 */
public record LoopRegion(boolean enabled, double startInBeats, double endInBeats) {

    /** Creates a loop region, validating the boundary ordering. */
    public LoopRegion {
        if (startInBeats < 0) {
            throw new IllegalArgumentException("loop start must not be negative: " + startInBeats);
        }
        if (endInBeats <= startInBeats) {
            throw new IllegalArgumentException(
                    "loop end must be greater than loop start: start=" + startInBeats + ", end=" + endInBeats);
        }
    }
}
