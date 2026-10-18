package com.benesquivelmusic.daw.core.comping;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Undoable action that adds (or replaces) a single {@link CompRegion} on a
 * {@link Track}'s {@link TakeComping}, applying the standard "swipe comping"
 * behavior: the new region's beat range deselects any overlapping regions on
 * every other take of the same track.
 *
 * <p>This is the action invoked by the comp tool ({@code EditTool.COMP})
 * when the user click-drags across a take's waveform. The swipe is audible
 * at once (story 328, book §5.6): the action composes a
 * {@link CompileCompAction}, so the track's main lane plays the new
 * selection as soon as the swipe ends, and undo restores the previous
 * selection and recompiles it from the live comping state (story 328
 * review: not from a snapshot of clips, so an audition in between leaves
 * nothing orphaned). The lanes are never touched.</p>
 */
public final class SetCompRegionAction implements UndoableAction {

    private final TakeComping comping;
    private final CompRegion region;
    private final CompileCompAction recompile;
    private List<CompRegion> previousRegions;

    /**
     * Creates a new set-comp-region action.
     *
     * @param track  the track whose take comping to modify
     * @param region the comp region to apply
     */
    public SetCompRegionAction(Track track, CompRegion region) {
        Objects.requireNonNull(track, "track must not be null");
        this.comping = track.getTakeComping();
        this.region = Objects.requireNonNull(region, "region must not be null");
        this.recompile = new CompileCompAction(track);
    }

    @Override
    public String description() {
        return "Set Comp Region";
    }

    @Override
    public void execute() {
        previousRegions = new ArrayList<>(comping.getCompRegions());
        comping.addCompRegion(region);
        recompile.execute();
    }

    @Override
    public void undo() {
        if (previousRegions != null) {
            // The selection first: the recompile reads the live state.
            comping.setCompRegions(previousRegions);
            recompile.undo();
        }
    }
}
