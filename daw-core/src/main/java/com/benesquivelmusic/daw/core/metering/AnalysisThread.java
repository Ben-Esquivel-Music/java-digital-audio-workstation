package com.benesquivelmusic.daw.core.metering;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The one dedicated analysis thread ({@code daw-metering-analysis}, book
 * &sect;4.3, &sect;6.1): a daemon <em>platform</em> thread — never a virtual
 * thread — started lazily on the first analysis attach, parked with a bounded
 * timeout and unparked by the render thread once per block through
 * {@link MeteringTapBus#blockCompleted(TapSnapshot)}.
 *
 * <p>Each pass reads the current lane array once (a volatile snapshot the
 * bus rebuilds immutably), drains each lane in order, and parks. A consumer
 * that throws is logged with a rate limit and skipped for that pass; the
 * others keep draining. Mirrors
 * {@code CallbackBackendAdapter.startDrainThread / stopDrainThread /
 * drainLoop}.</p>
 */
final class AnalysisThread {

    static final String THREAD_NAME = "daw-metering-analysis";
    static final long PARK_NANOS = 10_000_000L;
    static final long JOIN_TIMEOUT_MILLIS = 2_000L;

    private static final Logger LOG = Logger.getLogger(AnalysisThread.class.getName());
    private static final long LOG_FIRST_FAILURES = 5L;
    private static final long LOG_EVERY_NTH_FAILURE = 1_000L;

    private final Supplier<AnalysisLane[]> lanes;
    private volatile boolean running;
    private volatile Thread thread;
    /** Analysis-thread owned failure count for the rate-limited log. */
    private long failures;

    AnalysisThread(Supplier<AnalysisLane[]> lanes) {
        this.lanes = Objects.requireNonNull(lanes, "lanes must not be null");
    }

    /** Starts the thread. */
    void start() {
        if (thread != null) {
            throw new IllegalStateException("analysis thread already started");
        }
        running = true;
        Thread t = Thread.ofPlatform()
                .name(THREAD_NAME)
                .daemon(true)
                .unstarted(this::loop);
        thread = t;
        t.start();
    }

    /** Wakes the thread; the render thread's only cross-thread signal. */
    @RealTimeSafe
    void wake() {
        Thread t = thread;
        if (t != null) {
            LockSupport.unpark(t);
        }
    }

    /** Flags the loop to stop, unparks it, and joins with a bounded timeout. */
    void close() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t == null) {
            return;
        }
        LockSupport.unpark(t);
        try {
            t.join(JOIN_TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** {@code true} while the platform thread is alive (test seam). */
    boolean isAlive() {
        Thread t = thread;
        return t != null && t.isAlive();
    }

    private void loop() {
        while (running) {
            AnalysisLane[] current = lanes.get();
            for (AnalysisLane lane : current) {
                if (!running) {
                    break;
                }
                try {
                    lane.drain();
                } catch (RuntimeException e) {
                    logFailure(lane, e);
                }
            }
            if (running) {
                LockSupport.parkNanos(PARK_NANOS);
            }
        }
    }

    private void logFailure(AnalysisLane lane, RuntimeException e) {
        failures++;
        if (failures <= LOG_FIRST_FAILURES || failures % LOG_EVERY_NTH_FAILURE == 0L) {
            LOG.log(Level.WARNING, "analysis consumer failed (" + failures + " so far): " + lane, e);
        }
    }
}
