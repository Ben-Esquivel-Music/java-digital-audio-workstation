package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.mastering.CrossfadeCurve;

import java.util.Objects;

/**
 * An undoable action that changes the curve type of a {@link ClipCrossfade}.
 *
 * <p>Executing this action applies a new crossfade curve type. Undoing it
 * restores the previous curve type.</p>
 */
public final class EditCrossfadeAction implements UndoableAction {

    private final ClipCrossfade crossfade;
    private final CrossfadeCurve newCurveType;
    private CrossfadeCurve originalCurveType;

    /**
     * Creates a new edit-crossfade action.
     *
     * @param crossfade    the crossfade to modify
     * @param newCurveType the new crossfade curve type
     */
    public EditCrossfadeAction(ClipCrossfade crossfade, CrossfadeCurve newCurveType) {
        this.crossfade = Objects.requireNonNull(crossfade, "crossfade must not be null");
        this.newCurveType = Objects.requireNonNull(newCurveType, "newCurveType must not be null");
    }

    @Override
    public String description() {
        return "Edit Crossfade";
    }

    @Override
    public void execute() {
        originalCurveType = crossfade.getCurveType();
        crossfade.setCurveType(newCurveType);
    }

    @Override
    public void undo() {
        crossfade.setCurveType(originalCurveType);
    }
}
