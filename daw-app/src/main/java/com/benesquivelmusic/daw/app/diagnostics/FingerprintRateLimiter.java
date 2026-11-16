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
 * seen evicted first — one whose escalation is latched only when every kept
 * fingerprint's is (story 337, ruling E44). An evicted fingerprint's owed
 * tail goes to the log only (it is returned in
 * {@link Verdict.Surface#evicted()}).</p>
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
 * <p><strong>Story 337 additions</strong> (Failure Surfacing book §6.2, and
 * its story-336 as-built note):</p>
 * <ul>
 *   <li><em>Weighted occurrences</em> ({@link #occur(Fingerprint, String, long)},
 *       story 337, ruling E39): one audio drain frame's counter delta of n
 *       callback errors counts as n in the totals, the re-shows and the
 *       tails.</li>
 *   <li><em>The isolated-consumer policy</em> ({@link #occurIsolatedConsumer},
 *       story 337, ruling E35): a pulse item or bus subscriber the dispatcher
 *       or the bus isolated is {@linkplain Verdict.LogOnly logged once} at its
 *       first occurrence, counted at its second, and surfaced at its
 *       {@value #ISOLATED_CONSUMER_THRESHOLD}rd, which anchors its window;
 *       after that the window rule above applies. A fingerprint that never
 *       surfaced owes a log-only tail ({@link Tail#surfaced()}).</li>
 *   <li><em>The escalation latch</em> ({@link #occurEscalating},
 *       {@link #clearEscalation}, story 337, ruling E44): a
 *       sustained real-time fault or a halt surfaces at once, whatever the
 *       window, and from then on its fingerprint's occurrences are only
 *       counted — no re-show — until the latch is cleared.</li>
 *   <li>A notice's severity and action are kept with its fingerprint
 *       ({@link #noticeShown(Fingerprint, String, FailureFact.Severity, NoticeAction)}),
 *       so its tail is recorded at that level and with that action (story
 *       337, ruling E43).</li>
 * </ul>
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

    /**
     * The most fingerprints kept; beyond it the least recently seen is
     * evicted — one whose escalation is latched only when every kept
     * fingerprint's is (story 337, ruling E44).
     */
    static final int MAX_FINGERPRINTS = 256;

    /**
     * The occurrence of an isolated consumer's fingerprint that first
     * surfaces it: the third since it was first seen (story 337, ruling E35;
     * book §3.3 "Log once per offender; toast on persistence").
     */
    public static final int ISOLATED_CONSUMER_THRESHOLD = 3;

    private final LongSupplier clockNanos;
    private final InstantSource wallClock;
    private final Object lock = new Object();
    /**
     * Kept in the order last seen: an occurrence moves its fingerprint to the
     * end, a lookup never does (story 337, ruling E88), so the eldest entry is
     * the least recently seen. Guarded by {@link #lock}.
     */
    private final LinkedHashMap<String, State> states = new LinkedHashMap<>(64);
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
        return occur(fingerprint, guardedMessage, 1);
    }

    /**
     * Decides {@code weight} occurrences of {@code fingerprint} at once — one
     * audio drain frame's counter delta (story 337, ruling E39). They count
     * as {@code weight} in the total, in a re-show's running total and in a
     * tail. While the fingerprint's escalation ({@link #occurEscalating}) is
     * latched they are only counted (story 337, ruling E44).
     *
     * @param fingerprint    the occurrences' fingerprint
     * @param guardedMessage the newest occurrence's message, read guardedly; the tail line names the last one
     * @param weight         how many occurrences; at least 1
     * @return {@link Verdict.Surface} when no window is open and no escalation is latched, else
     *         {@link Verdict.Counted}; a surface is the fingerprint's first exactly when its
     *         {@link Verdict.Surface#total()} equals {@code weight}
     */
    public Verdict occur(Fingerprint fingerprint, String guardedMessage, long weight) {
        Objects.requireNonNull(fingerprint, "fingerprint must not be null");
        Objects.requireNonNull(guardedMessage, "guardedMessage must not be null");
        requireWeight(weight);
        long now = clockNanos.getAsLong();
        Instant wall = wallClock.instant();
        String key = fingerprint.key();
        synchronized (lock) {
            State state = states.get(key);
            if (state == null) {
                state = new State(fingerprint, wall, now, guardedMessage, weight);
                state.surfaced = true;
                states.put(key, state);
                Optional<Tail> evicted = evictBeyondCapacity();
                return new Verdict.Surface(weight, wall, wall, 0, evicted);
            }
            seen(key, state, weight, now, wall, guardedMessage);
            if (!state.escalated && now - state.shownAtNanos >= WINDOW_NANOS) {
                return state.surface(now, wall);
            }
            return count(state, weight, now, wall);
        }
    }

    /**
     * Decides one occurrence of an isolated consumer — a pulse item or a bus
     * subscriber that threw and was contained (story 337, ruling E35): its
     * first occurrence is {@linkplain Verdict.LogOnly logged once}; later ones
     * are counted until its total reaches {@link #ISOLATED_CONSUMER_THRESHOLD},
     * which surfaces it and anchors its window; after that the window rule of
     * {@link #occur(Fingerprint, String)} applies.
     *
     * @param fingerprint    the occurrence's fingerprint
     * @param guardedMessage the occurrence's message, read guardedly; the tail line names the last one
     * @return {@link Verdict.LogOnly} for the first occurrence, {@link Verdict.Surface} at the threshold and
     *         after each window, else {@link Verdict.Counted}
     */
    public Verdict occurIsolatedConsumer(Fingerprint fingerprint, String guardedMessage) {
        Objects.requireNonNull(fingerprint, "fingerprint must not be null");
        Objects.requireNonNull(guardedMessage, "guardedMessage must not be null");
        long now = clockNanos.getAsLong();
        Instant wall = wallClock.instant();
        String key = fingerprint.key();
        synchronized (lock) {
            State state = states.get(key);
            if (state == null) {
                state = new State(fingerprint, wall, now, guardedMessage, 1);
                // Logged at WARNING, so a log-only tail before any notice is written at WARNING too (story 337,
                // ruling E43); a FATAL isolated consumer takes occur(...) instead.
                state.severity = FailureFact.Severity.WARNING;
                states.put(key, state);
                return new Verdict.LogOnly(wall, evictBeyondCapacity());
            }
            seen(key, state, 1, now, wall, guardedMessage);
            if (!state.surfaced) {
                return state.total >= ISOLATED_CONSUMER_THRESHOLD
                        ? state.surface(now, wall)
                        : count(state, 1, now, wall);
            }
            if (!state.escalated && now - state.shownAtNanos >= WINDOW_NANOS) {
                return state.surface(now, wall);
            }
            return count(state, 1, now, wall);
        }
    }

    /**
     * Counts {@code weight} occurrences of {@code fingerprint}, latches its
     * escalation and surfaces them whatever the window: a sustained
     * real-time fault or a halt (story 337, rulings E41 and E44). Until
     * {@link #clearEscalation} its later occurrences are only counted.
     *
     * @param fingerprint    the occurrences' fingerprint
     * @param guardedMessage the newest occurrence's message, read guardedly
     * @param weight         how many occurrences; at least 1
     * @return the surface: the running total, when the fingerprint was first seen, and the occurrences
     *         counted since its previous notice that no record logged
     */
    public Verdict.Surface occurEscalating(Fingerprint fingerprint, String guardedMessage, long weight) {
        Objects.requireNonNull(fingerprint, "fingerprint must not be null");
        Objects.requireNonNull(guardedMessage, "guardedMessage must not be null");
        requireWeight(weight);
        long now = clockNanos.getAsLong();
        Instant wall = wallClock.instant();
        String key = fingerprint.key();
        synchronized (lock) {
            State state = states.get(key);
            if (state == null) {
                state = new State(fingerprint, wall, now, guardedMessage, weight);
                state.surfaced = true;
                state.escalated = true;
                states.put(key, state);
                return new Verdict.Surface(weight, wall, wall, 0, evictBeyondCapacity());
            }
            seen(key, state, weight, now, wall, guardedMessage);
            state.escalated = true;
            return state.surface(now, wall);
        }
    }

    /**
     * Records {@code weight} more occurrences of a kept fingerprint and moves
     * it to the end of the eviction order: only an occurrence refreshes a
     * fingerprint's age (story 337, ruling E88). Caller holds {@link #lock}.
     */
    private void seen(String key, State state, long weight, long now, Instant wall, String guardedMessage) {
        state.seen(weight, now, wall, guardedMessage);
        states.putLast(key, state);
    }

    /**
     * Clears {@code fingerprint}'s escalation once its fault has cleared
     * (story 337, rulings E41 and E44): its next occurrence follows the window
     * rule again, anchored at its last notice. Does not count as seeing the
     * fingerprint for the eviction order (story 337, ruling E88).
     *
     * @param fingerprint the fingerprint
     */
    public void clearEscalation(Fingerprint fingerprint) {
        Objects.requireNonNull(fingerprint, "fingerprint must not be null");
        synchronized (lock) {
            State state = states.get(fingerprint.key());
            if (state != null) {
                state.escalated = false;
            }
        }
    }

    /**
     * Whether {@code fingerprint}'s escalation is latched. Does not count as
     * seeing the fingerprint for the eviction order (story 337, ruling E88).
     *
     * @param fingerprint the fingerprint
     * @return {@code true} between {@link #occurEscalating} and {@link #clearEscalation}
     */
    public boolean isEscalated(Fingerprint fingerprint) {
        Objects.requireNonNull(fingerprint, "fingerprint must not be null");
        synchronized (lock) {
            State state = states.get(fingerprint.key());
            return state != null && state.escalated;
        }
    }

    /**
     * Whether {@code fingerprint} is kept: it occurred, and was not evicted
     * since. The audio drain's fold of a first worker failure into a first
     * render failure asks this (story 337, ruling E27). Does not count as
     * seeing the fingerprint for the eviction order.
     *
     * @param fingerprint the fingerprint
     * @return whether it has been seen
     */
    public boolean hasSeen(Fingerprint fingerprint) {
        Objects.requireNonNull(fingerprint, "fingerprint must not be null");
        synchronized (lock) {
            return states.containsKey(fingerprint.key());
        }
    }

    /**
     * Keeps the base sentence of the notice just shown for {@code fingerprint},
     * which its tail repeats; the tail is recorded at ERROR with the funnel's
     * Open Log Folder action. The base sentence is formatted only for a
     * surfaced occurrence, after this limiter's decision and outside its
     * monitor. Does not count as seeing the fingerprint for the eviction
     * order (story 337, ruling E88).
     *
     * @param fingerprint the fingerprint that surfaced
     * @param baseMessage its base sentence
     */
    public void noticeShown(Fingerprint fingerprint, String baseMessage) {
        Objects.requireNonNull(baseMessage, "baseMessage must not be null");
        keepNotice(fingerprint, baseMessage, FailureFact.Severity.ERROR, Optional.empty());
    }

    /**
     * Keeps the base sentence, severity and action of the notice just shown
     * for {@code fingerprint}: its tail repeats the sentence, is recorded at
     * that severity's level and carries that action (story 337, ruling E43).
     * Does not count as seeing the fingerprint for the eviction order (story
     * 337, ruling E88).
     *
     * @param fingerprint the fingerprint that surfaced
     * @param baseMessage its base sentence
     * @param severity    the notice's severity
     * @param action      the notice's action
     */
    public void noticeShown(Fingerprint fingerprint, String baseMessage, FailureFact.Severity severity,
                            NoticeAction action) {
        Objects.requireNonNull(baseMessage, "baseMessage must not be null");
        Objects.requireNonNull(severity, "severity must not be null");
        Objects.requireNonNull(action, "action must not be null");
        keepNotice(fingerprint, baseMessage, severity, Optional.of(action));
    }

    private void keepNotice(Fingerprint fingerprint, String baseMessage, FailureFact.Severity severity,
                            Optional<NoticeAction> action) {
        synchronized (lock) {
            State state = states.get(fingerprint.key());
            if (state != null) {
                state.baseMessage = baseMessage;
                state.severity = severity;
                state.action = action;
            }
        }
    }

    /** Counts occurrences inside the window, or under a latch. Caller holds {@link #lock}. */
    private Verdict.Counted count(State state, long weight, long now, Instant wall) {
        if (state.suppressedSinceNotice == 0) {
            state.firstSuppressed = wall;
        }
        state.suppressedSinceNotice += weight;
        long due = now + WINDOW_NANOS;
        if (due < nextTailDueNanos) {
            nextTailDueNanos = due;
        }
        return new Verdict.Counted(state.total);
    }

    private static void requireWeight(long weight) {
        if (weight < 1) {
            throw new IllegalArgumentException("weight must be at least 1: " + weight);
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

    /**
     * Evicts the least recently seen fingerprint beyond capacity whose
     * escalation is not latched; only when every one is latched, the least
     * recently seen. Caller holds {@link #lock}.
     */
    private Optional<Tail> evictBeyondCapacity() {
        if (states.size() <= MAX_FINGERPRINTS) {
            return Optional.empty();
        }
        // Story 337, ruling E44: an escalated fingerprint is never forgotten while its fact is latched.
        Iterator<State> candidates = states.values().iterator();
        while (candidates.hasNext()) {
            State candidate = candidates.next();
            if (!candidate.escalated) {
                candidates.remove();
                return owedTailOf(candidate);
            }
        }
        Iterator<State> eldest = states.values().iterator();
        State evicted = eldest.next();
        eldest.remove();
        return owedTailOf(evicted);
    }

    /** The tail an evicted fingerprint owes, if it counted occurrences since its last notice. */
    private static Optional<Tail> owedTailOf(State evicted) {
        return evicted.suppressedSinceNotice > 0 ? Optional.of(evicted.tail()) : Optional.empty();
    }

    /** What one occurrence gets. */
    public sealed interface Verdict permits Verdict.Surface, Verdict.Counted, Verdict.LogOnly {

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

        /**
         * An isolated consumer's first occurrence: log it once, with its stack,
         * and show nothing (story 337, ruling E35).
         *
         * @param at      when it happened, which is when the fingerprint was first seen
         * @param evicted the tail a fingerprint evicted to make room for this one owed, which goes to the
         *                log only
         */
        record LogOnly(Instant at, Optional<Tail> evicted) implements Verdict {
            public LogOnly {
                Objects.requireNonNull(at, "at must not be null");
                Objects.requireNonNull(evicted, "evicted must not be null");
            }
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
     * @param severity        the severity of its last notice, which the tail is recorded at (ERROR when
     *                        none was recorded; story 337, ruling E43)
     * @param action          the action of its last notice, if the caller supplied one; the funnel's Open
     *                        Log Folder otherwise
     * @param surfaced        whether the fingerprint ever surfaced: a tail of one that never did — an
     *                        isolated consumer below its threshold — is log-only (story 337, ruling E35)
     */
    public record Tail(Fingerprint fingerprint, String baseMessage, long suppressed,
                       Instant firstSuppressed, Instant last, String lastMessage,
                       FailureFact.Severity severity, Optional<NoticeAction> action, boolean surfaced) {
        public Tail {
            Objects.requireNonNull(fingerprint, "fingerprint must not be null");
            Objects.requireNonNull(baseMessage, "baseMessage must not be null");
            Objects.requireNonNull(firstSuppressed, "firstSuppressed must not be null");
            Objects.requireNonNull(last, "last must not be null");
            Objects.requireNonNull(lastMessage, "lastMessage must not be null");
            Objects.requireNonNull(severity, "severity must not be null");
            Objects.requireNonNull(action, "action must not be null");
        }
    }

    /** One fingerprint's state. Guarded by the limiter's lock. */
    private static final class State {
        private final Fingerprint fingerprint;
        private final Instant firstSeen;
        private long total;
        private long shownAtNanos;
        private long lastSeenNanos;
        private Instant lastSeen;
        private long suppressedSinceNotice;
        private Instant firstSuppressed;
        private String baseMessage = "";
        private String lastMessage;
        private FailureFact.Severity severity = FailureFact.Severity.ERROR;
        private Optional<NoticeAction> action = Optional.empty();
        /** Whether it ever surfaced (story 337, ruling E35). */
        private boolean surfaced;
        /** Whether its escalation is latched (story 337, ruling E44). */
        private boolean escalated;

        State(Fingerprint fingerprint, Instant firstSeen, long nowNanos, String message, long weight) {
            this.fingerprint = fingerprint;
            this.firstSeen = firstSeen;
            this.total = weight;
            this.shownAtNanos = nowNanos;
            this.lastSeenNanos = nowNanos;
            this.lastSeen = firstSeen;
            this.firstSuppressed = firstSeen;
            this.lastMessage = message;
        }

        /** Records {@code weight} more occurrences, the newest now. */
        void seen(long weight, long nowNanos, Instant wall, String message) {
            total += weight;
            lastSeenNanos = nowNanos;
            lastSeen = wall;
            lastMessage = message;
        }

        /** A notice now: anchors the window there and hands over what was counted since the last one. */
        Verdict.Surface surface(long nowNanos, Instant wall) {
            long notLogged = suppressedSinceNotice;
            suppressedSinceNotice = 0;
            shownAtNanos = nowNanos;
            surfaced = true;
            return new Verdict.Surface(total, firstSeen, wall, notLogged, Optional.empty());
        }

        Tail tail() {
            return new Tail(fingerprint, baseMessage, suppressedSinceNotice, firstSuppressed, lastSeen, lastMessage,
                    severity, action, surfaced);
        }
    }
}
