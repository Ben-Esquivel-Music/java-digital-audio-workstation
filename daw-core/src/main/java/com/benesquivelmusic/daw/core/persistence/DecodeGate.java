package com.benesquivelmusic.daw.core.persistence;

import java.util.Optional;
import java.util.concurrent.CancellationException;

/**
 * Admits a rehydration session's decodes in their scheduled order under a
 * concurrency limit and a memory budget (Persistence Integrity book §4.3;
 * decision D10).
 * <ul>
 *   <li>Every decode holds a numbered ticket, issued on the owner thread in
 *       the session's decode order. Tickets are admitted strictly in order:
 *       ticket {@code n} waits until every earlier ticket was admitted or
 *       {@linkplain #skip skipped}, so the playhead's sources go first
 *       whatever order the decode threads happen to start in.</li>
 *   <li>At most {@code permits} decodes run at once, and the estimated
 *       bytes of the running decodes stay within {@code budgetBytes}.</li>
 *   <li>A decode estimated above the budget waits until nothing runs, then
 *       runs alone: it takes the whole budget, and nothing is admitted until
 *       it releases.</li>
 * </ul>
 * {@link #cancel()} wakes every waiter, which then gives up with a
 * {@link CancellationException}; nothing is admitted afterwards.
 *
 * <p>Thread-safe; waiting decode threads are virtual and block in
 * {@link Object#wait()}, which does not pin their carrier (JEP 491).</p>
 */
final class DecodeGate {

    private final int permits;
    private final long budgetBytes;
    private final boolean[] settled;

    /** The lowest ticket not yet admitted or skipped. */
    private int next;
    private int running;
    private long bytesInUse;
    private boolean alone;
    private boolean cancelled;

    /**
     * Creates a gate.
     *
     * @param tickets     how many tickets the session issues, numbered from 0
     * @param permits     the most decodes that may run at once; at least 1
     * @param budgetBytes the memory budget; not negative
     */
    DecodeGate(int tickets, int permits, long budgetBytes) {
        if (tickets < 0) {
            throw new IllegalArgumentException("tickets must not be negative: " + tickets);
        }
        if (permits < 1) {
            throw new IllegalArgumentException("permits must be at least 1: " + permits);
        }
        if (budgetBytes < 0) {
            throw new IllegalArgumentException("budgetBytes must not be negative: " + budgetBytes);
        }
        this.settled = new boolean[tickets];
        this.permits = permits;
        this.budgetBytes = budgetBytes;
    }

    /**
     * What an admitted decode holds until it {@linkplain #release releases}.
     *
     * @param bytes the budget bytes charged
     * @param alone whether the decode runs alone (its estimate exceeded the budget)
     */
    record Lease(long bytes, boolean alone) {
    }

    /**
     * Waits until ticket {@code ticket} may run a decode estimated at
     * {@code costBytes}, then admits it.
     *
     * @param ticket    the decode's ticket
     * @param costBytes the decode's estimated cost
     * @return the lease to release when the decode is done
     * @throws InterruptedException  if the waiting thread is interrupted
     * @throws CancellationException if the gate is (or becomes) cancelled
     */
    synchronized Lease admit(int ticket, long costBytes) throws InterruptedException {
        Optional<Lease> lease = tryAdmit(ticket, costBytes);
        while (lease.isEmpty()) {
            wait();
            lease = tryAdmit(ticket, costBytes);
        }
        return lease.get();
    }

    /**
     * Admits ticket {@code ticket} now if it may run, without waiting.
     *
     * @param ticket    the decode's ticket
     * @param costBytes the decode's estimated cost
     * @return the lease, or empty when the ticket must wait
     * @throws CancellationException if the gate is cancelled
     */
    synchronized Optional<Lease> tryAdmit(int ticket, long costBytes) {
        if (cancelled) {
            throw new CancellationException("the rehydration session was cancelled");
        }
        checkTicket(ticket);
        boolean oversize = costBytes > budgetBytes;
        long charge = oversize ? budgetBytes : Math.max(0L, costBytes);
        boolean fits = oversize ? running == 0 : bytesInUse + charge <= budgetBytes;
        if (ticket != next || alone || running >= permits || !fits) {
            return Optional.empty();
        }
        running++;
        bytesInUse += charge;
        alone = oversize;
        settle(ticket);
        return Optional.of(new Lease(charge, oversize));
    }

    /**
     * Gives up ticket {@code ticket} without running a decode — its source
     * failed before it needed one, or the session was cancelled — so later
     * tickets are not held up by it. Does nothing for a ticket already
     * admitted or skipped.
     *
     * @param ticket the ticket
     */
    synchronized void skip(int ticket) {
        checkTicket(ticket);
        if (!settled[ticket]) {
            settle(ticket);
        }
    }

    /**
     * Returns what {@code lease} holds to the gate.
     *
     * @param lease the lease {@link #admit} or {@link #tryAdmit} returned
     */
    synchronized void release(Lease lease) {
        running--;
        bytesInUse -= lease.bytes();
        if (lease.alone()) {
            alone = false;
        }
        notifyAll();
    }

    /** Cancels the gate: every waiter gives up, nothing is admitted again. */
    synchronized void cancel() {
        cancelled = true;
        notifyAll();
    }

    private void settle(int ticket) {
        settled[ticket] = true;
        while (next < settled.length && settled[next]) {
            next++;
        }
        notifyAll();
    }

    private void checkTicket(int ticket) {
        if (ticket < 0 || ticket >= settled.length) {
            throw new IllegalArgumentException("no such ticket: " + ticket);
        }
    }
}
