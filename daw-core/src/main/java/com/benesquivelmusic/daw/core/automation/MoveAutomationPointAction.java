package com.benesquivelmusic.daw.core.automation;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that moves an automation point to a new time and/or value.
 */
public final class MoveAutomationPointAction implements UndoableAction {

    private final AutomationLane lane;
    private final AutomationPoint point;
    private final double oldTime;
    private final double oldValue;
    private final double newTime;
    private final double newValue;

    /**
     * Creates a new move-automation-point action.
     *
     * @param lane     the lane containing the point
     * @param point    the point to move
     * @param newTime  the new time position in beats
     * @param newValue the new parameter value
     */
    public MoveAutomationPointAction(AutomationLane lane, AutomationPoint point,
                                     double newTime, double newValue) {
        this.lane = Objects.requireNonNull(lane, "lane must not be null");
        this.point = Objects.requireNonNull(point, "point must not be null");
        this.oldTime = point.getTimeInBeats();
        this.oldValue = point.getValue();
        this.newTime = newTime;
        this.newValue = newValue;
    }

    @Override
    public String description() {
        return "Move Automation Point";
    }

    @Override
    public void execute() {
        point.setTimeInBeats(newTime);
        point.setValue(newValue);
        lane.sortPoints();
    }

    @Override
    public void undo() {
        point.setTimeInBeats(oldTime);
        point.setValue(oldValue);
        lane.sortPoints();
    }
}
