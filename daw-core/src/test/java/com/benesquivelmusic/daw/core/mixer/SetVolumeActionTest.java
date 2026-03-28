package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SetVolumeActionTest {

    @Test
    void shouldHaveCorrectDescription() {
        MixerChannel channel = new MixerChannel("Drums");
        assertThat(new SetVolumeAction(channel, 0.5).description()).isEqualTo("Adjust Volume");
    }

    @Test
    void shouldSetVolumeOnExecute() {
        MixerChannel channel = new MixerChannel("Drums");

        SetVolumeAction action = new SetVolumeAction(channel, 0.5);
        action.execute();

        assertThat(channel.getVolume()).isEqualTo(0.5);
    }

    @Test
    void shouldRestoreVolumeOnUndo() {
        MixerChannel channel = new MixerChannel("Drums");
        channel.setVolume(0.8);

        SetVolumeAction action = new SetVolumeAction(channel, 0.3);
        action.execute();
        action.undo();

        assertThat(channel.getVolume()).isEqualTo(0.8);
    }

    @Test
    void shouldWorkWithUndoManager() {
        MixerChannel channel = new MixerChannel("Drums");
        UndoManager undoManager = new UndoManager();

        undoManager.execute(new SetVolumeAction(channel, 0.5));
        assertThat(channel.getVolume()).isEqualTo(0.5);

        undoManager.undo();
        assertThat(channel.getVolume()).isEqualTo(1.0);

        undoManager.redo();
        assertThat(channel.getVolume()).isEqualTo(0.5);
    }

    @Test
    void shouldRejectNullChannel() {
        assertThatThrownBy(() -> new SetVolumeAction(null, 0.5))
                .isInstanceOf(NullPointerException.class);
    }
}
