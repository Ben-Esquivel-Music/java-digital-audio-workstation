package com.benesquivelmusic.daw.core.persistence.journal;

/**
 * Story 298 — JavaFX-free callback seam by which the {@link JournalWriter}
 * reports its queue depth and disk-backpressure level.
 *
 * <p>The daw-app status strip implements this interface and marshals the
 * callbacks onto the FX application thread (the journal never touches
 * JavaFX). Both methods have no-op defaults so an embedding that does not
 * care about journal telemetry can pass {@code null} (treated as a no-op)
 * or an empty implementation.</p>
 *
 * <p>Callbacks are invoked on the journal writer's virtual thread (for
 * queue-count and drain-driven backpressure transitions) or on the
 * producer thread (for the first spill to overflow). Implementations must
 * therefore be cheap and non-blocking, and must hand any UI work off to
 * the FX thread themselves.</p>
 */
public interface JournalListener {

    /**
     * Invoked after a batch is durably appended, reporting the running
     * count of records journaled since the last successful checkpoint
     * (the count {@code onCheckpointSucceeded()} resets to 0). The UI uses
     * this to show "N unsaved edits captured".
     *
     * @param queuedSinceCheckpoint records journaled since the last checkpoint
     */
    default void onQueuedCountChanged(int queuedSinceCheckpoint) {
        // no-op by default
    }

    /**
     * Invoked when the journal's disk-backpressure level changes.
     *
     * @param level the new backpressure level
     */
    default void onBackpressure(Backpressure level) {
        // no-op by default
    }
}
