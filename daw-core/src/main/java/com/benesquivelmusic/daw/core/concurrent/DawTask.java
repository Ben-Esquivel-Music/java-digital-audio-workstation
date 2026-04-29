package com.benesquivelmusic.daw.core.concurrent;

import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * A unit of non-realtime work submitted to {@link DawTaskRunner}.
 *
 * <p>A {@code DawTask} bundles three things:</p>
 * <ol>
 *   <li>A human-readable {@link #name() name} used as the carrier
 *       thread label and surfaced in {@link DawTaskRunner#snapshot()}
 *       to spot leaks in the debug view.</li>
 *   <li>A {@link #category() category} that decides whether the runner
 *       schedules the work on a virtual thread (I/O) or the bounded
 *       platform pool (CPU).</li>
 *   <li>The actual {@link #work() work} to execute.</li>
 * </ol>
 *
 * <p>This is a record (JEP 395) so it is shallowly immutable and
 * trivially safe to share between threads.</p>
 *
 * @param <T> the result type
 */
public record DawTask<T>(String name, TaskCategory category, Callable<T> work) {

    public DawTask {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(work, "work must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    /**
     * Convenience factory for tasks that do not return a value.
     *
     * @param name     human-readable task name (e.g., {@code "import:kick.wav"})
     * @param category routing category
     * @param work     side-effecting work
     * @return a new {@code DawTask} that returns {@code null} on success
     */
    public static DawTask<Void> ofRunnable(String name, TaskCategory category, Runnable work) {
        Objects.requireNonNull(work, "work must not be null");
        return new DawTask<>(name, category, () -> {
            work.run();
            return null;
        });
    }
}
