package com.benesquivelmusic.daw.core.comping;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An undoable action that changes the comp selection on a {@link TakeComping}.
 *
 * <p>Executing this action replaces the current comp regions with a new set.
 * Undoing it restores the previous comp regions.</p>
 */
public final class SetCompSelectionAction implements UndoableAction {

    private final TakeComping takeComping;
    private final List<CompRegion> newRegions;
    private final List<CompRegion> previousRegions;

    /**
     * Creates a new set-comp-selection action.
     *
     * @param takeComping the take comping instance to modify
     * @param newRegions  the new comp regions to apply
     */
    public SetCompSelectionAction(TakeComping takeComping, List<CompRegion> newRegions) {
        this.takeComping = Objects.requireNonNull(takeComping, "takeComping must not be null");
        Objects.requireNonNull(newRegions, "newRegions must not be null");
        this.newRegions = List.copyOf(newRegions);
        this.previousRegions = new ArrayList<>(takeComping.getCompRegions());
    }

    @Override
    public String description() {
        return "Set Comp Selection";
    }

    @Override
    public void execute() {
        takeComping.setCompRegions(newRegions);
    }

    @Override
    public void undo() {
        takeComping.setCompRegions(previousRegions);
    }
}
