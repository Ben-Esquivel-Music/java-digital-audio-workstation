package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.performance.PerformanceEventLossListener;
import com.benesquivelmusic.daw.sdk.audio.performance.DegradationPolicy;
import com.benesquivelmusic.daw.sdk.audio.performance.TrackPerformanceEvent;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Writes the xrun journal's Budget entries for the tracks the CPU budget
 * enforcer marks over budget (story 338, rulings E42, E174, E178, E181, E184,
 * E197, E199, E200 and E203; amendment A5). The engine applies no degradation
 * policy yet, so the entry, of kind {@link XrunJournal.Kind#SHED} and shown
 * as "Budget", reports only that the track went over its budget, with the
 * event's rolling average as a whole percent of the block time, whatever
 * policy the budget names. A degradation by {@code DoNothing} writes nothing,
 * nor does a restore. The entry never raises the xrun count. It keeps the
 * track's id; the window's {@link EngineHealthFeed} names the track on the FX
 * thread.
 *
 * <p>A track gets a Budget entry at its first marking, at its first marking
 * after any 30-second stretch with no CPU budget event for it, and at its
 * first marking after each Clear of the log; while its events keep coming
 * less than 30 seconds apart, it gets no further entry. Its CPU budget events
 * are its markings and its restores, timed when this subscriber receives
 * them, on the clock the journal dates its entries by; the 30 seconds are the
 * log's "recent" window. A marking that gets no entry writes nothing, while
 * the ⚠ on the track's mixer strip shows it is still marked. This subscriber
 * keeps a track only while that track's last event is less than 30 seconds
 * old, forgetting the others at each delivery and each read of the log, so a
 * removed track is forgotten too; a restore of a track it holds nothing for
 * begins a run with no entry, so the track's next marking writes one. A new
 * enforcer gets a new subscriber, which starts with no runs.</p>
 *
 * <p>The enforcer offers its events and never waits, so a degradation this
 * subscriber's full buffer cannot hold is dropped for it. Each marking
 * dropped is counted ({@link #eventDropped}); the audio thread cannot tell
 * whether it would have written an entry, so the count is of markings, not
 * of entries, and a marking this subscriber received and did not log is not in
 * it. The count is handed over once this subscriber has delivered every
 * event queued for it ahead of the latest counted drop: at that delivery, at
 * the window's next read of the log when that drop was counted only after it,
 * or at the completion, whichever comes first.
 * Those are the events the enforcer offered before that drop, a count it
 * keeps on the offering thread, less the events dropped for this subscriber
 * before it; the subscriber is subscribed before the enforcer offers
 * anything. The backlog that was queued at that drop is then in the journal
 * ahead of the count, so the backlog cannot push the count out of the newest
 * lines of the log. Each drop is handed over once, by whichever of the three
 * comes first. The journal writes one entry for all it was handed at the
 * window's next read of the log, dated then; a count the read itself hands
 * over ({@link #handOverAtTheRead}) is in that read's entry.</p>
 *
 * <p>Subscribed beside the enforcer's UI binding. It is never cancelled.
 * When the enforcer is replaced, the old one is detached from the engine and
 * closed once the block in flight has ended, or at once when no stream is
 * running, and this subscriber stays subscribed until that close; so every
 * marking the old enforcer makes, its last block's included, gets a Budget
 * entry under this subscriber's runs or is counted in its "never reached the
 * log" entry (ruling E203). Deliveries run on the
 * enforcer publisher's executor, never on the audio thread, which runs only
 * {@link #eventDropped}, taking no lock; the journal is thread-safe. The
 * deliveries, the completion and the window's read take this subscriber's
 * monitor, held only around a journal append, a few field reads and the
 * forgetting of the tracks quiet for 30 seconds, so the FX thread never waits
 * long for it.</p>
 */
final class ShedLogSubscriber implements Flow.Subscriber<TrackPerformanceEvent>, PerformanceEventLossListener {

    private static final Logger LOG = Logger.getLogger(ShedLogSubscriber.class.getName());

    /** The quiet that ends a track's run: the log's "recent" window, and the toast's per-track throttle. */
    private static final long QUIET_NANOS = XrunJournal.RECENT_WINDOW_SECONDS * 1_000_000_000L;

    /**
     * The {@link Run#clearsAtEntry} of a run begun at a restore, which has no
     * entry: no count of the journal's Clears equals it, so the run's next
     * marking writes one (ruling E200).
     */
    private static final long NO_ENTRY = -1L;

    private final XrunJournal journal;
    /** {@code null}, then the live subscription. */
    private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
    /**
     * The degradations dropped for this subscriber. Written only under the
     * enforcer publisher's lock, so by one thread at a time: a volatile read
     * and store suffice, with no atomic read-modify-write on the audio thread.
     */
    private volatile long dropped;
    /**
     * The delivery after which the count is handed over: written only in
     * {@link #eventDropped}, under the publisher's lock, before
     * {@link #dropped}.
     */
    private volatile long reportAfter;
    /**
     * Every event dropped for this subscriber, counted or not; touched only
     * in {@link #eventDropped}, under the publisher's lock.
     */
    private long lost;
    /**
     * The events delivered; guarded by this. A delivery counts itself only
     * after its entry is in the journal, so a read never hands the count over
     * ahead of the backlog's last entry.
     */
    private long delivered;
    /** How many of {@link #dropped} were handed to the journal; guarded by this. */
    private long reported;

    /** When this subscriber receives an event, on the clock the journal dates its entries by. */
    private final LongSupplier nanoClock;
    /**
     * Guarded by this: each track whose last CPU budget event is less than
     * {@link #QUIET_NANOS} old, oldest event first, so the quiet ones are
     * forgotten from the head.
     */
    private final Map<String, Run> runs = new LinkedHashMap<>();

    /**
     * One track's run of CPU budget events: when its last event was received,
     * and the journal's Clears when the run's last entry was written, or
     * {@link #NO_ENTRY} while the run, begun at a restore, has none.
     */
    private record Run(long lastEventNanos, long clearsAtEntry) {
    }

    /**
     * A subscriber on {@link System#nanoTime()}, the clock of the journal the
     * engine controller builds.
     *
     * @param journal the engine controller's xrun journal
     */
    ShedLogSubscriber(XrunJournal journal) {
        this(journal, System::nanoTime);
    }

    /**
     * A subscriber on the given clock.
     *
     * @param journal   the xrun journal
     * @param nanoClock the journal's monotonic clock
     */
    ShedLogSubscriber(XrunJournal journal, LongSupplier nanoClock) {
        this.journal = Objects.requireNonNull(journal, "journal must not be null");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock must not be null");
    }

    /** How many tracks this subscriber holds a run for: those with a CPU budget event in the last 30 seconds. */
    synchronized int tracksHeld() {
        return runs.size();
    }

    @Override
    public void onSubscribe(Flow.Subscription arrived) {
        Objects.requireNonNull(arrived, "subscription must not be null");
        if (subscription.compareAndSet(null, arrived)) {
            arrived.request(Long.MAX_VALUE);
        } else {
            arrived.cancel();
        }
    }

    @Override
    public synchronized void onNext(TrackPerformanceEvent event) {
        long now = nanoClock.getAsLong();
        forgetQuietTracks(now);
        if (namesAPolicy(event)) {
            TrackPerformanceEvent.TrackDegraded degraded = (TrackPerformanceEvent.TrackDegraded) event;
            if (opensARun(degraded.trackId(), now)) {
                long percent = Math.round(degraded.measuredFraction() * 100.0);
                journal.append(XrunJournal.Detail.OVER_CPU_BUDGET, List.of(degraded.trackId(), percent), 1L);
            }
        } else if (event instanceof TrackPerformanceEvent.TrackRestored restored) {
            Run run = runs.remove(restored.trackId());
            runs.put(restored.trackId(), new Run(now, run == null ? NO_ENTRY : run.clearsAtEntry()));
        }
        delivered = delivered + 1L;
        handOverWhenDue();
    }

    /**
     * The window's read of the log, on the FX thread, before it drains the
     * journal: hands over a count that is due, so the read writes it in its
     * own entry, dated then (ruling E197), and forgets the tracks quiet for
     * 30 seconds.
     */
    synchronized void handOverAtTheRead() {
        forgetQuietTracks(nanoClock.getAsLong());
        handOverWhenDue();
    }

    /**
     * Whether a marking of {@code trackId} received at {@code now} writes an
     * entry: the track had no CPU budget event in the last 30 seconds (it
     * holds no run once {@link #forgetQuietTracks} ran), its run began at a
     * restore and has no entry yet ({@link #NO_ENTRY}), or the log was
     * cleared since the run's last entry. Whether or not it writes one, the
     * marking becomes the run's latest event.
     */
    private boolean opensARun(String trackId, long now) {
        // Read before the append: a Clear in between costs one extra entry, never a missing one.
        long clears = journal.clears();
        Run run = runs.remove(trackId);
        boolean opens = run == null || run.clearsAtEntry() != clears;
        runs.put(trackId, new Run(now, opens ? clears : run.clearsAtEntry()));
        return opens;
    }

    /** Forgets every track whose last CPU budget event is {@link #QUIET_NANOS} old or older at {@code now}. */
    private void forgetQuietTracks(long now) {
        Iterator<Run> oldestFirst = runs.values().iterator();
        while (oldestFirst.hasNext() && now - oldestFirst.next().lastEventNanos() >= QUIET_NANOS) {
            oldestFirst.remove();
        }
    }

    /**
     * Counts a degradation the enforcer's publisher dropped for this
     * subscriber; a dropped restore or {@code DoNothing} counts nothing, but
     * every drop counts in {@link #lost}. Called on the audio thread under
     * the publisher's lock. It tests the event's type and its policy's type;
     * for a counted drop it reads {@link #lost}, stores {@link #reportAfter}
     * as the events queued for this subscriber ahead of this one, then reads
     * and stores {@link #dropped}; for every drop it reads and stores
     * {@link #lost}. Field reads and stores only, with no lock, allocation or
     * call out.
     */
    @Override
    public void eventDropped(TrackPerformanceEvent event, long offeredBefore) {
        if (namesAPolicy(event)) {
            reportAfter = offeredBefore - lost;
            dropped = dropped + 1L;
        }
        lost = lost + 1L;
    }

    @Override
    public void onError(Throwable throwable) {
        LOG.log(Level.WARNING, "The CPU budget enforcer's events ended with an error; no more CPU budget entries",
                throwable);
    }

    /** The enforcer was closed: what it had dropped is handed over; its replacement gets a subscriber of its own. */
    @Override
    public synchronized void onComplete() {
        long total = dropped;
        if (total > reported) {
            handOver(total);
        }
    }

    private static boolean namesAPolicy(TrackPerformanceEvent event) {
        return event instanceof TrackPerformanceEvent.TrackDegraded degraded
                && !(degraded.appliedPolicy() instanceof DegradationPolicy.DoNothing);
    }

    private void handOverWhenDue() {
        // Read in the reverse of eventDropped's write order: a total that holds a drop sees that drop's reportAfter.
        long total = dropped;
        if (total > reported && delivered >= reportAfter) {
            handOver(total);
        }
    }

    private void handOver(long total) {
        journal.noteCpuBudgetEntriesLost(total - reported);
        reported = total;
    }
}
