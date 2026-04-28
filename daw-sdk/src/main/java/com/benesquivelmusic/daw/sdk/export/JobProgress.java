package com.benesquivelmusic.daw.sdk.export;

import java.util.Objects;

/**
 * Immutable snapshot of a single progress event published by the offline
 * render queue. Every job emits a sequence of {@code JobProgress} events
 * starting with a {@code QUEUED} or {@code RUNNING} phase and ending with
 * one of {@link Phase#COMPLETED}, {@link Phase#FAILED}, or
 * {@link Phase#CANCELLED}.
 *
 * <p>The {@link #percent()} value is in the closed interval {@code [0.0, 1.0]}
 * and is monotonically non-decreasing for any single job until a terminal
 * phase is reached. The {@link #stage()} string is a free-form,
 * human-readable description of the current pipeline stage (e.g.,
 * {@code "Encoding WAV"}, {@code "Loudness normalization"}).</p>
 *
 * @param jobId   the {@link RenderJob#jobId() job id} this update belongs to
 * @param phase   the high-level lifecycle phase
 * @param stage   a free-form description of the active pipeline stage
 * @param percent progress in {@code [0.0, 1.0]}
 */
public record JobProgress(String jobId, Phase phase, String stage, double percent) {

    public JobProgress {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(stage, "stage");
        if (Double.isNaN(percent) || percent < 0.0 || percent > 1.0) {
            throw new IllegalArgumentException(
                    "percent must be in [0.0, 1.0]: " + percent);
        }
    }

    /** Lifecycle phase of a render job. */
    public enum Phase {
        /** Job is in the queue, waiting for a worker. */
        QUEUED,
        /** Job has been assigned to a worker and is rendering. */
        RUNNING,
        /** Job has been paused by the user and is awaiting resume. */
        PAUSED,
        /** Job finished successfully. */
        COMPLETED,
        /** Job failed with an exception. */
        FAILED,
        /** Job was cancelled by the user; partial outputs were cleaned up. */
        CANCELLED;

        public boolean isTerminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED;
        }
    }
}
