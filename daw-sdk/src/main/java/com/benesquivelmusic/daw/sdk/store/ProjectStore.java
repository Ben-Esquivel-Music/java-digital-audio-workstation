package com.benesquivelmusic.daw.sdk.store;

import com.benesquivelmusic.daw.sdk.event.ProjectChange;
import com.benesquivelmusic.daw.sdk.model.Project;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Authoritative holder of the immutable
 * {@link com.benesquivelmusic.daw.sdk.model.Project Project} snapshot.
 *
 * <p>The store keeps the current snapshot in an {@link AtomicReference}, so
 * concurrent reads via {@link #project()} are lock-free and always observe
 * a fully constructed value. Writes are channeled through
 * {@link #apply(CompoundAction)}, which:</p>
 *
 * <ol>
 *   <li>computes the next snapshot by applying the supplied action to the
 *       current one;</li>
 *   <li>installs it atomically via compare-and-set;</li>
 *   <li>computes the per-entity {@link ProjectChange} list by diffing the
 *       previous and next snapshots; and</li>
 *   <li>submits each change to a {@link Flow.Publisher Flow.Publisher} for
 *       interested subscribers.</li>
 * </ol>
 *
 * <p>If a competing thread updates the snapshot between steps 1 and 2 the
 * action is re-applied to the latest value (optimistic retry). Actions
 * therefore must be idempotent in their reasoning about the input
 * snapshot — typically true since they are pure reducers.</p>
 *
 * <p>Subscribers are notified on a private, daemon-backed
 * {@link SubmissionPublisher} executor, so a slow subscriber will not
 * block writers.</p>
 */
public final class ProjectStore implements AutoCloseable {

    private final AtomicReference<Project> current;
    private final SubmissionPublisher<ProjectChange> publisher;

    public ProjectStore(Project initial) {
        this.current = new AtomicReference<>(Objects.requireNonNull(initial, "initial must not be null"));
        this.publisher = new SubmissionPublisher<>();
    }

    /** Returns the current immutable snapshot. Lock-free. */
    public Project project() {
        return current.get();
    }

    /** Returns the {@link Flow.Publisher} that emits {@link ProjectChange} events. */
    public Flow.Publisher<ProjectChange> changes() {
        return publisher;
    }

    /**
     * Applies the supplied action atomically and publishes any resulting
     * {@link ProjectChange} events.
     *
     * @param action the reducer to apply (must not be {@code null})
     * @return the new project snapshot after the action has been applied
     */
    public Project apply(CompoundAction action) {
        Objects.requireNonNull(action, "action must not be null");
        Project before;
        Project after;
        do {
            before = current.get();
            after = Objects.requireNonNull(action.apply(before),
                    "CompoundAction must not return null");
        } while (!current.compareAndSet(before, after));

        if (before == after || before.equals(after)) {
            return after;
        }

        List<ProjectChange> events = ProjectDiff.diff(before, after);
        for (ProjectChange event : events) {
            publisher.submit(event);
        }
        return after;
    }

    /**
     * Replaces the current snapshot with {@code snapshot}, emitting a
     * {@link ProjectChange.ProjectReplaced ProjectReplaced} event in
     * addition to the per-entity diff. Used by {@link UndoManager} for
     * undo/redo.
     */
    public Project replace(Project snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Project before = current.getAndSet(snapshot);
        if (before.equals(snapshot)) {
            return snapshot;
        }
        publisher.submit(new ProjectChange.ProjectReplaced(before, snapshot));
        for (ProjectChange event : ProjectDiff.diff(before, snapshot)) {
            publisher.submit(event);
        }
        return snapshot;
    }

    @Override
    public void close() {
        publisher.close();
    }
}
