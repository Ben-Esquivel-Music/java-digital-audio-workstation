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
 * <p>Each callback runs on the thread whose work caused it: the thread that
 * appends a batch — the journal writer's virtual thread, a
 * {@link JournalWriter#forceFlush()} thread, or the thread running a
 * close's or a seal's final drain — for the queue count and
 * {@link Backpressure#NORMAL}; the thread that reports a checkpoint for the
 * queue count once that checkpoint's marker is accepted; and the thread
 * that offers a record for {@link Backpressure#BUFFERING} and
 * {@link Backpressure#UNRESPONSIVE}. Implementations must therefore be
 * cheap and non-blocking, and must hand any UI work off to the FX thread
 * themselves.</p>
 */
public interface JournalListener {

    /**
     * Reports how many mutation records the journal has durably appended that
     * no accepted checkpoint marker covers yet: invoked after a batch is
     * durably appended, and after a checkpoint's marker is accepted, which
     * retires the records its capture covers
     * ({@code JournalWriter.onCheckpointSucceeded}). A checkpoint whose marker
     * cannot be built or is refused retires nothing, so the count stands. The
     * status strip's Journal cell shows this count.
     *
     * @param queuedSinceCheckpoint the mutation records durably appended that
     *                              no accepted checkpoint marker covers yet
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
