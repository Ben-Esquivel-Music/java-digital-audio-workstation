package com.benesquivelmusic.daw.core.automation;

import com.benesquivelmusic.daw.core.undo.UndoManager;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class MoveAutomationPointActionTest {

    @Test
    void shouldHaveDescriptiveName() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        AutomationPoint point = new AutomationPoint(0.0, 0.5);
        lane.addPoint(point);
        MoveAutomationPointAction action = new MoveAutomationPointAction(lane, point, 4.0, 0.8);

        assertThat(action.description()).isEqualTo("Move Automation Point");
    }

    @Test
    void shouldMovePointOnExecute() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        AutomationPoint point = new AutomationPoint(0.0, 0.5);
        lane.addPoint(point);

        MoveAutomationPointAction action = new MoveAutomationPointAction(lane, point, 4.0, 0.8);
        action.execute();

        assertThat(point.getTimeInBeats()).isCloseTo(4.0, within(0.001));
        assertThat(point.getValue()).isCloseTo(0.8, within(0.001));
    }

    @Test
    void shouldRestorePositionOnUndo() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        AutomationPoint point = new AutomationPoint(1.0, 0.5);
        lane.addPoint(point);

        MoveAutomationPointAction action = new MoveAutomationPointAction(lane, point, 4.0, 0.8);
        action.execute();
        action.undo();

        assertThat(point.getTimeInBeats()).isCloseTo(1.0, within(0.001));
        assertThat(point.getValue()).isCloseTo(0.5, within(0.001));
    }

    @Test
    void shouldMaintainSortOrderAfterMove() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        AutomationPoint p1 = new AutomationPoint(0.0, 1.0);
        AutomationPoint p2 = new AutomationPoint(2.0, 0.5);
        AutomationPoint p3 = new AutomationPoint(4.0, 0.0);
        lane.addPoint(p1);
        lane.addPoint(p2);
        lane.addPoint(p3);

        // Move p1 to after p3
        MoveAutomationPointAction action = new MoveAutomationPointAction(lane, p1, 6.0, 0.9);
        action.execute();

        assertThat(lane.getPoints()).containsExactly(p2, p3, p1);
    }

    @Test
    void shouldRestoreSortOrderOnUndo() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        AutomationPoint p1 = new AutomationPoint(0.0, 1.0);
        AutomationPoint p2 = new AutomationPoint(2.0, 0.5);
        lane.addPoint(p1);
        lane.addPoint(p2);

        MoveAutomationPointAction action = new MoveAutomationPointAction(lane, p1, 6.0, 0.9);
        action.execute();
        action.undo();

        assertThat(lane.getPoints()).containsExactly(p1, p2);
    }

    @Test
    void shouldWorkWithUndoManager() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        AutomationPoint point = new AutomationPoint(1.0, 0.5);
        lane.addPoint(point);

        UndoManager undoManager = new UndoManager();
        undoManager.execute(new MoveAutomationPointAction(lane, point, 4.0, 0.8));

        assertThat(point.getTimeInBeats()).isCloseTo(4.0, within(0.001));
        assertThat(point.getValue()).isCloseTo(0.8, within(0.001));

        undoManager.undo();
        assertThat(point.getTimeInBeats()).isCloseTo(1.0, within(0.001));
        assertThat(point.getValue()).isCloseTo(0.5, within(0.001));

        undoManager.redo();
        assertThat(point.getTimeInBeats()).isCloseTo(4.0, within(0.001));
        assertThat(point.getValue()).isCloseTo(0.8, within(0.001));
    }

    @Test
    void shouldRejectNullLane() {
        AutomationPoint point = new AutomationPoint(0.0, 0.5);
        assertThatThrownBy(() -> new MoveAutomationPointAction(null, point, 1.0, 0.5))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullPoint() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        assertThatThrownBy(() -> new MoveAutomationPointAction(lane, null, 1.0, 0.5))
                .isInstanceOf(NullPointerException.class);
    }
}
