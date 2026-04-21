package com.benesquivelmusic.daw.core.undo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An undoable action that groups multiple {@link UndoableAction}s into a
 * single undo/redo step.
 *
 * <p>Executing this action executes all child actions in order. Undoing
 * reverses all child actions in reverse order. This ensures that a group
 * of related operations (e.g. moving multiple clips) can be undone and
 * redone as a single unit.</p>
 */
public final class CompoundUndoableAction implements UndoableAction {

    private final String desc;
    private final List<UndoableAction> actions;

    /**
     * Creates a compound action from the given description and child actions.
     *
     * @param description a short, human-readable description of this compound action
     * @param actions     the child actions to execute as a group (must not be empty)
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if {@code actions} is empty
     */
    public CompoundUndoableAction(String description, List<UndoableAction> actions) {
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(actions, "actions must not be null");
        if (actions.isEmpty()) {
            throw new IllegalArgumentException("actions must not be empty");
        }
        this.desc = description;
        this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
    }

    @Override
    public String description() {
        return desc;
    }

    /** Returns the immutable list of child actions. */
    public List<UndoableAction> getActions() {
        return actions;
    }

    @Override
    public void execute() {
        for (UndoableAction action : actions) {
            action.execute();
        }
    }

    @Override
    public void undo() {
        for (int i = actions.size() - 1; i >= 0; i--) {
            actions.get(i).undo();
        }
    }
}
