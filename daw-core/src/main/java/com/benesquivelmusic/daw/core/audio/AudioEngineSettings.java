package com.benesquivelmusic.daw.core.audio;

/**
 * Persistent settings for the audio engine's parallel graph scheduler.
 *
 * <p>These settings are user-facing (intended to be exposed through the
 * audio settings dialog) and persisted across sessions. Defaults target
 * the best out-of-the-box balance between throughput and real-time safety
 * on a modern multi-core machine.</p>
 *
 * @param workerPoolSize        the number of platform worker threads the
 *                              {@link AudioWorkerPool} should spawn; a
 *                              value of {@code 1} disables parallelism.
 *                              The default is
 *                              {@code max(1, Runtime.availableProcessors() - 2)}
 *                              so the audio callback thread and the OS
 *                              still get dedicated cores.
 * @param minParallelBlockSize  the minimum block size (frames) at which
 *                              parallel dispatch is attempted; smaller
 *                              blocks fall back to inline execution
 *                              because coordination overhead exceeds the
 *                              gain. The default is
 *                              {@link AudioGraphScheduler#DEFAULT_MIN_PARALLEL_BLOCK_SIZE}.
 */
public record AudioEngineSettings(int workerPoolSize, int minParallelBlockSize) {

    /** Validates ranges and normalizes to safe values. */
    public AudioEngineSettings {
        if (workerPoolSize <= 0) {
            throw new IllegalArgumentException(
                    "workerPoolSize must be positive: " + workerPoolSize);
        }
        if (minParallelBlockSize <= 0) {
            throw new IllegalArgumentException(
                    "minParallelBlockSize must be positive: " + minParallelBlockSize);
        }
    }

    /**
     * Returns default settings that adapt the worker pool size to the
     * number of CPU cores available to the JVM.
     *
     * @return the default settings
     */
    public static AudioEngineSettings defaults() {
        int cores = Runtime.getRuntime().availableProcessors();
        // Leave the audio callback core and one core for the OS to avoid
        // preempting either with DSP work at MAX_PRIORITY.
        int suggested = Math.max(1, cores - 2);
        return new AudioEngineSettings(
                suggested, AudioGraphScheduler.DEFAULT_MIN_PARALLEL_BLOCK_SIZE);
    }

    /**
     * Returns a copy of these settings with the worker pool size replaced.
     *
     * @param workerPoolSize the new worker pool size
     * @return a new settings instance
     */
    public AudioEngineSettings withWorkerPoolSize(int workerPoolSize) {
        return new AudioEngineSettings(workerPoolSize, minParallelBlockSize);
    }

    /**
     * Returns a copy of these settings with the minimum parallel block size
     * replaced.
     *
     * @param minParallelBlockSize the new minimum parallel block size
     * @return a new settings instance
     */
    public AudioEngineSettings withMinParallelBlockSize(int minParallelBlockSize) {
        return new AudioEngineSettings(workerPoolSize, minParallelBlockSize);
    }
}
