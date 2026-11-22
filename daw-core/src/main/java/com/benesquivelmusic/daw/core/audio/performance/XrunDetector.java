package com.benesquivelmusic.daw.core.audio.performance;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.XrunEvent;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts render blocks that missed their deadline and turns the counts into
 * {@link XrunEvent}s off the real-time thread.
 *
 * <p>The deadline is {@code bufferSize / sampleRate}. The render drive hands
 * every rendered block's processing time to {@link #recordTick(long)}, which
 * classifies it:</p>
 * <ul>
 *   <li>on time: counted as a tick only;</li>
 *   <li>late, below the dropout threshold (default 2&times; the deadline):
 *       a late tick, published as {@link XrunEvent.BufferLate};</li>
 *   <li>at or beyond the dropout threshold: a dropped tick, published as
 *       {@link XrunEvent.BufferDropped}.</li>
 * </ul>
 * <p>A non-real-time caller adds gaps it observed elsewhere through
 * {@link #reportDropped(long)}, and CPU-budget overloads through
 * {@link #reportGraphOverload(String, double)}; both offer their event at
 * once.</p>
 *
 * <h2>Threads</h2>
 * <p>{@link #recordTick(long)} is the one real-time method. It stores into
 * plain {@code volatile} fields whose only writer is the render drive (the
 * current pump: a new pump starts only after the previous one's stop was
 * confirmed), and it never allocates, logs, publishes, locks or throws, so
 * the render thread never blocks here. Events for the counted ticks are
 * offered by {@link #publishPending()}, which a non-real-time thread calls
 * periodically. Every getter, {@code publishPending}, the two report methods
 * and {@link #close()} are for non-real-time threads, and none of them
 * throws because the publisher was closed.</p>
 *
 * <h2>Publisher ownership</h2>
 * <p>The constructors without a publisher argument create and own one, and
 * {@link #close()} completes it. The constructors that take a publisher
 * borrow it, so a detector can be replaced (after a reconfigure) while the
 * subscribers stay on one stream; {@code close()} then leaves it open.</p>
 */
public final class XrunDetector implements AutoCloseable {

    /** Default multiplier of the deadline after which a late buffer is considered dropped. */
    public static final double DEFAULT_DROP_THRESHOLD = 2.0;

    /**
     * The most tick events one {@link #publishPending()} call offers. The
     * counts beyond it stay exact in the getters; only their events are not
     * offered, so a burst at a small buffer size cannot flood subscribers.
     */
    public static final int MAX_EVENTS_PER_PUBLISH = 16;

    private final long deadlineNanos;
    private final long dropThresholdNanos;
    private final SubmissionPublisher<XrunEvent> publisher;
    private final boolean ownsPublisher;

    // Written only by the render drive (recordTick); read by any thread.
    private volatile long ticks;
    private volatile long lateTicks;
    private volatile long droppedTicks;
    private volatile long lastOvershootNanos;

    // Written by non-real-time callers.
    private final AtomicLong reportedDropped = new AtomicLong();
    private final AtomicLong overloadCount = new AtomicLong();

    // Guarded by this: what publishPending has already accounted for.
    private long publishedLateTicks;
    private long publishedDroppedTicks;

    /**
     * Creates a detector that owns its publisher, with the default dropout
     * threshold.
     *
     * @param sampleRate frames per second (must be positive)
     * @param bufferSize frames per callback (must be positive)
     */
    public XrunDetector(double sampleRate, int bufferSize) {
        this(sampleRate, bufferSize, DEFAULT_DROP_THRESHOLD);
    }

    /**
     * Creates a detector that owns its publisher.
     *
     * @param sampleRate              frames per second (must be positive)
     * @param bufferSize              frames per callback (must be positive)
     * @param dropThresholdMultiplier factor of the deadline at or beyond which
     *                                a late buffer is classified as dropped
     *                                (must be {@code > 1.0})
     */
    public XrunDetector(double sampleRate, int bufferSize, double dropThresholdMultiplier) {
        this(sampleRate, bufferSize, dropThresholdMultiplier, new SubmissionPublisher<>(), true);
    }

    /**
     * Creates a detector that offers to a publisher it borrows, with the
     * default dropout threshold; {@link #close()} leaves the publisher open.
     *
     * @param sampleRate frames per second (must be positive)
     * @param bufferSize frames per callback (must be positive)
     * @param publisher  the publisher to offer events to; must not be null
     */
    public XrunDetector(double sampleRate, int bufferSize, SubmissionPublisher<XrunEvent> publisher) {
        this(sampleRate, bufferSize, DEFAULT_DROP_THRESHOLD, publisher);
    }

    /**
     * Creates a detector that offers to a publisher it borrows;
     * {@link #close()} leaves the publisher open.
     *
     * @param sampleRate              frames per second (must be positive)
     * @param bufferSize              frames per callback (must be positive)
     * @param dropThresholdMultiplier factor of the deadline at or beyond which
     *                                a late buffer is classified as dropped
     *                                (must be {@code > 1.0})
     * @param publisher               the publisher to offer events to; must
     *                                not be null
     */
    public XrunDetector(double sampleRate, int bufferSize, double dropThresholdMultiplier,
                        SubmissionPublisher<XrunEvent> publisher) {
        this(sampleRate, bufferSize, dropThresholdMultiplier,
                Objects.requireNonNull(publisher, "publisher must not be null"), false);
    }

    private XrunDetector(double sampleRate, int bufferSize, double dropThresholdMultiplier,
                         SubmissionPublisher<XrunEvent> publisher, boolean ownsPublisher) {
        if (sampleRate <= 0.0 || Double.isNaN(sampleRate) || Double.isInfinite(sampleRate)) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize must be positive: " + bufferSize);
        }
        if (!(dropThresholdMultiplier > 1.0) || Double.isInfinite(dropThresholdMultiplier)) {
            throw new IllegalArgumentException(
                    "dropThresholdMultiplier must be > 1.0 and finite: " + dropThresholdMultiplier);
        }
        this.deadlineNanos = (long) ((bufferSize / sampleRate) * 1_000_000_000.0);
        this.dropThresholdNanos = (long) (deadlineNanos * dropThresholdMultiplier);
        this.publisher = publisher;
        this.ownsPublisher = ownsPublisher;
    }

    /** Returns the deadline derived from {@code bufferSize / sampleRate} in nanoseconds. */
    public long getDeadlineNanos() {
        return deadlineNanos;
    }

    /**
     * Returns the publisher the events are offered to: the owned one, or the
     * borrowed one this detector was given.
     */
    public Flow.Publisher<XrunEvent> xrunEvents() {
        return publisher;
    }

    /**
     * Records one rendered block's processing time. Real-time: plain stores
     * only, and the overshoot is stored before its count, so a reader that
     * reads a count and then the overshoot sees that tick's overshoot or a
     * newer one. Its only caller is the render drive.
     *
     * @param elapsedNanos processing time of the block in nanoseconds
     */
    @RealTimeSafe
    public void recordTick(long elapsedNanos) {
        ticks = ticks + 1L;
        if (elapsedNanos <= deadlineNanos) {
            return;
        }
        lastOvershootNanos = elapsedNanos - deadlineNanos;
        if (elapsedNanos >= dropThresholdNanos) {
            droppedTicks = droppedTicks + 1L;
        } else {
            lateTicks = lateTicks + 1L;
        }
    }

    /**
     * Offers the events for the ticks counted since the previous call: one
     * {@link XrunEvent.BufferDropped} per dropped tick, then one
     * {@link XrunEvent.BufferLate} per late tick, at most
     * {@link #MAX_EVENTS_PER_PUBLISH} in all. The ticks beyond the cap are
     * accounted for without an event. Each event's frame index is the
     * ordinal of the newest tick counted when the call read the counts, and
     * a late event's deadline miss is the newest overshoot; the counters
     * cannot say which block each one was.
     *
     * @return the number of events offered by this call
     */
    public synchronized int publishPending() {
        long dropped = droppedTicks;
        long late = lateTicks;
        long frameIndex = Math.max(0L, ticks - 1L);
        Duration deadlineMiss = Duration.ofNanos(Math.max(0L, lastOvershootNanos));
        long newDropped = dropped - publishedDroppedTicks;
        long newLate = late - publishedLateTicks;
        publishedDroppedTicks = dropped;
        publishedLateTicks = late;
        int droppedEvents = (int) Math.min(newDropped, MAX_EVENTS_PER_PUBLISH);
        int lateEvents = (int) Math.min(newLate, MAX_EVENTS_PER_PUBLISH - droppedEvents);
        int offered = 0;
        for (int i = 0; i < droppedEvents; i++) {
            offered += offer(new XrunEvent.BufferDropped(frameIndex));
        }
        for (int i = 0; i < lateEvents; i++) {
            offered += offer(new XrunEvent.BufferLate(frameIndex, deadlineMiss));
        }
        return offered;
    }

    /**
     * Reports blocks that were dropped outside the render drive's timing (for
     * example observed by the backend), and offers one
     * {@link XrunEvent.BufferDropped} for the whole report. Non-real-time.
     *
     * @param count the number of dropped blocks; at least 1
     * @throws IllegalArgumentException if {@code count} is below 1
     */
    public void reportDropped(long count) {
        if (count < 1L) {
            throw new IllegalArgumentException("count must be at least 1: " + count);
        }
        reportedDropped.addAndGet(count);
        offer(new XrunEvent.BufferDropped(Math.max(0L, ticks - 1L)));
    }

    /**
     * Reports that the audio graph exceeded its CPU budget and offers a
     * {@link XrunEvent.GraphOverload}. Non-real-time.
     *
     * @param offendingNodeId identifier of the node that dominated the
     *                        over-budget buffer; must not be null
     * @param cpuFraction     measured CPU load as a fraction in
     *                        {@code [0.0, +∞)}
     */
    public void reportGraphOverload(String offendingNodeId, double cpuFraction) {
        XrunEvent.GraphOverload event = new XrunEvent.GraphOverload(offendingNodeId, cpuFraction);
        overloadCount.incrementAndGet();
        offer(event);
    }

    /** Returns the number of rendered blocks recorded by {@link #recordTick(long)}. */
    public long getTickCount() {
        return ticks;
    }

    /** Returns the number of late ticks (past the deadline, below the dropout threshold). */
    public long getLateCount() {
        return lateTicks;
    }

    /** Returns how far the newest late or dropped tick ran past the deadline, in nanoseconds; 0 before any. */
    public long getLastOvershootNanos() {
        return lastOvershootNanos;
    }

    /** Returns the dropped ticks plus the blocks reported through {@link #reportDropped(long)}. */
    public long getDroppedCount() {
        return droppedTicks + reportedDropped.get();
    }

    /** Returns the number of overloads reported through {@link #reportGraphOverload(String, double)}. */
    public long getOverloadCount() {
        return overloadCount.get();
    }

    /** Completes the publisher if this detector owns it; a borrowed publisher stays open. */
    @Override
    public void close() {
        if (ownsPublisher) {
            publisher.close();
        }
    }

    private int offer(XrunEvent event) {
        try {
            return publisher.offer(event, null) >= 0 ? 1 : 0;
        } catch (IllegalStateException | RejectedExecutionException closedOrRefused) {
            // A closed publisher means nobody listens any more; the counts stay exact.
            return 0;
        }
    }
}
