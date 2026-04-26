package com.benesquivelmusic.daw.sdk.store;

import com.benesquivelmusic.daw.sdk.event.ProjectChange;
import com.benesquivelmusic.daw.sdk.model.Project;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Authoritative holder of the immutable
 * {@link com.benesquivelmusic.daw.sdk.model.Project Project} snapshot.
 *
 * <p>The store keeps the current snapshot in an {@link AtomicReference}, so
 * concurrent reads via {@link #project()} are lock-free and always observe
 * a fully constructed value. Writes ({@link #apply(CompoundAction)},
 * {@link #replace(Project)}) are serialized by an internal
 * {@link ReentrantLock writeLock}, which guarantees:</p>
 *
 * <ul>
 *   <li>commit order is well-defined — no two writers can interleave the
 *       compute / install / publish steps;</li>
 *   <li>{@link ProjectChange} events are submitted to subscribers in the
 *       same order in which their corresponding transitions were
 *       committed.</li>
 * </ul>
 *
 * <h2>Subscriber threading and backpressure</h2>
 *
 * <p>Subscribers are notified on a private, daemon-backed
 * {@link ExecutorService} configured into the
 * {@link SubmissionPublisher}. Daemon threads do not prevent JVM exit and
 * are not shared with the {@link java.util.concurrent.ForkJoinPool#commonPool()
 * common pool}, so a misbehaving subscriber cannot starve unrelated
 * parallel workloads.</p>
 *
 * <p>Events are delivered with {@link SubmissionPublisher#offer(Object,
 * java.util.function.BiPredicate) offer(item, onDrop)}. If a subscriber's
 * buffer is full the item is dropped for that subscriber and a warning is
 * logged — writers are never blocked by a slow subscriber. Subscribers
 * that need lossless delivery are responsible for keeping up with the
 * publisher.</p>
 */
public final class ProjectStore implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ProjectStore.class.getName());

    private final AtomicReference<Project> current;
    private final SubmissionPublisher<ProjectChange> publisher;
    private final ExecutorService publisherExecutor;
    private final ReentrantLock writeLock = new ReentrantLock();

    public ProjectStore(Project initial) {
        this.current = new AtomicReference<>(Objects.requireNonNull(initial, "initial must not be null"));
        // Private daemon-backed executor so subscribers run on threads that
        // do not block JVM shutdown and are isolated from the common pool.
        this.publisherExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "daw-project-store-publisher");
            t.setDaemon(true);
            return t;
        });
        this.publisher = new SubmissionPublisher<>(publisherExecutor, Flow.defaultBufferSize());
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
        return applyForTransition(action).after();
    }

    /**
     * Applies the supplied action atomically and returns the
     * {@link Transition} (the {@code before} and {@code after} snapshots).
     *
     * <p>This is the preferred entry point for callers that need to record
     * the transition (e.g. {@link UndoManager}). Because the entire
     * compute / install / publish sequence is performed under the
     * write-lock, the returned {@code before} is guaranteed to be the
     * snapshot the action was actually applied to — no other writer can
     * have interleaved between the read of the current snapshot and the
     * commit.</p>
     *
     * @param action the reducer to apply (must not be {@code null})
     * @return the transition that was committed
     */
    public Transition applyForTransition(CompoundAction action) {
        Objects.requireNonNull(action, "action must not be null");
        writeLock.lock();
        try {
            Project before = current.get();
            Project after = Objects.requireNonNull(action.apply(before),
                    "CompoundAction must not return null");
            current.set(after);

            if (before == after || before.equals(after)) {
                return new Transition(before, after);
            }
            for (ProjectChange event : ProjectDiff.diff(before, after)) {
                publish(event);
            }
            return new Transition(before, after);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Replaces the current snapshot with {@code snapshot}, emitting a
     * {@link ProjectChange.ProjectReplaced ProjectReplaced} event in
     * addition to the per-entity diff. Used by {@link UndoManager} for
     * undo/redo.
     */
    public Project replace(Project snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        writeLock.lock();
        try {
            Project before = current.get();
            current.set(snapshot);
            if (before.equals(snapshot)) {
                return snapshot;
            }
            publish(new ProjectChange.ProjectReplaced(before, snapshot));
            for (ProjectChange event : ProjectDiff.diff(before, snapshot)) {
                publish(event);
            }
            return snapshot;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Non-blocking publish. Drops the event for any subscriber whose
     * buffer is full and logs a warning so writers are never stalled by a
     * slow consumer.
     */
    private void publish(ProjectChange event) {
        publisher.offer(event, (subscriber, item) -> {
            LOG.log(Level.WARNING,
                    () -> "ProjectStore: dropping change event for slow subscriber "
                            + subscriber + ": " + item);
            return false; // do not retry
        });
    }

    @Override
    public void close() {
        publisher.close();
        publisherExecutor.shutdown();
    }

    /**
     * A successfully committed transition — the {@code before} and
     * {@code after} immutable project snapshots.
     */
    public record Transition(Project before, Project after) {
        public Transition {
            Objects.requireNonNull(before, "before must not be null");
            Objects.requireNonNull(after, "after must not be null");
        }
    }
}
