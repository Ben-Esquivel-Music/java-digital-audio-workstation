package com.benesquivelmusic.daw.sdk.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * The driver resync requests an {@link AsioBackend} counted and the arrival of
 * each (story 338, rulings E270 and E279): written on the driver's message
 * thread, which may be its real-time thread, with no lock, no allocation and
 * no blocking; taken up by the engine's xrun feed at each engine-health tick
 * ({@link #take()}); and read by the clock-lock poll for the first request of
 * each poll interval ({@link #arrivalOf(long)}).
 *
 * <p><strong>What is kept.</strong> Requests are numbered from 0 in the order
 * they are claimed. Of the requests since the last take, the first
 * {@value #KEPT} are kept, but for one whose record is under way as a take
 * that covered {@value #KEPT} or more ends, which compares against the bound
 * that take replaces (one per driver thread); a take dates a request not kept
 * at the last arrival kept before it. A slot is reused once a take has passed
 * its request, so the poll finds an arrival only until a request
 * {@value #KEPT} later is kept.</p>
 *
 * <p><strong>Order.</strong> A request's arrival is stored before the request
 * is counted, so with one driver thread sending, a reader that sees a count
 * finds every kept arrival behind it. When two driver threads send at the same
 * instant, a take can count a request whose arrival is still being stored, and
 * dates it at the last arrival kept before it. No date is later than the
 * record that counts its request: an arrival is read before its claim, and the
 * last arrival kept before a request was read before that request's
 * claim.</p>
 *
 * <p>Writers: any number of driver threads. Takes: any non-real-time thread,
 * one at a time; a take never waits for a driver thread. Arrival reads: any
 * thread, lock-free.</p>
 */
final class ResyncArrivalRing {

    /**
     * The arrivals kept between two takes: more than one engine-health
     * tick holds at one request per buffer period down to 32-frame buffers at
     * 48 kHz (150 in 100 ms), with room for a tick held up to about 170 ms. A
     * power of two, so a request's slot is its number masked.
     */
    static final int KEPT = 256;

    private static final int MASK = KEPT - 1;

    /** The next request's number. */
    private final AtomicLong claimed = new AtomicLong();
    /** The requests counted, each after its arrival is stored or found beyond the bound. */
    private final AtomicLong counted = new AtomicLong();
    private final AtomicLongArray arrivals = new AtomicLongArray(KEPT);
    /** Per slot, the number of the request whose arrival it holds, plus one; 0 while being written or empty. */
    private final AtomicLongArray holders = new AtomicLongArray(KEPT);
    /** The first request the next take covers. */
    private volatile long takenUpTo;
    /** Guarded by this: the arrival the last take ended on. */
    private long lastTakenNanos;

    /**
     * @param createdNanos a {@link System#nanoTime()} reading taken before any
     *                     request can arrive; it dates a request not kept when
     *                     no arrival was kept before it
     */
    ResyncArrivalRing(long createdNanos) {
        this.lastTakenNanos = createdNanos;
    }

    /**
     * Counts one request and keeps its arrival when it is among the first
     * {@value #KEPT} since the last take. Driver thread: an atomic claim, at
     * most three array stores, and the count; no lock, no allocation.
     *
     * @param arrivalNanos when the request arrived, as a {@link System#nanoTime()} reading
     */
    void record(long arrivalNanos) {
        long request = claimed.getAndIncrement();
        if (request - takenUpTo < KEPT) {
            int slot = (int) (request & MASK);
            holders.set(slot, 0L);
            arrivals.set(slot, arrivalNanos);
            holders.set(slot, request + 1L);
        }
        counted.incrementAndGet();
    }

    /** @return the requests counted, monotonic for the ring's life */
    long requests() {
        return counted.get();
    }

    /**
     * The arrival of request number {@code request} while it is kept.
     * Lock-free; any thread.
     *
     * @param request a request {@link #requests()} has counted
     * @return its arrival; empty when it was not kept or its slot was reused
     */
    OptionalLong arrivalOf(long request) {
        int slot = (int) (request & MASK);
        long holder = request + 1L;
        if (holders.get(slot) != holder) {
            return OptionalLong.empty();
        }
        long arrival = arrivals.get(slot);
        return holders.get(slot) == holder ? OptionalLong.of(arrival) : OptionalLong.empty();
    }

    /**
     * Takes up the requests counted since the last take, in request order:
     * each kept request a run of its own at its arrival, each other request in
     * the run of the last arrival kept before it. Frees the next {@value #KEPT}
     * slots for the requests after them.
     *
     * @return the requests taken up; no runs when none was counted since the last take
     */
    synchronized DriverResyncArrivals take() {
        long from = takenUpTo;
        long to = counted.get();
        if (to == from) {
            return new DriverResyncArrivals(from, List.of());
        }
        long keptTo = to - from > KEPT ? from + KEPT : to;
        List<DriverResyncArrivals.Run> runs = new ArrayList<>();
        long runNanos = lastTakenNanos;
        long runRequests = 0L;
        for (long request = from; request < keptTo; request++) {
            OptionalLong arrival = arrivalOf(request);
            if (arrival.isEmpty()) {
                runRequests++;
                continue;
            }
            if (runRequests > 0L) {
                runs.add(new DriverResyncArrivals.Run(runNanos, runRequests));
            }
            runNanos = arrival.getAsLong();
            runRequests = 1L;
        }
        runs.add(new DriverResyncArrivals.Run(runNanos, runRequests + to - keptTo));
        lastTakenNanos = runNanos;
        takenUpTo = to;
        return new DriverResyncArrivals(from, runs);
    }
}
