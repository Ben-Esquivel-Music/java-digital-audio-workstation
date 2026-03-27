package com.benesquivelmusic.daw.core.automation;

import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AddAutomationPointActionTest {

    @Test
    void shouldHaveDescriptiveName() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        AutomationPoint point = new AutomationPoint(0.0, 0.5);
        AddAutomationPointAction action = new AddAutomationPointAction(lane, point);

        assertThat(action.description()).isEqualTo("Add Automation Point");
    }

    @Test
    void shouldAddPointOnExecute() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        AutomationPoint point = new AutomationPoint(2.0, 0.8);

        AddAutomationPointAction action = new AddAutomationPointAction(lane, point);
        action.execute();

        assertThat(lane.getPoints()).containsExactly(point);
    }

    @Test
    void shouldRemovePointOnUndo() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        AutomationPoint point = new AutomationPoint(2.0, 0.8);

        AddAutomationPointAction action = new AddAutomationPointAction(lane, point);
        action.execute();
        action.undo();

        assertThat(lane.getPoints()).isEmpty();
    }

    @Test
    void shouldWorkWithUndoManager() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        AutomationPoint point = new AutomationPoint(2.0, 0.8);
        UndoManager undoManager = new UndoManager();

        undoManager.execute(new AddAutomationPointAction(lane, point));
        assertThat(lane.getPointCount()).isEqualTo(1);

        undoManager.undo();
        assertThat(lane.getPointCount()).isZero();

        undoManager.redo();
        assertThat(lane.getPointCount()).isEqualTo(1);
    }

    @Test
    void shouldRejectNullLane() {
        AutomationPoint point = new AutomationPoint(0.0, 0.5);
        assertThatThrownBy(() -> new AddAutomationPointAction(null, point))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullPoint() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        assertThatThrownBy(() -> new AddAutomationPointAction(lane, null))
                .isInstanceOf(NullPointerException.class);
    }
}
