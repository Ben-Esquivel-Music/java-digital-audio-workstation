package com.benesquivelmusic.daw.sdk.event;

/**
 * Strategy applied by an {@link EventBus} when a subscriber's bounded
 * buffer is full at the moment of a {@link EventBus#publish publish}.
 *
 * <p>Audio applications must never let a slow consumer (a UI subscriber,
 * a logging subscriber) push back into the audio thread. The default
 * for high-rate event types (meter updates, xruns) is therefore
 * {@link #DROP_OLDEST}: the oldest queued event is silently discarded
 * to make room for the newest one. For events whose loss would corrupt
 * application state (project-save lifecycle, transport transitions),
 * use {@link #BLOCK} so the publisher waits until buffer space is
 * available.</p>
 */
public enum OverflowStrategy {
    /**
     * Discard the oldest queued event so the new event can be enqueued
     * without blocking the publisher. Recommended for high-rate events
     * (meters, xruns) where freshness matters more than completeness.
     */
    DROP_OLDEST,
    /**
     * Block the publishing thread until buffer space is available.
     * Use only for low-rate critical events where loss is unacceptable.
     */
    BLOCK
}
