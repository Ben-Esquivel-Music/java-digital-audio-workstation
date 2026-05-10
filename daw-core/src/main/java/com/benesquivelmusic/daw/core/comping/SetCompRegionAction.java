package com.benesquivelmusic.daw.core.comping;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Undoable action that adds (or replaces) a single {@link CompRegion} on a
 * {@link TakeComping}, applying the standard "swipe comping" behavior:
 * the new region's beat range deselects any overlapping regions on every
 * other take of the same track.
 *
 * <p>This is the action invoked by the comp tool ({@code EditTool.COMP})
 * when the user click-drags across a take's waveform.</p>
 */
public final class SetCompRegionAction implements UndoableAction {

    private final TakeComping comping;
    private final CompRegion region;
    private List<CompRegion> previousRegions;

    /**
     * Creates a new set-comp-region action.
     *
     * @param comping the take comping instance to modify
     * @param region  the comp region to apply
     */
    public SetCompRegionAction(TakeComping comping, CompRegion region) {
        this.comping = Objects.requireNonNull(comping, "comping must not be null");
        this.region = Objects.requireNonNull(region, "region must not be null");
    }

    @Override
    public String description() {
        return "Set Comp Region";
    }

    @Override
    public void execute() {
        previousRegions = new ArrayList<>(comping.getCompRegions());
        comping.addCompRegion(region);
    }

    @Override
    public void undo() {
        if (previousRegions != null) {
            comping.setCompRegions(previousRegions);
        }
    }
}
