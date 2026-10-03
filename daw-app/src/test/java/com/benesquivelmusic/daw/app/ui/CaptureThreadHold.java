package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.recording.CaptureFlushService;
import com.benesquivelmusic.daw.core.recording.RecordingPipeline;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds a real take's {@code capture-flush} thread, so that a test can see
 * what {@link TransportController} does on the FX thread while the take's
 * files are still being created or its seal is still running (PR #978 review
 * 5391920205). It is the take's monotonic clock: {@link #installOn} hands it
 * to every pipeline the controller builds, through
 * {@code setPipelineSetupForTest} and the pipeline's public
 * {@code setNanoClock}. The core's own holds (a held channel opener or
 * session factory) are package-private to daw-core and out of this module's
 * reach; the clock is read on the capture thread on the paths that matter.
 *
 * <p>Once {@link #arm() armed}, the capture thread's next read of the clock
 * waits until {@link #release()}. While the take is being prepared that read
 * is its first segment writer's — the take directory, the track directory
 * and the lane's {@code segment-000.wav.part} exist, the initial manifest
 * does not yet, and the take's readiness waits behind it; while recording it
 * is the capture thread's next clock read in its drain loop — normally the
 * disk-headroom check of the next block it applies, or the force-cadence
 * check of a segment holding bytes not yet forced; a new segment writer's
 * open at a rotation or a loop-lap wrap, and a manifest retry, read it too —
 * and the final sweep and the seal a Stop requests wait behind it. Only the
 * capture thread waits —
 * any other reader gets {@link System#nanoTime()} at once — and after the
 * release nobody waits again. The hold remembers the thread it held
 * ({@link #heldThread()}), so that a test can wait for that thread to end
 * ({@link #awaitTheHeldThreadEnded}). The wait is bounded at
 * {@link #HOLD_GUARD}, so a test that fails before it releases cannot hang
 * the suite; every test that arms it releases it, in a {@code finally} or
 * on its normal path, and every test class that uses it releases it again
 * in its {@code @AfterEach}.</p>
 */
final class CaptureThreadHold implements LongSupplier {

    /** The longest the capture thread waits for a release a test never gave. */
    static final Duration HOLD_GUARD = Duration.ofSeconds(30);

    private final CountDownLatch holding = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);
    private final List<RecordingPipeline> pipelines = new CopyOnWriteArrayList<>();
    private volatile boolean armed;
    /** The capture thread this hold held; {@code null} until it held one. */
    private volatile Thread heldThread;

    /** Hands this clock to every take {@code controller} builds from now on. FX thread. */
    void installOn(TransportController controller) {
        controller.setPipelineSetupForTest(pipeline -> {
            pipelines.add(pipeline);
            pipeline.setNanoClock(this);
        });
    }

    /** From now on the capture thread's next read of the clock waits for {@link #release()}. Any thread. */
    void arm() {
        armed = true;
    }

    /** Lets the capture thread go on; nothing waits again. Any thread; idempotent. */
    void release() {
        released.countDown();
    }

    /** Waits, at most {@code within}, until the capture thread is held, and asserts that it is. */
    void awaitHolding(Duration within) throws InterruptedException {
        assertThat(holdsWithin(within))
                .as("fixture: the take's capture thread is held within %d s", within.toSeconds()).isTrue();
    }

    /**
     * Waits, at most {@code within}, until the capture thread is held, and
     * returns whether it is — for a caller that must not throw an
     * {@link AssertionError} where it waits (a transport listener, say).
     */
    boolean holdsWithin(Duration within) throws InterruptedException {
        return holding.await(within.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** The capture thread this hold held; {@code null} until it held one. */
    Thread heldThread() {
        return heldThread;
    }

    /**
     * Waits, at most {@code within}, until the capture thread this hold held
     * has ended, and asserts that it has. Every FX turn that thread posted
     * before it ended — a take's readiness turn, for one — was posted before
     * any turn this caller posts afterwards. Any thread but the FX thread.
     */
    void awaitTheHeldThreadEnded(Duration within) throws InterruptedException {
        Thread held = heldThread;
        assertThat(held).as("fixture: the hold held a capture thread").isNotNull();
        held.join(within.toMillis());
        assertThat(held.isAlive()).as("fixture: the held capture thread ended within %d s", within.toSeconds())
                .isFalse();
    }

    /** The pipeline of the latest take built since {@link #installOn}. */
    RecordingPipeline pipeline() {
        assertThat(pipelines).as("fixture: a take's pipeline was built").isNotEmpty();
        return pipelines.getLast();
    }

    @Override
    public long getAsLong() {
        if (armed && released.getCount() > 0
                && CaptureFlushService.THREAD_NAME.equals(Thread.currentThread().getName())) {
            heldThread = Thread.currentThread();
            holding.countDown();
            try {
                released.await(HOLD_GUARD.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return System.nanoTime();
    }
}
