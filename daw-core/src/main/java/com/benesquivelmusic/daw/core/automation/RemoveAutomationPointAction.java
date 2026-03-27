package com.benesquivelmusic.daw.core.automation;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that removes an automation point from a lane.
 */
public final class RemoveAutomationPointAction implements UndoableAction {

    private final AutomationLane lane;
    private final AutomationPoint point;

    /**
     * Creates a new remove-automation-point action.
     *
     * @param lane  the lane to remove the point from
     * @param point the point to remove
     */
    public RemoveAutomationPointAction(AutomationLane lane, AutomationPoint point) {
        this.lane = Objects.requireNonNull(lane, "lane must not be null");
        this.point = Objects.requireNonNull(point, "point must not be null");
    }

    @Override
    public String description() {
        return "Remove Automation Point";
    }

    @Override
    public void execute() {
        lane.removePoint(point);
    }

    @Override
    public void undo() {
        lane.addPoint(point);
    }
}
