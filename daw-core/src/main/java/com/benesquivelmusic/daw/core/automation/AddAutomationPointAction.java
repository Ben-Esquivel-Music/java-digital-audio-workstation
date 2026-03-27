package com.benesquivelmusic.daw.core.automation;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that adds an automation point to a lane.
 */
public final class AddAutomationPointAction implements UndoableAction {

    private final AutomationLane lane;
    private final AutomationPoint point;

    /**
     * Creates a new add-automation-point action.
     *
     * @param lane  the lane to add the point to
     * @param point the point to add
     */
    public AddAutomationPointAction(AutomationLane lane, AutomationPoint point) {
        this.lane = Objects.requireNonNull(lane, "lane must not be null");
        this.point = Objects.requireNonNull(point, "point must not be null");
    }

    @Override
    public String description() {
        return "Add Automation Point";
    }

    @Override
    public void execute() {
        lane.addPoint(point);
    }

    @Override
    public void undo() {
        lane.removePoint(point);
    }
}
