package com.benesquivelmusic.daw.core.recording;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Thrown when a Stop gave up waiting for the {@code capture-flush} thread:
 * the bounded join ({@link CaptureFlushService#STOP_JOIN_TIMEOUT}) ran out
 * while the thread was still finalising the take — draining, sealing, or
 * writing the manifest on a slow or stuck filesystem (story 323 review) —
 * or, made on that thread itself before it had terminated, the Stop could
 * not wait for it at all.
 *
 * <p>Nothing of the take has been read or published when this is thrown:
 * {@link RecordingPipeline#stop()} builds no clip, adds no clip or take
 * group to any {@link com.benesquivelmusic.daw.core.track.Track} and
 * records nothing in {@link RecordingPipeline#getRecordedClips()} until the
 * thread has terminated, because the thread is still writing the state the
 * clips are built from (the one-shot stop has already cleared the tracks'
 * recording flags). The pipeline stays
 * {@linkplain RecordingPipeline#isFinalizationPending() finalization pending}:
 * a {@code stop()} made once the thread has terminated
 * ({@link CaptureFlushService#isTerminated()}, which is set before
 * {@link #completion()} completes) neither wakes nor joins it, finishes the
 * stop and returns the clips the normal path would have returned. One made
 * before that, on any thread but the flush thread, wakes it and waits the
 * bounded join again, throwing again if the thread has still not terminated
 * when the join ends; one made on the flush thread itself throws again
 * without waiting.</p>
 *
 * <p>This is never an empty result in disguise: an empty list from
 * {@code stop()} still means "nothing was recorded".</p>
 */
public final class TakeFinalizationPendingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Not serialized: meaningful only in the JVM that recorded the take. */
    private final transient Path takeDirectory;
    /** Not serialized: meaningful only in the JVM that recorded the take. */
    private final transient CompletionStage<Void> completion;

    /**
     * Creates the exception.
     *
     * @param takeDirectory the directory of the take that is still being written
     * @param waited        the join bound: how long the Stop waited for the
     *                      thread before giving up (a Stop made on the flush
     *                      thread itself waited for no join)
     * @param completion    completes normally when the {@code capture-flush}
     *                      thread has terminated
     */
    public TakeFinalizationPendingException(Path takeDirectory, Duration waited,
                                            CompletionStage<Void> completion) {
        super("The take under " + Objects.requireNonNull(takeDirectory, "takeDirectory must not be null")
                + " is still being written to disk: capture-flush did not finish within "
                + Objects.requireNonNull(waited, "waited must not be null")
                + "; nothing of it is read until the thread has terminated, and completion()"
                + " completes then");
        this.takeDirectory = takeDirectory;
        this.completion = Objects.requireNonNull(completion, "completion must not be null");
    }

    /** Returns the directory of the take that is still being written. */
    public Path takeDirectory() {
        return takeDirectory;
    }

    /**
     * Returns the signal that completes normally when the {@code capture-flush}
     * thread has terminated — on every path that ends it. A dependent
     * registered with a non-async method may run on that thread as its last
     * act; register the work that completes the stop with an async variant
     * and the executor of the thread that owns the tracks.
     */
    public CompletionStage<Void> completion() {
        return completion;
    }
}
