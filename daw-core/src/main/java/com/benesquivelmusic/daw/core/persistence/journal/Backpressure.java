package com.benesquivelmusic.daw.core.persistence.journal;

/**
 * Story 298 — the journal's disk-backpressure level, surfaced to the UI
 * (via {@link JournalListener#onBackpressure}) so the status strip can warn
 * the user that the disk is falling behind the session's edit rate.
 *
 * <p>The levels are monotonic in severity:</p>
 * <ul>
 *   <li>{@link #NORMAL} — the hand-off queue is keeping up; nothing has
 *       spilled to the overflow deque (or the overflow has fully drained).</li>
 *   <li>{@link #BUFFERING} — the bounded queue filled and records are
 *       spilling to the in-memory overflow deque, but the overflow byte
 *       budget has not been exceeded.</li>
 *   <li>{@link #UNRESPONSIVE} — the overflow byte budget
 *       ({@link JournalConfig#overflowMaxBytes()}) has been exceeded; the
 *       disk is effectively unresponsive. Records are <em>still</em>
 *       buffered (never dropped) and the user may invoke a force-flush.</li>
 * </ul>
 */
public enum Backpressure {

    /** Queue keeping up; no records spilled (or overflow fully drained). */
    NORMAL,

    /** Bounded queue full; records spilling to the in-memory overflow deque. */
    BUFFERING,

    /** Overflow byte budget exceeded; disk is unresponsive (no drop). */
    UNRESPONSIVE
}
