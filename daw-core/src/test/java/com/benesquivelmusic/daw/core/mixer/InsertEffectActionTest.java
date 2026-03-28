package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InsertEffectActionTest {

    @Test
    void shouldHaveCorrectDescription() {
        MixerChannel channel = new MixerChannel("Drums");
        InsertSlot slot = new InsertSlot("Compressor", createDummyProcessor());

        assertThat(new InsertEffectAction(channel, 0, slot).description())
                .isEqualTo("Insert Effect");
    }

    @Test
    void shouldInsertEffectOnExecute() {
        MixerChannel channel = new MixerChannel("Drums");
        InsertSlot slot = new InsertSlot("Compressor", createDummyProcessor());

        InsertEffectAction action = new InsertEffectAction(channel, 0, slot);
        action.execute();

        assertThat(channel.getInsertCount()).isEqualTo(1);
        assertThat(channel.getInsertSlot(0).getName()).isEqualTo("Compressor");
    }

    @Test
    void shouldRemoveEffectOnUndo() {
        MixerChannel channel = new MixerChannel("Drums");
        InsertSlot slot = new InsertSlot("Compressor", createDummyProcessor());

        InsertEffectAction action = new InsertEffectAction(channel, 0, slot);
        action.execute();
        action.undo();

        assertThat(channel.getInsertCount()).isZero();
    }

    @Test
    void shouldWorkWithUndoManager() {
        MixerChannel channel = new MixerChannel("Drums");
        InsertSlot slot = new InsertSlot("Compressor", createDummyProcessor());
        UndoManager undoManager = new UndoManager();

        undoManager.execute(new InsertEffectAction(channel, 0, slot));
        assertThat(channel.getInsertCount()).isEqualTo(1);

        undoManager.undo();
        assertThat(channel.getInsertCount()).isZero();

        undoManager.redo();
        assertThat(channel.getInsertCount()).isEqualTo(1);
    }

    @Test
    void shouldRejectNullChannel() {
        InsertSlot slot = new InsertSlot("Comp", createDummyProcessor());
        assertThatThrownBy(() -> new InsertEffectAction(null, 0, slot))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullSlot() {
        MixerChannel channel = new MixerChannel("Drums");
        assertThatThrownBy(() -> new InsertEffectAction(channel, 0, null))
                .isInstanceOf(NullPointerException.class);
    }

    private static AudioProcessor createDummyProcessor() {
        return new AudioProcessor() {
            @Override public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) { }
            @Override public void reset() { }
            @Override public int getInputChannelCount() { return 2; }
            @Override public int getOutputChannelCount() { return 2; }
        };
    }
}
