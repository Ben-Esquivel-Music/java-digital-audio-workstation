package com.benesquivelmusic.daw.core.persistence;

/**
 * A writer of durable application files whose writes still pending the
 * application's exit waits for, under the one deadline of
 * {@link ProjectManager#awaitWritesBeforeExit()} (story 334, studio ruling
 * E9: an install that reported success survives an immediate quit). The
 * installed-plugin registry's store ({@link InstalledPluginStore}) is one;
 * {@link ProjectManager#alsoAwaitBeforeExit(PendingWrites)} registers it.
 */
public interface PendingWrites {

    /**
     * Whether a write handed to this writer has not finished yet.
     *
     * @return {@code true} while a write is pending
     */
    boolean pending();

    /**
     * Waits until every write handed to this writer before the call has
     * finished, or until {@code deadlineNanos}. It completes, cancels and
     * interrupts nothing.
     *
     * @param deadlineNanos the deadline, on the {@link System#nanoTime()} scale
     * @return whether those writes finished before the deadline
     * @throws InterruptedException if the waiting thread is interrupted
     */
    boolean awaitUntil(long deadlineNanos) throws InterruptedException;
}
