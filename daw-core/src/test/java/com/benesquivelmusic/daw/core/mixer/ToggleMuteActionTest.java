package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToggleMuteActionTest {

    @Test
    void shouldHaveCorrectDescription() {
        MixerChannel channel = new MixerChannel("Drums");
        assertThat(new ToggleMuteAction(channel, true).description()).isEqualTo("Toggle Mute");
    }

    @Test
    void shouldSetMuteOnExecute() {
        MixerChannel channel = new MixerChannel("Drums");

        ToggleMuteAction action = new ToggleMuteAction(channel, true);
        action.execute();

        assertThat(channel.isMuted()).isTrue();
    }

    @Test
    void shouldRestoreMuteOnUndo() {
        MixerChannel channel = new MixerChannel("Drums");

        ToggleMuteAction action = new ToggleMuteAction(channel, true);
        action.execute();
        action.undo();

        assertThat(channel.isMuted()).isFalse();
    }

    @Test
    void shouldWorkWithUndoManager() {
        MixerChannel channel = new MixerChannel("Drums");
        UndoManager undoManager = new UndoManager();

        undoManager.execute(new ToggleMuteAction(channel, true));
        assertThat(channel.isMuted()).isTrue();

        undoManager.undo();
        assertThat(channel.isMuted()).isFalse();

        undoManager.redo();
        assertThat(channel.isMuted()).isTrue();
    }

    @Test
    void shouldRejectNullChannel() {
        assertThatThrownBy(() -> new ToggleMuteAction(null, true))
                .isInstanceOf(NullPointerException.class);
    }
}
