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
 * of ruling E20) and when the feed wrote it, both on the wall clock and as a
 * {@link System#nanoTime()} reading, so a resync's arrival can be mapped to a
 * take frame (ruling E40).</p>
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
 * <p>Thread-safe: every method holds this journal's monitor briefly and none
 * waits, calls out or throws for a closed engine.</p>
 */
final class XrunJournal {

    /** The most entries the journal keeps, and the most one {@link #drain()} returns (ruling E22). */
    static final int MAX_ENTRIES = 100;

    /** How far back the gap arrivals are kept for {@link #gapsSince(long)} (ruling E22's 30 seconds). */
    static final int RECENT_WINDOW_SECONDS = 30;

    private static final long RECENT_WINDOW_NANOS = RECENT_WINDOW_SECONDS * 1_000_000_000L;

    /** The kinds of ruling E20, with the resync of ruling E38. */
    enum Kind {
        GAP(true),
        DISCARDED(true),
        OVERLOAD(true),
        RESYNC(true),
        LATE(false),
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
         * Shed: a track's expensive processing was bypassed. Arguments: the track id ({@link String}) and the
         * rolling average as a whole percent of the block time ({@link Long}).
         */
        EXPENSIVE_PROCESSING_BYPASSED(Kind.SHED, 2),
        /**
         * Shed: a track's oversampling was reduced. Arguments: the track id ({@link String}), the new factor
         * ({@link Integer}) and the rolling average as a whole percent of the block time ({@link Long}).
         */
        OVERSAMPLING_REDUCED(Kind.SHED, 3),
        /**
         * Shed: simpler processing was substituted on a track. Arguments: the track id ({@link String}) and the
         * rolling average as a whole percent of the block time ({@link Long}).
         */
        SIMPLER_PROCESSING_SUBSTITUTED(Kind.SHED, 2),
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
     * @param arrivedAt    when the feed wrote it, on the wall clock
     * @param arrivedNanos when the feed wrote it, as a {@link System#nanoTime()} reading
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
    private long gaps;
    private long lateBlocks;
    private Instant since;
    private EngineHealthStatus.UnderrunReporting underruns = EngineHealthStatus.UnderrunReporting.NOT_ASKED;
    /** Advanced by every {@link #forgetUnderrunReporting()}. */
    private long underrunAnswers;

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
        return append(new Entry(detail, arguments, count, wallClock.instant(), nanos), nanos);
    }

    /**
     * Writes one entry standing for {@code count} occurrences that arrived at
     * {@code arrivedNanos}, before now: its time, on both clocks, is that
     * arrival (a driver's resync message, rulings E40 and E62). The 30-second
     * figure counts it from now.
     *
     * @param detail       what it says
     * @param arguments    the detail's arguments
     * @param count        the occurrences; at least 1
     * @param arrivedNanos when they arrived, as a {@link System#nanoTime()} reading
     * @return the entry written
     */
    synchronized Entry append(Detail detail, List<Object> arguments, long count, long arrivedNanos) {
        long nanos = nanoClock.getAsLong();
        Instant arrivedAt = wallClock.instant().minusNanos(Math.max(0L, nanos - arrivedNanos));
        return append(new Entry(detail, arguments, count, arrivedAt, arrivedNanos), nanos);
    }

    private Entry append(Entry entry, long nanos) {
        Detail detail = entry.detail();
        long count = entry.count();
        keepNewest(entries, entry);
        keepNewest(undrained, entry);
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

    /** The log's Clear (ruling E22): restarts the count and forgets every entry, drained or not. */
    synchronized void clear() {
        restartCount();
        entries.clear();
        undrained.clear();
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
     * once, at most the newest {@value #MAX_ENTRIES}.
     *
     * @return the new entries
     */
    synchronized List<Entry> drain() {
        List<Entry> fresh = List.copyOf(undrained);
        undrained.clear();
        return fresh;
    }

    /**
     * The newest entries, oldest first, at most {@value #MAX_ENTRIES}.
     *
     * @return the entries
     */
    synchronized List<Entry> entries() {
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
