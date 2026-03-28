package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SetPanActionTest {

    @Test
    void shouldHaveCorrectDescription() {
        MixerChannel channel = new MixerChannel("Drums");
        assertThat(new SetPanAction(channel, -0.5).description()).isEqualTo("Adjust Pan");
    }

    @Test
    void shouldSetPanOnExecute() {
        MixerChannel channel = new MixerChannel("Drums");

        SetPanAction action = new SetPanAction(channel, -0.5);
        action.execute();

        assertThat(channel.getPan()).isEqualTo(-0.5);
    }

    @Test
    void shouldRestorePanOnUndo() {
        MixerChannel channel = new MixerChannel("Drums");
        channel.setPan(0.3);

        SetPanAction action = new SetPanAction(channel, -0.7);
        action.execute();
        action.undo();

        assertThat(channel.getPan()).isEqualTo(0.3);
    }

    @Test
    void shouldWorkWithUndoManager() {
        MixerChannel channel = new MixerChannel("Drums");
        UndoManager undoManager = new UndoManager();

        undoManager.execute(new SetPanAction(channel, 0.5));
        assertThat(channel.getPan()).isEqualTo(0.5);

        undoManager.undo();
        assertThat(channel.getPan()).isEqualTo(0.0);

        undoManager.redo();
        assertThat(channel.getPan()).isEqualTo(0.5);
    }

    @Test
    void shouldRejectNullChannel() {
        assertThatThrownBy(() -> new SetPanAction(null, 0.5))
                .isInstanceOf(NullPointerException.class);
    }
}
