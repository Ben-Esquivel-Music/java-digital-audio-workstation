package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReorderEffectActionTest {

    @Test
    void shouldHaveCorrectDescription() {
        MixerChannel channel = new MixerChannel("Drums");
        assertThat(new ReorderEffectAction(channel, 0, 1).description())
                .isEqualTo("Reorder Effect");
    }

    @Test
    void shouldReorderEffectsOnExecute() {
        MixerChannel channel = new MixerChannel("Drums");
        InsertSlot comp = new InsertSlot("Compressor", createDummyProcessor());
        InsertSlot eq = new InsertSlot("EQ", createDummyProcessor());
        channel.addInsert(comp);
        channel.addInsert(eq);

        ReorderEffectAction action = new ReorderEffectAction(channel, 0, 1);
        action.execute();

        assertThat(channel.getInsertSlot(0).getName()).isEqualTo("EQ");
        assertThat(channel.getInsertSlot(1).getName()).isEqualTo("Compressor");
    }

    @Test
    void shouldRestoreOrderOnUndo() {
        MixerChannel channel = new MixerChannel("Drums");
        InsertSlot comp = new InsertSlot("Compressor", createDummyProcessor());
        InsertSlot eq = new InsertSlot("EQ", createDummyProcessor());
        channel.addInsert(comp);
        channel.addInsert(eq);

        ReorderEffectAction action = new ReorderEffectAction(channel, 0, 1);
        action.execute();
        action.undo();

        assertThat(channel.getInsertSlot(0).getName()).isEqualTo("Compressor");
        assertThat(channel.getInsertSlot(1).getName()).isEqualTo("EQ");
    }

    @Test
    void shouldWorkWithUndoManager() {
        MixerChannel channel = new MixerChannel("Drums");
        InsertSlot comp = new InsertSlot("Compressor", createDummyProcessor());
        InsertSlot eq = new InsertSlot("EQ", createDummyProcessor());
        channel.addInsert(comp);
        channel.addInsert(eq);
        UndoManager undoManager = new UndoManager();

        undoManager.execute(new ReorderEffectAction(channel, 0, 1));
        assertThat(channel.getInsertSlot(0).getName()).isEqualTo("EQ");

        undoManager.undo();
        assertThat(channel.getInsertSlot(0).getName()).isEqualTo("Compressor");

        undoManager.redo();
        assertThat(channel.getInsertSlot(0).getName()).isEqualTo("EQ");
    }

    @Test
    void shouldRejectNullChannel() {
        assertThatThrownBy(() -> new ReorderEffectAction(null, 0, 1))
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
