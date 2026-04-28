package com.benesquivelmusic.daw.sdk.export;

import java.nio.file.Path;

/**
 * Cooperation channel between the {@code RenderQueue} and a
 * {@link RenderJobRunner}. The runner uses this control object to:
 * <ul>
 *   <li>publish progress with {@link #publishProgress(String, double)};</li>
 *   <li>cooperate with cancellation via {@link #isCancelled()} or
 *       {@link #throwIfCancelled()};</li>
 *   <li>cooperate with pause / resume via {@link #checkpoint()};</li>
 *   <li>register partial-output paths via {@link #registerCleanupPath(Path)}
 *       so that the queue can delete them if the job is cancelled or fails.</li>
 * </ul>
 *
 * <p>Runners <em>must</em> call {@link #checkpoint()} (or
 * {@link #throwIfCancelled()}) at regular intervals; otherwise pause and
 * cancel will not take effect until the next progress publication.</p>
 */
public interface JobControl {

    /** @return {@code true} if cancellation has been requested. */
    boolean isCancelled();

    /**
     * Throws {@link InterruptedException} if cancellation has been requested.
     * Equivalent to {@code if (isCancelled()) throw new InterruptedException();}
     * but with a clearer call site.
     */
    default void throwIfCancelled() throws InterruptedException {
        if (isCancelled()) {
            throw new InterruptedException("Job cancelled");
        }
    }

    /**
     * Cooperative checkpoint. If a pause has been requested, this method
     * blocks (publishing a {@link JobProgress.Phase#PAUSED PAUSED} event)
     * until {@code resume} or {@code cancel} is invoked. If a cancel has
     * been requested it throws {@link InterruptedException}.
     *
     * @throws InterruptedException if the job has been cancelled
     */
    void checkpoint() throws InterruptedException;

    /**
     * Publish a progress event. Implicitly performs a {@link #checkpoint()}.
     *
     * @param stage   human-readable stage description
     * @param percent progress in {@code [0.0, 1.0]}
     * @throws InterruptedException if the job has been cancelled
     */
    void publishProgress(String stage, double percent) throws InterruptedException;

    /**
     * Register a path that should be deleted if the job is later cancelled
     * or fails. Runners should call this <em>before</em> creating each
     * output file so that the queue can guarantee a clean state.
     */
    void registerCleanupPath(Path path);
}
