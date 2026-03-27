package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AddReturnBusActionTest {

    @Test
    void shouldAddReturnBus() {
        Mixer mixer = new Mixer();
        int initialCount = mixer.getReturnBusCount();

        AddReturnBusAction action = new AddReturnBusAction(mixer, "Delay Return");
        action.execute();

        assertThat(mixer.getReturnBusCount()).isEqualTo(initialCount + 1);
        assertThat(action.getReturnBus()).isNotNull();
        assertThat(action.getReturnBus().getName()).isEqualTo("Delay Return");
    }

    @Test
    void shouldUndoAddReturnBus() {
        Mixer mixer = new Mixer();
        int initialCount = mixer.getReturnBusCount();

        AddReturnBusAction action = new AddReturnBusAction(mixer, "Delay Return");
        action.execute();
        assertThat(mixer.getReturnBusCount()).isEqualTo(initialCount + 1);

        action.undo();
        assertThat(mixer.getReturnBusCount()).isEqualTo(initialCount);
    }

    @Test
    void shouldRedoAddReturnBus() {
        Mixer mixer = new Mixer();
        int initialCount = mixer.getReturnBusCount();

        AddReturnBusAction action = new AddReturnBusAction(mixer, "Delay Return");
        action.execute();
        MixerChannel returnBus = action.getReturnBus();

        action.undo();
        assertThat(mixer.getReturnBusCount()).isEqualTo(initialCount);

        action.execute();
        assertThat(mixer.getReturnBusCount()).isEqualTo(initialCount + 1);
        assertThat(action.getReturnBus()).isSameAs(returnBus);
    }

    @Test
    void shouldReturnNullBeforeExecute() {
        Mixer mixer = new Mixer();
        AddReturnBusAction action = new AddReturnBusAction(mixer, "Bus");

        assertThat(action.getReturnBus()).isNull();
    }

    @Test
    void shouldHaveCorrectDescription() {
        Mixer mixer = new Mixer();
        AddReturnBusAction action = new AddReturnBusAction(mixer, "Bus");

        assertThat(action.description()).isEqualTo("Add Return Bus");
    }

    @Test
    void shouldWorkWithUndoManager() {
        Mixer mixer = new Mixer();
        UndoManager undoManager = new UndoManager();
        int initialCount = mixer.getReturnBusCount();

        AddReturnBusAction action = new AddReturnBusAction(mixer, "Chorus Return");
        undoManager.execute(action);
        assertThat(mixer.getReturnBusCount()).isEqualTo(initialCount + 1);

        undoManager.undo();
        assertThat(mixer.getReturnBusCount()).isEqualTo(initialCount);

        undoManager.redo();
        assertThat(mixer.getReturnBusCount()).isEqualTo(initialCount + 1);
    }
}
