package com.benesquivelmusic.daw.sdk.store;

import com.benesquivelmusic.daw.sdk.model.Project;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * A pure reducer over the immutable {@link Project} snapshot.
 *
 * <p>A {@code CompoundAction} represents a logical write operation: it
 * receives the current project value and returns a new project value with
 * the changes applied. The reducer must be referentially transparent — no
 * I/O, no side effects on shared mutable state, and no retained references
 * to the input. This contract is what lets
 * {@link ProjectStore} apply actions atomically and lets
 * {@link UndoManager} swap snapshots without surprises.</p>
 *
 * <p>Compose multiple actions with {@link #andThen(CompoundAction)} so a
 * single dispatch can encompass several related edits and emit a single
 * undo entry.</p>
 */
@FunctionalInterface
public interface CompoundAction extends UnaryOperator<Project> {

    /**
     * Reduce {@code current} to a new project value.
     *
     * @param current the current snapshot (never {@code null})
     * @return the next snapshot (never {@code null})
     */
    @Override
    Project apply(Project current);

    /** Returns a composed action that first applies {@code this} then {@code after}. */
    default CompoundAction andThen(CompoundAction after) {
        Objects.requireNonNull(after, "after must not be null");
        return current -> after.apply(this.apply(current));
    }

    /** The identity action — useful as a starting accumulator for reductions. */
    static CompoundAction identity() {
        return current -> current;
    }
}
