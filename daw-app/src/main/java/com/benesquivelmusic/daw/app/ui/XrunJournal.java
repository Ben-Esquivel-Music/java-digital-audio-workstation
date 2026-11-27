package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.status.EngineHealthStatus;

import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The xrun log's store (story 338, amendment A15): the typed
 * entries the xrun feed writes on the engine-health monitor's thread, and the
 * exact counts the status strip's xrun cell shows, read on the FX thread.
 *
 * <p>An entry carries its {@link Detail} and the detail's arguments, never
 * formatted text: the surfaces format it from the message bundle (rulings
 * E20, E38 and E42). It carries how many occurrences it stands for (the "×n"
 * of ruling E20) and when the feed wrote it, a Resync entry the date of its
 * first request instead, both on the wall clock and as a
 * {@link System#nanoTime()} reading (rulings E40, E279 and E292).</p>
 *
 * <p>The count — the "Xruns N" of ruling E22 — is the sum of the entries
 * whose kind {@linkplain Kind#raisesTheCount() raises it}: gaps, discarded
 * blocks, driver overloads and resyncs (rulings E17 and E38). Late blocks are
 * counted apart and never raise it (ruling E17); shed and stall entries raise
 * nothing (rulings E19 and E42). The count restarts when the engine's xrun
 * detector is rebuilt, by a Settings apply, Restart Audio Engine or a reopen
 * the driver asked for ({@link #restartCount()}), and on the log's Clear
 * ({@link #clear()}), never otherwise (rulings E22, E71 and E148). The
 * journal keeps the newest {@value #MAX_ENTRIES} entries, and the gap
 * arrivals of the last {@value #RECENT_WINDOW_SECONDS} seconds, so a reader
 * can say how many of the count are recent ({@link #gapsSince(long)}).</p>
 *
 * <p>Apart from the log it keeps the resync marks owed to the take
 * ({@link #drainResyncMarks()}): one per date of the requests a resync entry
 * stands for, never cut by {@value #MAX_ENTRIES} nor by Clear, at most
 * {@value #RESYNC_MARKS_KEPT}, the oldest two merged beyond that at the earlier
 * date with their counts summed, so every resync counted reaches the take
 * once and no merge dates a request later than its mark did (rulings E225,
 * E226 and E279). With the dates the xrun feed says it holds, they are the
 * dates the take's frame history keeps a sample for
 * ({@link #resyncDatesOwed()}, ruling E291).</p>
 *
 * <p>Thread-safe: every instance method holds this journal's monitor briefly
 * and none waits, calls out or throws for a closed engine; the static
 * {@link #keepMerging} works only on its caller's own marks.</p>
 */
final class XrunJournal {

    /** The most entries the journal keeps, and the most one {@link #drain()} returns (ruling E22). */
    static final int MAX_ENTRIES = 100;

    /** How far back the gap arrivals are kept for {@link #gapsSince(long)} (ruling E22's 30 seconds). */
    static final int RECENT_WINDOW_SECONDS = 30;

    private static final long RECENT_WINDOW_NANOS = RECENT_WINDOW_SECONDS * 1_000_000_000L;

    /**
     * The most resync marks kept for the take, one per arrival: 60 s of the feed's 100 ms ticks at one request a
     * tick.
     */
    static final int RESYNC_MARKS_KEPT = 600;

    /** No dates: what {@link #resyncDatesOwed()} answers when none is owed. */
    static final long[] NO_DATES = new long[0];

    /** The kinds of ruling E20, with the resync of ruling E38. */
    enum Kind {
        GAP(true),
        DISCARDED(true),
        OVERLOAD(true),
        RESYNC(true),
        LATE(false),
        /** Shown as "Budget" (ruling E181): a track went over its CPU budget; nothing was shed. */
        SHED(false),
        STALL(false);

        private final boolean raisesTheCount;

        Kind(boolean raisesTheCount) {
            this.raisesTheCount = raisesTheCount;
        }

        /** Whether an entry of this kind adds its occurrences to the count (rulings E17, E19, E38, E42). */
        boolean raisesTheCount() {
            return raisesTheCount;
        }
    }

    /** What an entry says; each detail lists the arguments its entries carry, in order. */
    enum Detail {
        /** Gap: the device played silence because the output buffer was empty. No arguments. */
        OUTPUT_STARVED(Kind.GAP, 0),
        /** Gap: a device callback failed and one period was silenced. No arguments. */
        CALLBACK_FAILED(Kind.GAP, 0),
        /** Gap: audio processing failed and one block was silenced. No arguments. */
        PROCESSING_FAILED(Kind.GAP, 0),
        /** Gap: the output refused a block, which was not played. No arguments. */
        OUTPUT_REFUSED(Kind.GAP, 0),
        /**
         * Discarded: a rendered block never reached the converter — the output ring refused it, or a late callback
         * skipped it as an older queued block (ruling E72). No arguments.
         */
        BLOCK_NEVER_PLAYED(Kind.DISCARDED, 0),
        /** Overload: the audio driver reported an overload. No arguments. */
        DRIVER_OVERLOAD(Kind.OVERLOAD, 0),
        /** Resync: the audio driver lost sync and resynchronized. No arguments. */
        DRIVER_RESYNC(Kind.RESYNC, 0),
        /**
         * Late: a block took longer than its period. Arguments: the newest late block's time and the block
         * period, both in milliseconds as {@link Double}.
         */
        LATE_BLOCK(Kind.LATE, 2),
        /**
         * Budget: the CPU budget enforcer marked a track over its budget, whatever policy the budget names; its
         * processing was not reduced (ruling E181). Arguments: the track id ({@link String}) and the rolling
         * average as a whole percent of the block time ({@link Long}).
         */
        OVER_CPU_BUDGET(Kind.SHED, 2),
        /**
         * Budget: markings over a CPU budget that never reached the journal's listener because it fell behind the
         * enforcer (rulings E174 and E199): markings, not entries, since a dropped marking might not have started
         * an entry. Argument: how many ({@link Long}); the entry's count stays 1.
         */
        CPU_BUDGET_ENTRIES_NOT_LOGGED(Kind.SHED, 1),
        /**
         * Stall: the device stopped responding and was declared lost. Argument: its name ({@link String}),
         * empty when no device was known.
         */
        DEVICE_STOPPED_RESPONDING(Kind.STALL, 1);

        private final Kind kind;
        private final int arity;

        Detail(Kind kind, int arity) {
            this.kind = kind;
            this.arity = arity;
        }

        Kind kind() {
            return kind;
        }

        /** How many arguments an entry of this detail carries. */
        int arity() {
            return arity;
        }
    }

    /**
     * One entry of the log.
     *
     * @param detail       what it says
     * @param arguments    the detail's arguments, in the order its javadoc lists them
     * @param count        how many occurrences it stands for; at least 1
     * @param arrivedAt    when the feed wrote it, on the wall clock; for an entry {@link #appendResyncs} writes, the
     *                     date of its first request (rulings E279 and E292)
     * @param arrivedNanos the same time, as a {@link System#nanoTime()} reading
     */
    record Entry(Detail detail, List<Object> arguments, long count, Instant arrivedAt, long arrivedNanos) {
        Entry {
            Objects.requireNonNull(detail, "detail must not be null");
            arguments = List.copyOf(arguments);
            Objects.requireNonNull(arrivedAt, "arrivedAt must not be null");
            if (arguments.size() != detail.arity()) {
                throw new IllegalArgumentException(detail + " takes " + detail.arity() + " arguments: " + arguments);
            }
            if (count < 1L) {
                throw new IllegalArgumentException("count must be at least 1: " + count);
            }
        }

        Kind kind() {
            return detail.kind();
        }
    }

    /**
     * The journal's counts at one moment.
     *
     * @param gaps                    occurrences that raise the count since its last restart (ruling E22)
     * @param lateBlocks              late blocks since the count's last restart (ruling E23)
     * @param since                   when the count last restarted
     * @param underruns  whether the selected driver reports output starvation, as its last open stream answered;
     *                   {@link EngineHealthStatus.UnderrunReporting#NOT_ASKED} until a stream on it has answered
     *                   (rulings E18 and E116)
     */
    record Counts(long gaps, long lateBlocks, Instant since, EngineHealthStatus.UnderrunReporting underruns) {
        Counts {
            Objects.requireNonNull(since, "since must not be null");
            Objects.requireNonNull(underruns, "underruns must not be null");
        }

        /** @return whether the driver answered that it reports output starvation */
        boolean reportsOutputStarvation() {
            return underruns == EngineHealthStatus.UnderrunReporting.REPORTED;
        }
    }

    /**
     * Resyncs owed to the take's clock marks (rulings E40, E225, E226 and E279): requests dated at one arrival.
     *
     * @param arrivedNanos when they arrived, as a {@link System#nanoTime()} reading: their own arrival, the last
     *                     arrival the backend kept before them when it did not keep theirs, or, when the feed holds
     *                     no arrival for them, its read before the one that counted them, never later than the
     *                     driver's call that brought them (ruling E292); for an entry {@link #append(Detail, List,
     *                     long)} writes, its write; for marks merged past the bound, the earlier of the two
     * @param count        how many; at least 1
     */
    record ResyncMark(long arrivedNanos, long count) {
        ResyncMark {
            if (count < 1L) {
                throw new IllegalArgumentException("count must be at least 1: " + count);
            }
        }
    }

    /** One write of occurrences that raise the count, for {@link #gapsSince(long)}. */
    private record GapArrival(long nanos, long count) {
    }

    private final InstantSource wallClock;
    private final LongSupplier nanoClock;
    /** Guarded by this: the newest entries, oldest first. */
    private final Deque<Entry> entries = new ArrayDeque<>();
    /** Guarded by this: the entries written since the last {@link #drain()}, oldest first. */
    private final Deque<Entry> undrained = new ArrayDeque<>();
    /** Guarded by this: the recent writes that raised the count, oldest first. */
    private final Deque<GapArrival> recentGaps = new ArrayDeque<>();
    /** Guarded by this: the resync marks owed to the take, in the order written. */
    private final Deque<ResyncMark> resyncMarks = new ArrayDeque<>();
    /** Guarded by this: the dates of the resyncs the xrun feed holds and may yet write, as it last said (E291). */
    private long[] heldByTheFeed = NO_DATES;
    private long gaps;
    private long lateBlocks;
    private Instant since;
    private EngineHealthStatus.UnderrunReporting underruns = EngineHealthStatus.UnderrunReporting.NOT_ASKED;
    /** Advanced by every {@link #forgetUnderrunReporting()}. */
    private long underrunAnswers;
    /** Guarded by this: CPU budget markings lost before they reached the journal's listener, not written yet. */
    private long cpuBudgetEntriesLost;
    /** Guarded by this: how many times {@link #clear()} ran. */
    private long clears;

    /** A journal on the system's wall clock and {@link System#nanoTime()}. */
    XrunJournal() {
        this(InstantSource.system(), System::nanoTime);
    }

    /**
     * A journal on the given clocks.
     *
     * @param wallClock the wall clock of the entries and of the count's restart
     * @param nanoClock the monotonic clock of the entries ({@link System#nanoTime()} in production)
     */
    XrunJournal(InstantSource wallClock, LongSupplier nanoClock) {
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock must not be null");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock must not be null");
        this.since = wallClock.instant();
    }

    /**
     * Writes one entry standing for {@code count} occurrences, now.
     *
     * @param detail    what it says
     * @param arguments the detail's arguments
     * @param count     the occurrences; at least 1
     * @return the entry written
     */
    synchronized Entry append(Detail detail, List<Object> arguments, long count) {
        long nanos = nanoClock.getAsLong();
        Entry entry = new Entry(detail, arguments, count, wallClock.instant(), nanos);
        return append(entry, nanos,
                detail == Detail.DRIVER_RESYNC ? List.of(new ResyncMark(nanos, count)) : List.of());
    }

    /**
     * Writes one Resync entry standing for the driver resync requests
     * {@code arrivals} date, in request order (rulings E40, E62, E270 and
     * E279): its count is theirs, and its time, on both clocks, the arrival of
     * the first of them; each of {@code arrivals} is kept for the take as a
     * mark of its own. The 30-second figure counts them from now.
     *
     * @param arrivals the requests, as runs dated at one arrival each, in request order; not empty
     * @return the entry written
     */
    synchronized Entry appendResyncs(List<ResyncMark> arrivals) {
        if (arrivals.isEmpty()) {
            throw new IllegalArgumentException("a resync entry stands for at least one request");
        }
        long count = 0L;
        for (ResyncMark arrival : arrivals) {
            count += arrival.count();
        }
        long arrivedNanos = arrivals.getFirst().arrivedNanos();
        long nanos = nanoClock.getAsLong();
        Instant arrivedAt = wallClock.instant().minusNanos(Math.max(0L, nanos - arrivedNanos));
        return append(new Entry(Detail.DRIVER_RESYNC, List.of(), count, arrivedAt, arrivedNanos), nanos, arrivals);
    }

    private Entry append(Entry entry, long nanos, List<ResyncMark> marks) {
        Detail detail = entry.detail();
        long count = entry.count();
        keepNewest(entries, entry);
        keepNewest(undrained, entry);
        for (ResyncMark mark : marks) {
            keepMerging(resyncMarks, mark);
        }
        if (detail.kind().raisesTheCount()) {
            gaps += count;
            recentGaps.addLast(new GapArrival(nanos, count));
            while (nanos - recentGaps.getFirst().nanos() > RECENT_WINDOW_NANOS) {
                recentGaps.removeFirst();
            }
        } else if (detail.kind() == Kind.LATE) {
            lateBlocks += count;
        }
        return entry;
    }

    /**
     * Adds {@code mark} as the newest of {@code marks}, oldest first, keeping at most {@value #RESYNC_MARKS_KEPT}:
     * beyond that the oldest two become one at the earlier of their arrivals with their counts summed, so no request
     * is lost or dated later than it was (rulings E226 and E279). The caller owns {@code marks}.
     */
    static void keepMerging(Deque<ResyncMark> marks, ResyncMark mark) {
        marks.addLast(mark);
        if (marks.size() > RESYNC_MARKS_KEPT) {
            ResyncMark oldest = marks.removeFirst();
            ResyncMark next = marks.removeFirst();
            long earlier = oldest.arrivedNanos() - next.arrivedNanos() < 0L
                    ? oldest.arrivedNanos()
                    : next.arrivedNanos();
            marks.addFirst(new ResyncMark(earlier, oldest.count() + next.count()));
        }
    }

    /**
     * The resync marks owed to the take since the previous call, in the order written: every resync written
     * since, whatever {@value #MAX_ENTRIES} or {@link #clear()} removed from the log.
     *
     * @return the marks; empty when none is owed
     */
    synchronized List<ResyncMark> drainResyncMarks() {
        if (resyncMarks.isEmpty()) {
            return List.of();
        }
        List<ResyncMark> owed = List.copyOf(resyncMarks);
        resyncMarks.clear();
        return owed;
    }

    /**
     * Notes the dates of the resyncs the xrun feed holds and may yet write: the arrivals it has taken up, and the
     * date of the resyncs it carries without one (ruling E291). Replaces what it noted before.
     *
     * @param dates the dates, as {@link System#nanoTime()} readings in any order; copied
     */
    synchronized void noteResyncDatesHeld(long[] dates) {
        heldByTheFeed = dates.length == 0 ? NO_DATES : dates.clone();
    }

    /**
     * The dates the take may still be owed a resync mark at: each mark not drained yet and each date the xrun feed
     * holds, so the take's frame history keeps the sample at or before each (ruling E291).
     *
     * @return the dates, as {@link System#nanoTime()} readings in any order; {@link #NO_DATES} when none
     */
    synchronized long[] resyncDatesOwed() {
        if (resyncMarks.isEmpty() && heldByTheFeed.length == 0) {
            return NO_DATES;
        }
        long[] owed = new long[resyncMarks.size() + heldByTheFeed.length];
        int next = 0;
        for (ResyncMark mark : resyncMarks) {
            owed[next++] = mark.arrivedNanos();
        }
        System.arraycopy(heldByTheFeed, 0, owed, next, heldByTheFeed.length);
        return owed;
    }

    /**
     * Restarts the count, the late blocks and their time — the engine's xrun
     * detector was rebuilt, by a Settings apply, Restart Audio Engine or a
     * reopen the driver asked for (rulings E22 and E148). The entries stay.
     */
    synchronized void restartCount() {
        gaps = 0L;
        lateBlocks = 0L;
        recentGaps.clear();
        since = wallClock.instant();
    }

    /**
     * Notes that {@code lost} CPU budget markings never reached the
     * journal's listener (rulings E174, E184 and E199). Everything noted
     * since the last {@link #drain()} or {@link #entries()} is written as one
     * {@link Detail#CPU_BUDGET_ENTRIES_NOT_LOGGED} entry carrying the sum at
     * the next of them, the window's next read of the log, and dated then.
     * The journal only dates the entry; that the backlog of a loss is in the
     * journal ahead of its count is the listener's doing, which notes the
     * loss once it has delivered that backlog.
     *
     * @param lost how many; at least 1
     */
    synchronized void noteCpuBudgetEntriesLost(long lost) {
        if (lost < 1L) {
            throw new IllegalArgumentException("lost must be at least 1: " + lost);
        }
        cpuBudgetEntriesLost += lost;
    }

    /**
     * The log's Clear (ruling E22): restarts the count and forgets every entry, drained or not, and every loss, and
     * keeps the resync marks owed to the take (ruling E226).
     */
    synchronized void clear() {
        restartCount();
        entries.clear();
        undrained.clear();
        cpuBudgetEntriesLost = 0L;
        clears++;
    }

    /**
     * How many times the log was cleared: the CPU budget log's listener
     * writes a track's entry again after a Clear (ruling E199).
     *
     * @return the Clears so far
     */
    synchronized long clears() {
        return clears;
    }

    private void writeTheLostCpuBudgetEntries() {
        if (cpuBudgetEntriesLost == 0L) {
            return;
        }
        long nanos = nanoClock.getAsLong();
        append(new Entry(Detail.CPU_BUDGET_ENTRIES_NOT_LOGGED, List.of(cpuBudgetEntriesLost), 1L,
                wallClock.instant(), nanos), nanos, List.of());
        cpuBudgetEntriesLost = 0L;
    }

    /**
     * Notes whether the open stream's backend reports output starvation
     * (ruling E18).
     *
     * @param reports what the backend's drop counters say
     */
    synchronized void reportsOutputStarvation(boolean reports) {
        reportsOutputStarvation(reports, underrunAnswers);
    }

    /**
     * As {@link #reportsOutputStarvation(boolean)}, for an answer sampled
     * when {@link #underrunAnswers()} read {@code sampledIn}: an answer
     * sampled before a {@link #forgetUnderrunReporting() forget} is the
     * driver's that was forgotten, and is ignored (ruling E116).
     *
     * @param reports   what the backend's drop counters say
     * @param sampledIn {@link #underrunAnswers()} read before the counters were
     */
    synchronized void reportsOutputStarvation(boolean reports, long sampledIn) {
        if (sampledIn != underrunAnswers) {
            return;
        }
        underruns = reports ? EngineHealthStatus.UnderrunReporting.REPORTED
                : EngineHealthStatus.UnderrunReporting.NOT_REPORTED;
    }

    /** @return how many times the answer was forgotten; read before a sample's drop counters */
    synchronized long underrunAnswers() {
        return underrunAnswers;
    }

    /**
     * Forgets the answer: another driver or device was chosen in Settings,
     * and nothing is said about underruns until a stream on it answers
     * (ruling E116).
     */
    synchronized void forgetUnderrunReporting() {
        underrunAnswers++;
        underruns = EngineHealthStatus.UnderrunReporting.NOT_ASKED;
    }

    /**
     * The entries written since the previous call, oldest first — each entry
     * once, at most the newest {@value #MAX_ENTRIES}; the entry for CPU
     * budget markings lost since then is written first, so it is the newest.
     *
     * @return the new entries
     */
    synchronized List<Entry> drain() {
        writeTheLostCpuBudgetEntries();
        List<Entry> fresh = List.copyOf(undrained);
        undrained.clear();
        return fresh;
    }

    /**
     * The newest entries, oldest first, at most {@value #MAX_ENTRIES}; the
     * entry for CPU budget markings lost so far is written first.
     *
     * @return the entries
     */
    synchronized List<Entry> entries() {
        writeTheLostCpuBudgetEntries();
        return List.copyOf(entries);
    }

    /**
     * The counts now.
     *
     * @return the counts
     */
    synchronized Counts counts() {
        return new Counts(gaps, lateBlocks, since, underruns);
    }

    /**
     * How many of the count arrived at or after {@code sinceNanos}, a
     * {@link System#nanoTime()} reading within the last
     * {@value #RECENT_WINDOW_SECONDS} seconds.
     *
     * @param sinceNanos the earliest arrival counted
     * @return the occurrences
     */
    synchronized long gapsSince(long sinceNanos) {
        long recent = 0L;
        for (GapArrival arrival : recentGaps) {
            if (arrival.nanos() - sinceNanos >= 0L) {
                recent += arrival.count();
            }
        }
        return recent;
    }

    private static void keepNewest(Deque<Entry> kept, Entry entry) {
        kept.addLast(entry);
        if (kept.size() > MAX_ENTRIES) {
            kept.removeFirst();
        }
    }
}
