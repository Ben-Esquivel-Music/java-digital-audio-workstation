package com.benesquivelmusic.daw.core.analysis;

/**
 * Availability of one physical input source in one capture generation.
 *
 * <p>Allocated before subscribing to the source and shared by its track
 * monitors. A terminal publisher signal only updates this generation's
 * state, so it cannot silence monitors rebound to a replacement stream.</p>
 */
public final class InputSourceAvailability {
    private volatile boolean available = true;

    /** Returns whether this generation's source can still deliver input. */
    public boolean isAvailable() {
        return available;
    }

    /** Permanently marks this generation's source as unavailable. */
    public void markUnavailable() {
        available = false;
    }
}
