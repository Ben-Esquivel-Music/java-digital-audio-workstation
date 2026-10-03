package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The one way a test starts and stops a {@link RecordingPipeline} when the
 * start or the stop is not itself what the test is about: the pipeline's
 * caller-thread lifecycle — {@link RecordingPipeline#prepare()} then
 * {@link RecordingPipeline#beginCapture()} or
 * {@link RecordingPipeline#cancelStart()}, {@link RecordingPipeline#requestStop()}
 * then {@link RecordingPipeline#completeStop()} — with the waits between the
 * steps that a test thread may make and the FX thread never does.
 *
 * <p>Every wait is bounded by {@link #LIFECYCLE_GUARD}: daw-core has no
 * default JUnit timeout, so a readiness or a termination that never comes
 * fails the test instead of hanging the suite.</p>
 */
public final class PipelineLifecycleTestSupport {

    /** Bound of every wait here; the same as {@link RampCaptureTestSupport#HANG_GUARD}. */
    public static final Duration LIFECYCLE_GUARD = RampCaptureTestSupport.HANG_GUARD;

    /** Every pipeline startRecording was given since forgetStartedTakes; see endEveryStartedTake. */
    private static final List<RecordingPipeline> STARTED = new CopyOnWriteArrayList<>();

    private PipelineLifecycleTestSupport() {
    }

    /**
     * Forgets the pipelines {@link #startRecording} was given so far (the
     * {@link CaptureFlushThreadLeakGuard} calls it before each test).
     */
    static void forgetStartedTakes() {
        STARTED.clear();
    }

    /**
     * Ends every take a pipeline given to {@link #startRecording} since
     * {@link #forgetStartedTakes()} left running: a take still being prepared
     * is cancelled, a recording or finalising one is stopped and its stop
     * completed, and each waits (bounded) for its flush thread's termination —
     * how that thread ended is not judged here (the test did), only that it
     * ended within the guard.
     * Every pipeline is tried even if an earlier one fails; the first failure
     * is thrown with the later ones suppressed. The registry is cleared.
     */
    static void endEveryStartedTake() {
        List<RecordingPipeline> started = List.copyOf(STARTED);
        STARTED.clear();
        AssertionError failure = null;
        for (RecordingPipeline pipeline : started.reversed()) {
            try {
                endTheTake(pipeline);
            } catch (AssertionError | RuntimeException e) {
                if (failure == null) {
                    failure = new AssertionError("ending the take of " + pipeline.getTakeDirectory() + " failed", e);
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Ends whatever take {@code pipeline} has left running: a take still being
     * prepared is cancelled, a recording or finalising one is stopped and its
     * stop completed, and in every case it waits (bounded) for the flush
     * thread's termination. How that thread ended is not judged here — the
     * test did — only that it ended within {@link #LIFECYCLE_GUARD}.
     *
     * @param pipeline the pipeline whose take to end
     * @throws AssertionError if the termination does not come within the guard
     */
    static void endTheTake(RecordingPipeline pipeline) {
        if (pipeline.isPreparing()) {
            failureWithinTheGuard(pipeline.cancelStart(), "the termination of a cancelled start at teardown");
        } else if (pipeline.isActive() || pipeline.isFinalizationPending()) {
            failureWithinTheGuard(pipeline.requestStop(), "the capture-flush thread's termination after a stop at teardown");
            pipeline.completeStop();
        } else {
            failureWithinTheGuard(pipeline.termination(), "the capture-flush thread's termination at teardown");
        }
    }

    /**
     * Ends the flush thread {@code pipeline}'s current take left behind
     * ({@link #endTheTake}), joins it (bounded) and fails if it is still
     * alive. For a test's {@code @AfterEach}, after any hold has been released.
     *
     * @param pipeline the pipeline, or {@code null} when the test made none
     */
    static void endTheTakeAndAssertItsFlushThreadEnded(RecordingPipeline pipeline) throws InterruptedException {
        if (pipeline == null) {
            return;
        }
        endTheTake(pipeline);
        CaptureFlushService service = pipeline.getCaptureFlushService();
        if (service != null) {
            service.thread().join(LIFECYCLE_GUARD.toMillis());
            if (service.thread().isAlive()) {
                throw new AssertionError("the capture-flush thread of " + service.takeDirectory()
                        + " is still alive " + LIFECYCLE_GUARD + " after its take was ended");
            }
        }
    }

    /**
     * Starts recording, all or nothing: prepares the take, waits (bounded)
     * for its readiness, and begins capture once it has completed normally.
     * When readiness fails, it cancels the start, waits (bounded) for the
     * flush thread's termination — by then that thread has deleted the
     * segment and manifest files the take created, and each track directory
     * it created, if that left it empty (best-effort: what an I/O
     * error keeps from being deleted is left and the error logged)
     * — and rethrows the readiness failure as it was given (an
     * {@link java.io.UncheckedIOException}, any other unchecked throwable,
     * or a {@link CancellationException}). A failure of {@code prepare()}
     * or {@code beginCapture()} itself propagates as thrown; both roll
     * themselves back.
     *
     * @param pipeline the pipeline to start
     */
    public static void startRecording(RecordingPipeline pipeline) {
        STARTED.add(pipeline);
        CompletionStage<Void> readiness = pipeline.prepare();
        Throwable notReady = failureWithinTheGuard(readiness, "the take's readiness");
        if (notReady != null) {
            awaitWithinTheGuard(pipeline.cancelStart(), "the capture-flush thread's termination after a failed start");
            throw asUnchecked(notReady);
        }
        pipeline.beginCapture();
    }

    /**
     * Stops recording and returns the take's clips: requests the stop, waits
     * (bounded) for the flush thread's termination, then completes the stop.
     * A throwable of {@code requestStop()} propagates as thrown — by then the
     * seal has been requested all the same.
     *
     * @param pipeline the pipeline to stop
     * @return what {@link RecordingPipeline#completeStop()} returned
     */
    public static List<AudioClip> stopRecording(RecordingPipeline pipeline) {
        awaitWithinTheGuard(pipeline.requestStop(), "the capture-flush thread's termination after a stop");
        return pipeline.completeStop();
    }

    /**
     * Waits (bounded) for the flush thread of {@code service} to terminate.
     *
     * @param service the flush service
     */
    public static void awaitTermination(CaptureFlushService service) {
        awaitWithinTheGuard(service.termination(), "the capture-flush thread's termination");
    }

    /**
     * Waits for {@code stage} to complete normally, for at most
     * {@link #LIFECYCLE_GUARD}, and returns its value.
     *
     * @param stage the stage
     * @param what  what the stage signals, for the failure message
     * @return the stage's value
     * @throws AssertionError if the stage completes exceptionally, or not within the guard
     */
    public static <T> T awaitWithinTheGuard(CompletionStage<T> stage, String what) {
        try {
            return stage.toCompletableFuture().get(LIFECYCLE_GUARD.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new AssertionError(what + " completed exceptionally", e.getCause());
        } catch (CancellationException e) {
            throw new AssertionError(what + " completed exceptionally", e);
        } catch (TimeoutException e) {
            throw new AssertionError(what + " did not complete within " + LIFECYCLE_GUARD, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for " + what, e);
        }
    }

    /**
     * Waits for {@code stage} to complete, for at most {@link #LIFECYCLE_GUARD},
     * and returns how it failed: {@code null} when it completed normally,
     * otherwise the throwable it completed with.
     *
     * @param stage the stage
     * @param what  what the stage signals, for the failure message
     * @return the failure, or {@code null}
     * @throws AssertionError if the stage does not complete within the guard
     */
    public static Throwable failureWithinTheGuard(CompletionStage<?> stage, String what) {
        try {
            stage.toCompletableFuture().get(LIFECYCLE_GUARD.toMillis(), TimeUnit.MILLISECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (CancellationException e) {
            return e;
        } catch (TimeoutException e) {
            throw new AssertionError(what + " did not complete within " + LIFECYCLE_GUARD, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for " + what, e);
        }
    }

    private static RuntimeException asUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("a readiness failure is always unchecked", failure);
    }
}
