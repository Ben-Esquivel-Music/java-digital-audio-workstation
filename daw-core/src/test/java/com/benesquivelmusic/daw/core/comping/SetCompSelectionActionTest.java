package com.benesquivelmusic.daw.core.comping;

import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SetCompSelectionActionTest {

    private TakeComping comping;

    @BeforeEach
    void setUp() {
        comping = new TakeComping();
        comping.addTakeLane(new TakeLane("Take 1"));
        comping.addTakeLane(new TakeLane("Take 2"));
    }

    @Test
    void shouldHaveCorrectDescription() {
        SetCompSelectionAction action = new SetCompSelectionAction(comping, List.of());
        assertThat(action.description()).isEqualTo("Set Comp Selection");
    }

    @Test
    void shouldSetCompRegionsOnExecute() {
        List<CompRegion> newRegions = List.of(
                new CompRegion(0, 0.0, 8.0),
                new CompRegion(1, 8.0, 8.0));

        SetCompSelectionAction action = new SetCompSelectionAction(comping, newRegions);
        action.execute();

        assertThat(comping.getCompRegions()).isEqualTo(newRegions);
    }

    @Test
    void shouldRestorePreviousRegionsOnUndo() {
        // Set initial selection
        List<CompRegion> initial = List.of(new CompRegion(0, 0.0, 16.0));
        comping.setCompRegions(initial);

        // Create action to change selection
        List<CompRegion> newRegions = List.of(
                new CompRegion(0, 0.0, 8.0),
                new CompRegion(1, 8.0, 8.0));
        SetCompSelectionAction action = new SetCompSelectionAction(comping, newRegions);
        action.execute();

        assertThat(comping.getCompRegions()).isEqualTo(newRegions);

        action.undo();

        assertThat(comping.getCompRegions()).isEqualTo(initial);
    }

    @Test
    void shouldWorkWithUndoManager() {
        List<CompRegion> initial = List.of(new CompRegion(0, 0.0, 16.0));
        comping.setCompRegions(initial);

        UndoManager undoManager = new UndoManager();
        List<CompRegion> newRegions = List.of(
                new CompRegion(0, 0.0, 8.0),
                new CompRegion(1, 8.0, 8.0));

        undoManager.execute(new SetCompSelectionAction(comping, newRegions));
        assertThat(comping.getCompRegions()).isEqualTo(newRegions);

        undoManager.undo();
        assertThat(comping.getCompRegions()).isEqualTo(initial);

        undoManager.redo();
        assertThat(comping.getCompRegions()).isEqualTo(newRegions);
    }

    @Test
    void shouldRejectNullTakeComping() {
        assertThatThrownBy(() -> new SetCompSelectionAction(null, List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullRegions() {
        assertThatThrownBy(() -> new SetCompSelectionAction(comping, null))
                .isInstanceOf(NullPointerException.class);
    }
}
