package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Dependency-aware scheduler that distributes independent branches of the
 * per-block audio graph across an {@link AudioWorkerPool}.
 *
 * <p>The single biggest CPU cost in {@link com.benesquivelmusic.daw.core.mixer.Mixer#mixDown}
 * is per-channel insert-effect processing. Tracks that do not share a
 * sidechain source process their insert chains entirely within their own
 * scratch buffer ({@code channelBuffers[i]}) and are therefore independent
 * — they can be computed on separate worker threads without any locking.
 * The subsequent summing, send-routing, and delay-compensation phases
 * remain sequential on the audio callback thread, preserving the exact
 * floating-point summation order of the single-threaded path. This is
 * critical for the <strong>bit-exact output</strong> correctness contract.</p>
 *
 * <p>Channels whose insert chain has a {@linkplain
 * com.benesquivelmusic.daw.core.mixer.InsertSlot#getSidechainSource()
 * sidechain source} read from other channels' buffers; to avoid a data
 * race the scheduler leaves those channels to the sequential pass.</p>
 *
 * <h2>Fallback</h2>
 *
 * <p>Parallelism incurs a non-negligible coordination overhead (wake-up,
 * task dispatch, join). For very small block sizes or trivial worker pool
 * configurations this overhead exceeds the gain. The scheduler falls back
 * to inline single-threaded execution when any of the following hold:</p>
 * <ul>
 *   <li>The pool size is {@code <= 1}</li>
 *   <li>The block size is smaller than {@link #getMinParallelBlockSize()}
 *       (default {@value #DEFAULT_MIN_PARALLEL_BLOCK_SIZE} samples)</li>
 *   <li>Fewer than two parallelizable tasks would be dispatched</li>
 * </ul>
 */
public final class AudioGraphScheduler {

    /**
     * Default minimum block size in frames at which parallel processing is
     * enabled. Below this threshold the coordination overhead typically
     * exceeds the throughput gain of parallelism.
     */
    public static final int DEFAULT_MIN_PARALLEL_BLOCK_SIZE = 64;

    private final AudioWorkerPool pool;
    private final int minParallelBlockSize;

    // Pre-allocated task array so scheduling is allocation-free on the audio thread.
    private final Runnable[] taskSlots;
    private final ChannelTask[] taskImpls;
    /** Live "threads in use" meter — the number of tasks dispatched last block. */
    private volatile int lastDispatchedTaskCount;

    /**
     * Creates a scheduler bound to the given worker pool with the default
     * minimum parallel block size.
     *
     * @param pool      the worker pool (non-null)
     * @param maxTracks the maximum number of concurrent channel tasks the
     *                  scheduler should be prepared to dispatch (typically
     *                  the maximum mixer channel count)
     */
    public AudioGraphScheduler(AudioWorkerPool pool, int maxTracks) {
        this(pool, maxTracks, DEFAULT_MIN_PARALLEL_BLOCK_SIZE);
    }

    /**
     * Creates a scheduler bound to the given worker pool.
     *
     * @param pool                 the worker pool (non-null)
     * @param maxTracks            the maximum number of concurrent channel
     *                             tasks the scheduler should be prepared to
     *                             dispatch
     * @param minParallelBlockSize the minimum block size (frames) at which
     *                             the scheduler will dispatch tasks in
     *                             parallel; smaller blocks run inline
     */
    public AudioGraphScheduler(AudioWorkerPool pool, int maxTracks, int minParallelBlockSize) {
        this.pool = Objects.requireNonNull(pool, "pool must not be null");
        if (maxTracks <= 0) {
            throw new IllegalArgumentException("maxTracks must be positive: " + maxTracks);
        }
        if (minParallelBlockSize <= 0) {
            throw new IllegalArgumentException(
                    "minParallelBlockSize must be positive: " + minParallelBlockSize);
        }
        this.minParallelBlockSize = minParallelBlockSize;
        this.taskSlots = new Runnable[maxTracks];
        this.taskImpls = new ChannelTask[maxTracks];
        for (int i = 0; i < maxTracks; i++) {
            this.taskImpls[i] = new ChannelTask();
            this.taskSlots[i] = this.taskImpls[i];
        }
    }

    /** Returns the underlying worker pool. */
    public AudioWorkerPool getWorkerPool() {
        return pool;
    }

    /** Returns the minimum block size (frames) at which parallelism is enabled. */
    public int getMinParallelBlockSize() {
        return minParallelBlockSize;
    }

    /**
     * Returns the number of parallel channel tasks dispatched during the
     * most recent {@link #processInsertsParallel} call. Intended as a live
     * "threads in use" meter for UI diagnostics.
     *
     * @return the dispatched task count from the last invocation
     */
    public int getLastDispatchedTaskCount() {
        return lastDispatchedTaskCount;
    }

    /**
     * Processes the insert-effect chain on each eligible channel in
     * parallel and records which channels were handled in {@code processed}.
     *
     * <p>A channel is eligible when its insert chain is non-empty, the
     * channel is not muted, the solo state permits it to contribute, and
     * its chain has no sidechain routing. The sequential phase of
     * {@link com.benesquivelmusic.daw.core.mixer.Mixer#mixDown} is expected
     * to skip re-processing any channel whose flag is {@code true} in
     * {@code processed}.</p>
     *
     * <p>If the pool or block size does not meet the parallelism threshold,
     * this method is a no-op: {@code processed} will be all {@code false}
     * and the caller will run the sequential fallback as usual.</p>
     *
     * @param channels        the mixer's full channel list (in mixer order)
     * @param channelBuffers  per-channel audio data
     *                        {@code [mixerChannel][audioChannel][frame]}
     * @param numFrames       the number of sample frames in this block
     * @param anySolo         whether any channel is currently soloed
     * @param hasSidechain    predicate identifying channels with sidechain
     *                        routing that must run sequentially
     * @param processed       output flags, one per channel; entries set to
     *                        {@code true} indicate the insert chain has
     *                        already been applied and the sequential pass
     *                        must not re-apply it
     */
    @RealTimeSafe
    public void processInsertsParallel(List<MixerChannel> channels,
                                        float[][][] channelBuffers,
                                        int numFrames,
                                        boolean anySolo,
                                        Predicate<MixerChannel> hasSidechain,
                                        boolean[] processed) {
        lastDispatchedTaskCount = 0;
        if (pool.size() <= 1 || numFrames < minParallelBlockSize) {
            return;
        }

        final int channelCount = Math.min(channels.size(), channelBuffers.length);
        final int limit = Math.min(channelCount, taskImpls.length);
        if (processed.length < limit) {
            throw new IllegalArgumentException(
                    "processed.length must be at least " + limit + " but was " + processed.length);
        }
        int taskIdx = 0;

        for (int i = 0; i < limit; i++) {
            MixerChannel channel = channels.get(i);
            if (channel.isMuted()) {
                continue;
            }
            if (anySolo && !channel.isSolo()) {
                continue;
            }
            if (channel.getEffectsChain().isEmpty()) {
                continue;
            }
            if (hasSidechain.test(channel)) {
                continue;
            }
            ChannelTask task = taskImpls[taskIdx++];
            task.configure(channel, channelBuffers[i], numFrames, processed, i);
        }

        // Require at least two eligible tasks to amortize coordination cost.
        if (taskIdx < 2) {
            return;
        }

        lastDispatchedTaskCount = taskIdx;
        pool.invokeAll(taskSlots, taskIdx);

        // Clear references held by ChannelTask instances so large audio buffers
        // and mixer channels are not retained between blocks / across sessions.
        for (int i = 0; i < taskIdx; i++) {
            taskImpls[i].clearReferences();
        }
    }

    /**
     * Reusable, stateless-between-batches task that applies one channel's
     * insert chain in place. Instances are pre-allocated in the scheduler
     * constructor; {@link #configure} mutates fields only from the audio
     * thread prior to dispatch, establishing a happens-before with workers
     * via the subsequent publication of the task batch.
     */
    private static final class ChannelTask implements Runnable {
        private MixerChannel channel;
        private float[][] buffer;
        private int numFrames;
        private boolean[] processed;
        private int channelIndex;

        void configure(MixerChannel channel, float[][] buffer, int numFrames,
                       boolean[] processed, int channelIndex) {
            this.channel = channel;
            this.buffer = buffer;
            this.numFrames = numFrames;
            this.processed = processed;
            this.channelIndex = channelIndex;
        }

        /** Clears retained references to allow GC between blocks / sessions. */
        void clearReferences() {
            this.channel = null;
            this.buffer = null;
            this.processed = null;
        }

        @Override
        @RealTimeSafe
        public void run() {
            channel.getEffectsChain().process(buffer, buffer, numFrames);
            processed[channelIndex] = true;
        }
    }
}
