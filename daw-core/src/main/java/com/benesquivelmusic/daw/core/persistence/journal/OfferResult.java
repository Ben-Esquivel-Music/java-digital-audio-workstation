package com.benesquivelmusic.daw.core.persistence.journal;

/**
 * Story 298 — the outcome of a non-blocking {@link JournalWriter#offer}.
 *
 * <p>Crucially, none of these outcomes is "dropped": the journal never
 * loses a record regardless of disk state. The result merely tells the
 * caller which buffer absorbed the record (or that journaling is disabled).</p>
 */
public enum OfferResult {

    /** Journaling is disabled (preference off); the record was ignored. */
    DISABLED,

    /** Accepted into the bounded hand-off queue (the fast path). */
    QUEUED,

    /** Queue was full; the record was buffered in the overflow deque. */
    OVERFLOW,

    /**
     * Queue was full and the overflow byte budget has been exceeded; the
     * record was still buffered (no drop) but the journal has escalated to
     * {@link Backpressure#UNRESPONSIVE}.
     */
    ESCALATED
}
