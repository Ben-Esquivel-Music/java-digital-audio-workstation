package com.benesquivelmusic.daw.app.ui;

/**
 * How a close ends the write-ahead journal of the folder it closes (story
 * 333; the studio engineer's rulings R3, R7, R8 and ruling 2 OQ-1): the
 * journal is first detached from the event bus — the close's point of no
 * return, so nothing is journaled after the closing checkpoint's capture —
 * and then ended one of three ways, on a worker. The application's one
 * implementation is {@link SessionJournalEnding}; the close protocol's
 * {@link ProjectFolderCloser} runs it.
 */
interface JournalEnding {

    /** How the journal of the closed folder ends. */
    enum JournalEnd {
        /** Sealed and certified clean: the session's {@code $session-seal}, then its manifest (ruling R3). */
        SEAL,
        /** Drained unsealed, its segments folded into the session manifest: the next open may offer recovery. */
        DRAIN_UNSEALED,
        /** Drained unsealed with no manifest written: a lost lock, or Save As's Continue (rulings R7, 2 OQ-1). */
        DRAIN_WITHOUT_MANIFEST
    }

    /**
     * Detaches the journal from the event bus, so no further event is
     * journaled. FX thread; idempotent; never blocks.
     */
    void detach();

    /**
     * Captures, on the FX thread, what ending the journal of the folder
     * being closed in {@code mode} needs — the open folder, the journal's
     * folder, the session to fold the segments into — and returns the work
     * that ends it, to be run on a worker: it does disk I/O and may wait
     * behind another close of the journal. The work touches the journal only
     * when it is still the journal of the folder being closed; otherwise its
     * journal part does nothing and returns at once (ruling 2 OQ-1,
     * constraint 2).
     *
     * @param mode how the journal ends
     * @return the work, run once, off the FX thread; a seal that fails
     *         throws {@link java.io.UncheckedIOException}
     */
    Runnable prepare(JournalEnd mode);
}
