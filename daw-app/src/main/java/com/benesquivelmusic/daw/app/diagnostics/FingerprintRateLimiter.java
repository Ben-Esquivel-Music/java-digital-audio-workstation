package com.benesquivelmusic.daw.app.diagnostics;

import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * The per-fingerprint rate limit of the failure funnel (story 336, Goals
 * bullet 4; Failure Surfacing book §6.2).
 *
 * <p>The window is {@link #WINDOW_NANOS} (30 s) on a monotonic clock, <em>fixed
 * and anchored at the last notice</em> — {@code now - shownAt >= WINDOW_NANOS},
 * the comparison of the track-budget precedent ({@code TrackBudgetUiBinding.java:56},
 * {@code :184}) — never sliding on the last occurrence, so a failure thrown
 * every frame still re-surfaces every 30 s. An occurrence with no window open
 * {@linkplain Verdict.Surface surfaces}: one log record, one toast. An
 * occurrence inside the window is {@linkplain Verdict.Counted counted}: no
 * log record, no toast, no history entry. A fingerprint whose window ended
 * with occurrences counted, and that then stayed quiet for a window after its
 * last occurrence, owes one {@linkplain #takeDueTails() tail}: a history-only
 * entry and one log line that account for them.</p>
 *
 * <p>The decision runs on the failing thread, before anything is posted to
 * the FX thread, so a storm never floods the dispatcher. State lasts for the
 * session, at most {@link #MAX_FINGERPRINTS} fingerprints, the least recently
 * seen evicted first. An evicted fingerprint's owed tail goes to the log only
 * (it is returned in {@link Verdict.Surface#evicted()}).</p>
 *
 * <p><strong>Accounting.</strong> The log accounts for every occurrence
 * counted before its last write: each surfaced record carries the running
 * total, and each counted occurrence is in exactly one place — a tail line
 * (when its tail is due, when its fingerprint is evicted, or when
 * {@link #takeEveryTail()} drains it as the toasts are disarmed and, with the
 * exit hold active, as the log closes) or the next surfaced record's count of
 * occurrences not logged individually. Occurrences counted after that last
 * write — with the exit hold inactive, every one counted after the
 * disarming — and the counts not yet written when the process ends without
 * the JDK's shutdown reset are in neither the log nor the history: per
 * fingerprint, at most the occurrences inside one window. The drain at the
 * disarming takes tails that are not yet due, so a tail line can precede the
 * notice record of the same fingerprint that another thread is still
 * writing: that thread's occurrence surfaced before the drain, and the tail
 * accounts for occurrences counted after it. The order in the log is then not
 * the order of the occurrences; the counts stay exact. The history's
 * accounting — the newest notice's running total plus the tail counts after
 * it equals the occurrences — holds for a fingerprint only while its
 * notices and tails reach the history and are still in it (the history keeps
 * its newest entries), and so not in these cases among others: its
 * fingerprint was evicted (its tail went to the log only); its notice came
 * before the toasts were armed and was summarised in the arm-time summary,
 * replaced by a FATAL fact's toast, or only counted beyond the pre-arm buffer
 * (its notice never entered the history); or the toasts were disarmed while
 * it owed a tail (that tail went to the log only), or before it occurred.</p>
 *
 * <p><strong>Rule:</strong> nothing is logged, formatted or
 * recorded into the history while this limiter's monitor is held; every
 * method here only reads and writes its own fields under it, and returns what
 * the caller writes after leaving it.</p>
 */
public final class FingerprintRateLimiter {

    /**
     * The window: 30 s, declared here with the value of the
     * track-budget precedent {@code TrackBudgetUiBinding.NOTIFICATION_THROTTLE_NANOS}
     * ({@code TrackBudgetUiBinding.java:56}, compared at {@code :184}).
     */
    public static final long WINDOW_NANOS = 30_000_000_000L;

    /** The most fingerprints kept; the least recently seen is evicted beyond it. */
    static final int MAX_FINGERPRINTS = 256;

    private final LongSupplier clockNanos;
    private final InstantSource wallClock;
    private final Object lock = new Object();
    /** Access-ordered: the eldest entry is the least recently seen. Guarded by {@link #lock}. */
    private final LinkedHashMap<String, State> states = new LinkedHashMap<>(64, 0.75f, true);
    /**
     * No tail is due before this {@link #clockNanos} reading. A lower bound,
     * never later than the true due time; {@link #takeDueTails()} recomputes it
     * exactly. Read without the lock by the pulse participant.
     */
    private volatile long nextTailDueNanos = Long.MAX_VALUE;

    /**
     * @param clockNanos the monotonic clock the window is measured on ({@code System::nanoTime} in production)
     * @param wallClock  the wall clock first-seen and last-seen times are read from
     */
    public FingerprintRateLimiter(LongSupplier clockNanos, InstantSource wallClock) {
        this.clockNanos = Objects.requireNonNull(clockNanos, "clockNanos must not be null");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock must not be null");
    }

    /**
     * The monotonic clock this limiter measures its window on — the very
     * {@link LongSupplier} it was built with, so the funnel's pulse participant
     * compares {@link #nextTailDueNanos()} against the same clock.
     *
     * @return the clock's reading now
     */
    public long nanoTime() {
        return clockNanos.getAsLong();
    }

    /**
     * Decides one occurrence of {@code fingerprint}.
     *
     * @param fingerprint    the occurrence's fingerprint
     * @param guardedMessage the occurrence's message, read guardedly; the tail line names the last one
     * @return {@link Verdict.Surface} when no window is open, else {@link Verdict.Counted}
     */
    public Verdict occur(Fingerprint fingerprint, String guardedMessage) {
        Objects.requireNonNull(fingerprint, "fingerprint must not be null");
        Objects.requireNonNull(guardedMessage, "guardedMessage must not be null");
        long now = clockNanos.getAsLong();
        Instant wall = wallClock.instant();
        String key = fingerprint.key();
        synchronized (lock) {
            State state = states.get(key);
            if (state == null) {
                state = new State(fingerprint, wall, now, guardedMessage);
                states.put(key, state);
                Optional<Tail> evicted = evictBeyondCapacity();
                return new Verdict.Surface(1, wall, wall, 0, evicted);
            }
            state.total++;
            state.lastSeenNanos = now;
            state.lastSeen = wall;
            state.lastMessage = guardedMessage;
            if (now - state.shownAtNanos >= WINDOW_NANOS) {
                long notLogged = state.suppressedSinceNotice;
                state.suppressedSinceNotice = 0;
                state.shownAtNanos = now;
                return new Verdict.Surface(state.total, state.firstSeen, wall, notLogged, Optional.empty());
            }
            if (state.suppressedSinceNotice == 0) {
                state.firstSuppressed = wall;
            }
            state.suppressedSinceNotice++;
            long due = now + WINDOW_NANOS;
            if (due < nextTailDueNanos) {
                nextTailDueNanos = due;
            }
            return new Verdict.Counted(state.total);
        }
    }

    /**
     * Keeps the base sentence of the notice just shown for {@code fingerprint},
     * which its tail repeats. The base sentence is formatted only for a
     * surfaced occurrence, after this limiter's decision and outside its
     * monitor.
     *
     * @param fingerprint the fingerprint that surfaced
     * @param baseMessage its base sentence
     */
    public void noticeShown(Fingerprint fingerprint, String baseMessage) {
        Objects.requireNonNull(baseMessage, "baseMessage must not be null");
        synchronized (lock) {
            State state = states.get(fingerprint.key());
            if (state != null) {
                state.baseMessage = baseMessage;
            }
        }
    }

    /**
     * Takes every tail now due: each fingerprint with occurrences counted since
     * its last notice whose last occurrence is at least a window old. Each is
     * returned once; its counted occurrences start again from zero.
     *
     * @return the due tails, possibly none
     */
    public List<Tail> takeDueTails() {
        long now = clockNanos.getAsLong();
        List<Tail> due = new ArrayList<>(0);
        synchronized (lock) {
            long next = Long.MAX_VALUE;
            for (State state : states.values()) {
                if (state.suppressedSinceNotice == 0) {
                    continue;
                }
                if (now - state.lastSeenNanos >= WINDOW_NANOS) {
                    due.add(state.tail());
                    state.suppressedSinceNotice = 0;
                } else {
                    next = Math.min(next, state.lastSeenNanos + WINDOW_NANOS);
                }
            }
            nextTailDueNanos = next;
        }
        return due;
    }

    /**
     * Takes every tail still owed, due or not: each fingerprint with
     * occurrences counted since its last notice. Each is returned once; its
     * counted occurrences start again from zero, and no tail is owed after
     * this until an occurrence is counted again. The funnel writes these to
     * the log only, when its toasts are disarmed and when the log closes, so
     * that every occurrence counted before the log's last write is accounted
     * for (story 336; the class Javadoc's "Accounting").
     *
     * @return the owed tails, possibly none
     */
    public List<Tail> takeEveryTail() {
        List<Tail> owed = new ArrayList<>(0);
        synchronized (lock) {
            for (State state : states.values()) {
                if (state.suppressedSinceNotice > 0) {
                    owed.add(state.tail());
                    state.suppressedSinceNotice = 0;
                }
            }
            nextTailDueNanos = Long.MAX_VALUE;
        }
        return owed;
    }

    /**
     * When the next tail may be due, on {@link #nanoTime()}'s clock; a cheap
     * volatile read for the pulse participant. Never later than the true due
     * time; {@link Long#MAX_VALUE} when none is owed.
     *
     * @return the earliest reading at which a tail may be due
     */
    public long nextTailDueNanos() {
        return nextTailDueNanos;
    }

    /**
     * A snapshot of every live fingerprint's total occurrences.
     *
     * @return fingerprint key → total
     */
    public Map<String, Long> totals() {
        Map<String, Long> totals = new LinkedHashMap<>();
        synchronized (lock) {
            for (Map.Entry<String, State> entry : states.entrySet()) {
                totals.put(entry.getKey(), entry.getValue().total);
            }
        }
        return Map.copyOf(totals);
    }

    /** Evicts the least recently seen fingerprint beyond capacity. Caller holds {@link #lock}. */
    private Optional<Tail> evictBeyondCapacity() {
        if (states.size() <= MAX_FINGERPRINTS) {
            return Optional.empty();
        }
        Iterator<State> eldest = states.values().iterator();
        State evicted = eldest.next();
        eldest.remove();
        return evicted.suppressedSinceNotice > 0 ? Optional.of(evicted.tail()) : Optional.empty();
    }

    /** What one occurrence gets. */
    public sealed interface Verdict permits Verdict.Surface, Verdict.Counted {

        /**
         * No window was open: log it and show it.
         *
         * @param total                the occurrences so far, this one included
         * @param firstSeen            when the fingerprint was first seen
         * @param at                   when this occurrence happened
         * @param notLoggedSinceNotice the occurrences counted since the previous notice, not logged individually
         * @param evicted              the tail a fingerprint evicted to make room for this one owed, which
         *                             goes to the log only
         */
        record Surface(long total, Instant firstSeen, Instant at, long notLoggedSinceNotice,
                       Optional<Tail> evicted) implements Verdict {
            public Surface {
                Objects.requireNonNull(firstSeen, "firstSeen must not be null");
                Objects.requireNonNull(at, "at must not be null");
                Objects.requireNonNull(evicted, "evicted must not be null");
            }
        }

        /**
         * Inside the window: counted, nothing else.
         *
         * @param total the occurrences so far, this one included
         */
        record Counted(long total) implements Verdict {
        }
    }

    /**
     * Occurrences counted after a fingerprint's last notice that no later
     * notice carried.
     *
     * @param fingerprint     the fingerprint
     * @param baseMessage     the base sentence of its last notice (empty if none was recorded)
     * @param suppressed      how many occurrences
     * @param firstSuppressed when the first of them happened
     * @param last            when the last of them happened
     * @param lastMessage     the last one's guarded message
     */
    public record Tail(Fingerprint fingerprint, String baseMessage, long suppressed,
                       Instant firstSuppressed, Instant last, String lastMessage) {
        public Tail {
            Objects.requireNonNull(fingerprint, "fingerprint must not be null");
            Objects.requireNonNull(baseMessage, "baseMessage must not be null");
            Objects.requireNonNull(firstSuppressed, "firstSuppressed must not be null");
            Objects.requireNonNull(last, "last must not be null");
            Objects.requireNonNull(lastMessage, "lastMessage must not be null");
        }
    }

    /** One fingerprint's state. Guarded by the limiter's lock. */
    private static final class State {
        private final Fingerprint fingerprint;
        private final Instant firstSeen;
        private long total = 1;
        private long shownAtNanos;
        private long lastSeenNanos;
        private Instant lastSeen;
        private long suppressedSinceNotice;
        private Instant firstSuppressed;
        private String baseMessage = "";
        private String lastMessage;

        State(Fingerprint fingerprint, Instant firstSeen, long nowNanos, String message) {
            this.fingerprint = fingerprint;
            this.firstSeen = firstSeen;
            this.shownAtNanos = nowNanos;
            this.lastSeenNanos = nowNanos;
            this.lastSeen = firstSeen;
            this.firstSuppressed = firstSeen;
            this.lastMessage = message;
        }

        Tail tail() {
            return new Tail(fingerprint, baseMessage, suppressedSinceNotice, firstSuppressed, lastSeen, lastMessage);
        }
    }
}
