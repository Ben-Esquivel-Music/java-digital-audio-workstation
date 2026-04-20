package com.benesquivelmusic.daw.core.project;

import com.benesquivelmusic.daw.core.recording.InputMonitoringMode;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that changes a track's input monitoring mode.
 *
 * <p>Executing this action sets the track's
 * {@link Track#setInputMonitoring(InputMonitoringMode) input monitoring
 * mode} to the new value. Undoing it restores the previous mode. This
 * makes monitoring-mode changes auditable in the
 * {@code UndoHistoryPanel} alongside other track edits (rename,
 * reorder, etc.).</p>
 */
public final class SetMonitoringModeAction implements UndoableAction {

    private final Track track;
    private final InputMonitoringMode newMode;
    private InputMonitoringMode previousMode;

    /**
     * Creates a new set-monitoring-mode action.
     *
     * @param track   the track whose monitoring mode is changing
     * @param newMode the new monitoring mode (must not be {@code null})
     */
    public SetMonitoringModeAction(Track track, InputMonitoringMode newMode) {
        this.track = Objects.requireNonNull(track, "track must not be null");
        this.newMode = Objects.requireNonNull(newMode, "newMode must not be null");
    }

    @Override
    public String description() {
        return "Set Monitoring Mode";
    }

    @Override
    public void execute() {
        previousMode = track.getInputMonitoring();
        track.setInputMonitoring(newMode);
    }

    @Override
    public void undo() {
        if (previousMode != null) {
            track.setInputMonitoring(previousMode);
        }
    }
}
