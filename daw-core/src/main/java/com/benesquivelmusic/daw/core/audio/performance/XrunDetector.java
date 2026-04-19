package com.benesquivelmusic.daw.core.audio.performance;

import com.benesquivelmusic.daw.sdk.audio.XrunEvent;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * Wraps the real-time render callback and emits {@link XrunEvent}s
 * whenever the audio graph misses its deadline or reports an abnormally
 * high CPU load.
 *
 * <p>The detector computes the deadline from
 * {@code bufferSize / sampleRate} and classifies every tick into one
 * of three outcomes:</p>
 * <ul>
 *   <li>On time — nothing published.</li>
 *   <li>Late but below the hard dropout threshold —
 *       {@link XrunEvent.BufferLate}.</li>
 *   <li>Beyond the dropout threshold (default: 2× budget) or explicitly
 *       reported via {@link #reportDropped(long)} —
 *       {@link XrunEvent.BufferDropped}.</li>
 *   <li>{@link #reportGraphOverload(String, double)} — emits a
 *       {@link XrunEvent.GraphOverload} describing the offending node.</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * <p>{@link #recordTick(long)} is designed to be called from the audio
 * thread. Subscribers receive events through a
 * {@link SubmissionPublisher}, which delivers on a separate executor so
 * the audio thread never blocks.</p>
 *
 * <p>The clock is injectable via {@link LongSupplier} so tests can
 * simulate late buffers deterministically without sleeping.</p>
 */
public final class XrunDetector implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(XrunDetector.class.getName());

    /** Default multiplier of the deadline after which a late buffer is considered dropped. */
    public static final double DEFAULT_DROP_THRESHOLD = 2.0;

    private final long deadlineNanos;
    private final LongSupplier clockNanos;
    private final double dropThresholdMultiplier;
    private final SubmissionPublisher<XrunEvent> publisher;

    private final AtomicLong frameCounter = new AtomicLong(0L);
    private final AtomicLong lateCount = new AtomicLong(0L);
    private final AtomicLong droppedCount = new AtomicLong(0L);
    private final AtomicLong overloadCount = new AtomicLong(0L);

    /**
     * Creates a detector for the given sample rate and buffer size,
     * using {@link System#nanoTime()} as the clock.
     *
     * @param sampleRate frames per second (must be positive)
     * @param bufferSize frames per callback (must be positive)
     */
    public XrunDetector(double sampleRate, int bufferSize) {
        this(sampleRate, bufferSize, DEFAULT_DROP_THRESHOLD, System::nanoTime);
    }

    /**
     * Creates a detector with an injectable clock — used by tests to
     * simulate late callbacks deterministically.
     *
     * @param sampleRate              frames per second (must be positive)
     * @param bufferSize              frames per callback (must be positive)
     * @param dropThresholdMultiplier factor of the deadline beyond which
     *                                a late buffer is classified as dropped
     *                                (must be {@code > 1.0})
     * @param clockNanos              monotonic nanosecond clock
     */
    public XrunDetector(double sampleRate,
                        int bufferSize,
                        double dropThresholdMultiplier,
                        LongSupplier clockNanos) {
        if (sampleRate <= 0.0 || Double.isNaN(sampleRate) || Double.isInfinite(sampleRate)) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize must be positive: " + bufferSize);
        }
        if (dropThresholdMultiplier <= 1.0) {
            throw new IllegalArgumentException(
                    "dropThresholdMultiplier must be > 1.0: " + dropThresholdMultiplier);
        }
        this.deadlineNanos = (long) ((bufferSize / sampleRate) * 1_000_000_000.0);
        this.dropThresholdMultiplier = dropThresholdMultiplier;
        this.clockNanos = Objects.requireNonNull(clockNanos, "clockNanos must not be null");
        this.publisher = new SubmissionPublisher<>();
    }

    /** Returns the deadline derived from {@code bufferSize / sampleRate} in nanoseconds. */
    public long getDeadlineNanos() {
        return deadlineNanos;
    }

    /**
     * Returns the publisher that emits {@link XrunEvent}s. UI code
     * subscribes here (e.g., an {@code XrunCounterLabel}) so it never
     * polls the audio thread.
     */
    public Flow.Publisher<XrunEvent> xrunEvents() {
        return publisher;
    }

    /**
     * Wraps one render callback invocation. Call at the start of the
     * callback; the returned {@link Tick} must be closed at the end (use
     * try-with-resources). Closing classifies the elapsed time as on
     * time, late, or dropped, and publishes the corresponding event.
     */
    public Tick beginTick() {
        long frame = frameCounter.getAndIncrement();
        long start = clockNanos.getAsLong();
        return new Tick(frame, start);
    }

    /**
     * Records a complete render tick using an elapsed duration measured
     * externally. Useful when the engine already tracks its own timing.
     *
     * @param elapsedNanos processing time in nanoseconds
     * @return the frame index assigned to this tick
     */
    public long recordTick(long elapsedNanos) {
        long frame = frameCounter.getAndIncrement();
        classify(frame, elapsedNanos);
        return frame;
    }

    /**
     * Explicitly reports that a buffer was dropped by the backend (e.g.,
     * the callback was skipped entirely).
     *
     * @param frameIndex the frame index of the dropped buffer, or
     *                   {@code -1} to auto-assign the next frame
     */
    public void reportDropped(long frameIndex) {
        long frame = frameIndex >= 0 ? frameIndex : frameCounter.getAndIncrement();
        droppedCount.incrementAndGet();
        publisher.offer(new XrunEvent.BufferDropped(frame), null);
    }

    /**
     * Explicitly reports that the audio graph exceeded its CPU budget.
     *
     * @param offendingNodeId identifier of the node that dominated the
     *                        over-budget buffer
     * @param cpuFraction     measured CPU load as a fraction in
     *                        {@code [0.0, +∞)}
     */
    public void reportGraphOverload(String offendingNodeId, double cpuFraction) {
        overloadCount.incrementAndGet();
        publisher.offer(new XrunEvent.GraphOverload(offendingNodeId, cpuFraction), null);
    }

    /** Returns the number of {@link XrunEvent.BufferLate} events emitted. */
    public long getLateCount() {
        return lateCount.get();
    }

    /** Returns the number of {@link XrunEvent.BufferDropped} events emitted. */
    public long getDroppedCount() {
        return droppedCount.get();
    }

    /** Returns the number of {@link XrunEvent.GraphOverload} events emitted. */
    public long getOverloadCount() {
        return overloadCount.get();
    }

    /**
     * Resets all counters and the frame index. Called on transport
     * start/stop and on buffer-size change.
     */
    public void reset() {
        frameCounter.set(0L);
        lateCount.set(0L);
        droppedCount.set(0L);
        overloadCount.set(0L);
    }

    @Override
    public void close() {
        publisher.close();
    }

    private void classify(long frame, long elapsedNanos) {
        if (elapsedNanos <= deadlineNanos) {
            return;
        }
        long overshoot = elapsedNanos - deadlineNanos;
        if (elapsedNanos >= (long) (deadlineNanos * dropThresholdMultiplier)) {
            droppedCount.incrementAndGet();
            publisher.offer(new XrunEvent.BufferDropped(frame), null);
            LOG.fine(() -> "Buffer dropped at frame " + frame
                    + " (elapsed " + elapsedNanos + " ns, deadline " + deadlineNanos + " ns)");
        } else {
            lateCount.incrementAndGet();
            publisher.offer(new XrunEvent.BufferLate(frame, Duration.ofNanos(overshoot)), null);
            LOG.fine(() -> "Buffer late at frame " + frame
                    + " (overshoot " + overshoot + " ns)");
        }
    }

    /**
     * Handle representing a single in-flight render tick. Closing it
     * classifies the elapsed time and publishes an event if needed.
     */
    public final class Tick implements AutoCloseable {
        private final long frame;
        private final long startNanos;
        private boolean closed;

        private Tick(long frame, long startNanos) {
            this.frame = frame;
            this.startNanos = startNanos;
        }

        /** Returns the frame index assigned to this tick. */
        public long frame() {
            return frame;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            long elapsed = clockNanos.getAsLong() - startNanos;
            classify(frame, elapsed);
        }
    }
}
