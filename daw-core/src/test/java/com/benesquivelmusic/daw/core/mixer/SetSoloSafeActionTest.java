package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SetSoloSafeActionTest {

    @Test
    void shouldHaveCorrectDescription() {
        MixerChannel channel = new MixerChannel("Reverb Return");
        assertThat(new SetSoloSafeAction(channel, true).description())
                .isEqualTo("Toggle Solo Safe");
    }

    @Test
    void shouldSetSoloSafeOnExecute() {
        MixerChannel channel = new MixerChannel("Reverb Return");

        SetSoloSafeAction action = new SetSoloSafeAction(channel, true);
        action.execute();

        assertThat(channel.isSoloSafe()).isTrue();
    }

    @Test
    void shouldRestoreSoloSafeOnUndo() {
        MixerChannel channel = new MixerChannel("Reverb Return");
        channel.setSoloSafe(true);

        SetSoloSafeAction action = new SetSoloSafeAction(channel, false);
        action.execute();
        action.undo();

        assertThat(channel.isSoloSafe()).isTrue();
    }

    @Test
    void shouldWorkWithUndoManager() {
        MixerChannel channel = new MixerChannel("Reverb Return");
        UndoManager undoManager = new UndoManager();

        undoManager.execute(new SetSoloSafeAction(channel, true));
        assertThat(channel.isSoloSafe()).isTrue();

        undoManager.undo();
        assertThat(channel.isSoloSafe()).isFalse();

        undoManager.redo();
        assertThat(channel.isSoloSafe()).isTrue();
    }

    @Test
    void shouldRejectNullChannel() {
        assertThatThrownBy(() -> new SetSoloSafeAction(null, true))
                .isInstanceOf(NullPointerException.class);
    }
}
