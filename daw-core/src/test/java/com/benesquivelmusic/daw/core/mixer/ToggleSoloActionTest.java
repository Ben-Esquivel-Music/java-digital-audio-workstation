package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToggleSoloActionTest {

    @Test
    void shouldHaveCorrectDescription() {
        MixerChannel channel = new MixerChannel("Drums");
        assertThat(new ToggleSoloAction(channel, true).description()).isEqualTo("Toggle Solo");
    }

    @Test
    void shouldSetSoloOnExecute() {
        MixerChannel channel = new MixerChannel("Drums");

        ToggleSoloAction action = new ToggleSoloAction(channel, true);
        action.execute();

        assertThat(channel.isSolo()).isTrue();
    }

    @Test
    void shouldRestoreSoloOnUndo() {
        MixerChannel channel = new MixerChannel("Drums");

        ToggleSoloAction action = new ToggleSoloAction(channel, true);
        action.execute();
        action.undo();

        assertThat(channel.isSolo()).isFalse();
    }

    @Test
    void shouldWorkWithUndoManager() {
        MixerChannel channel = new MixerChannel("Drums");
        UndoManager undoManager = new UndoManager();

        undoManager.execute(new ToggleSoloAction(channel, true));
        assertThat(channel.isSolo()).isTrue();

        undoManager.undo();
        assertThat(channel.isSolo()).isFalse();

        undoManager.redo();
        assertThat(channel.isSolo()).isTrue();
    }

    @Test
    void shouldRejectNullChannel() {
        assertThatThrownBy(() -> new ToggleSoloAction(null, true))
                .isInstanceOf(NullPointerException.class);
    }
}
