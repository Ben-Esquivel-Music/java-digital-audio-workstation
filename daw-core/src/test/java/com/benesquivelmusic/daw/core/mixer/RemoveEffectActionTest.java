package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoveEffectActionTest {

    @Test
    void shouldHaveCorrectDescription() {
        MixerChannel channel = new MixerChannel("Drums");
        channel.addInsert(new InsertSlot("Compressor", createDummyProcessor()));

        assertThat(new RemoveEffectAction(channel, 0).description())
                .isEqualTo("Remove Effect");
    }

    @Test
    void shouldRemoveEffectOnExecute() {
        MixerChannel channel = new MixerChannel("Drums");
        channel.addInsert(new InsertSlot("Compressor", createDummyProcessor()));

        RemoveEffectAction action = new RemoveEffectAction(channel, 0);
        action.execute();

        assertThat(channel.getInsertCount()).isZero();
    }

    @Test
    void shouldReInsertEffectOnUndo() {
        MixerChannel channel = new MixerChannel("Drums");
        InsertSlot slot = new InsertSlot("Compressor", createDummyProcessor());
        channel.addInsert(slot);

        RemoveEffectAction action = new RemoveEffectAction(channel, 0);
        action.execute();
        action.undo();

        assertThat(channel.getInsertCount()).isEqualTo(1);
        assertThat(channel.getInsertSlot(0).getName()).isEqualTo("Compressor");
    }

    @Test
    void shouldWorkWithUndoManager() {
        MixerChannel channel = new MixerChannel("Drums");
        channel.addInsert(new InsertSlot("Compressor", createDummyProcessor()));
        UndoManager undoManager = new UndoManager();

        undoManager.execute(new RemoveEffectAction(channel, 0));
        assertThat(channel.getInsertCount()).isZero();

        undoManager.undo();
        assertThat(channel.getInsertCount()).isEqualTo(1);

        undoManager.redo();
        assertThat(channel.getInsertCount()).isZero();
    }

    @Test
    void shouldRejectNullChannel() {
        assertThatThrownBy(() -> new RemoveEffectAction(null, 0))
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
