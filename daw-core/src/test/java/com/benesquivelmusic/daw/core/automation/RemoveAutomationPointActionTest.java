package com.benesquivelmusic.daw.core.automation;

import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoveAutomationPointActionTest {

    @Test
    void shouldHaveDescriptiveName() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        AutomationPoint point = new AutomationPoint(0.0, 0.5);
        RemoveAutomationPointAction action = new RemoveAutomationPointAction(lane, point);

        assertThat(action.description()).isEqualTo("Remove Automation Point");
    }

    @Test
    void shouldRemovePointOnExecute() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        AutomationPoint point = new AutomationPoint(2.0, 0.8);
        lane.addPoint(point);

        RemoveAutomationPointAction action = new RemoveAutomationPointAction(lane, point);
        action.execute();

        assertThat(lane.getPoints()).isEmpty();
    }

    @Test
    void shouldReAddPointOnUndo() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        AutomationPoint point = new AutomationPoint(2.0, 0.8);
        lane.addPoint(point);

        RemoveAutomationPointAction action = new RemoveAutomationPointAction(lane, point);
        action.execute();
        action.undo();

        assertThat(lane.getPoints()).containsExactly(point);
    }

    @Test
    void shouldWorkWithUndoManager() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        AutomationPoint point = new AutomationPoint(2.0, 0.8);
        lane.addPoint(point);

        UndoManager undoManager = new UndoManager();
        undoManager.execute(new RemoveAutomationPointAction(lane, point));
        assertThat(lane.getPointCount()).isZero();

        undoManager.undo();
        assertThat(lane.getPointCount()).isEqualTo(1);

        undoManager.redo();
        assertThat(lane.getPointCount()).isZero();
    }

    @Test
    void shouldRejectNullLane() {
        AutomationPoint point = new AutomationPoint(0.0, 0.5);
        assertThatThrownBy(() -> new RemoveAutomationPointAction(null, point))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullPoint() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        assertThatThrownBy(() -> new RemoveAutomationPointAction(lane, null))
                .isInstanceOf(NullPointerException.class);
    }
}
