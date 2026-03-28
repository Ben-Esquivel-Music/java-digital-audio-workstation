package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.plugin.clap.ClapPluginHost;
import com.benesquivelmusic.daw.core.plugin.clap.ClapPluginScanner;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoveClapEffectActionTest {

    @Test
    void shouldRemoveClapEffectOnExecute() {
        MixerChannel channel = new MixerChannel("Guitar");
        InsertSlot slot = createClapSlot("ClapReverb");
        channel.addInsert(slot);

        ClapPluginManager manager = new ClapPluginManager(new ClapPluginScanner(List.of()));

        RemoveClapEffectAction action = new RemoveClapEffectAction(channel, 0, manager);
        action.execute();

        assertThat(channel.getInsertCount()).isZero();
        assertThat(action.getRemovedSlot()).isSameAs(slot);
    }

    @Test
    void shouldReinsertOnUndo() {
        MixerChannel channel = new MixerChannel("Guitar");
        InsertSlot slot = createClapSlot("ClapReverb");
        channel.addInsert(slot);

        ClapPluginManager manager = new ClapPluginManager(new ClapPluginScanner(List.of()));

        RemoveClapEffectAction action = new RemoveClapEffectAction(channel, 0, manager);
        action.execute();
        action.undo();

        assertThat(channel.getInsertCount()).isEqualTo(1);
        assertThat(channel.getInsertSlot(0)).isSameAs(slot);
    }

    @Test
    void shouldHaveCorrectDescription() {
        MixerChannel channel = new MixerChannel("Guitar");
        ClapPluginManager manager = new ClapPluginManager(new ClapPluginScanner(List.of()));

        RemoveClapEffectAction action = new RemoveClapEffectAction(channel, 0, manager);

        assertThat(action.description()).isEqualTo("Remove CLAP Effect");
    }

    @Test
    void shouldReturnNullRemovedSlotBeforeExecute() {
        MixerChannel channel = new MixerChannel("Guitar");
        ClapPluginManager manager = new ClapPluginManager(new ClapPluginScanner(List.of()));

        RemoveClapEffectAction action = new RemoveClapEffectAction(channel, 0, manager);

        assertThat(action.getRemovedSlot()).isNull();
    }

    @Test
    void shouldNotThrowOnUndoBeforeExecute() {
        MixerChannel channel = new MixerChannel("Guitar");
        ClapPluginManager manager = new ClapPluginManager(new ClapPluginScanner(List.of()));

        RemoveClapEffectAction action = new RemoveClapEffectAction(channel, 0, manager);

        // undo before execute should be no-op
        action.undo();
        assertThat(channel.getInsertCount()).isZero();
    }

    @Test
    void shouldDisposePluginWithoutError() {
        MixerChannel channel = new MixerChannel("Guitar");
        InsertSlot slot = createClapSlot("ClapReverb");
        channel.addInsert(slot);

        ClapPluginManager manager = new ClapPluginManager(new ClapPluginScanner(List.of()));

        RemoveClapEffectAction action = new RemoveClapEffectAction(channel, 0, manager);
        action.execute();

        // Should not throw
        action.disposePlugin();
    }

    @Test
    void shouldUpdateEffectsChainOnExecuteAndUndo() {
        MixerChannel channel = new MixerChannel("Guitar");
        InsertSlot slot = createClapSlot("ClapReverb");
        channel.addInsert(slot);

        ClapPluginManager manager = new ClapPluginManager(new ClapPluginScanner(List.of()));

        RemoveClapEffectAction action = new RemoveClapEffectAction(channel, 0, manager);

        action.execute();
        assertThat(channel.getEffectsChain().isEmpty()).isTrue();

        action.undo();
        assertThat(channel.getEffectsChain().size()).isEqualTo(1);
    }

    @Test
    void shouldRejectNullChannel() {
        ClapPluginManager manager = new ClapPluginManager(new ClapPluginScanner(List.of()));
        assertThatThrownBy(() -> new RemoveClapEffectAction(null, 0, manager))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("channel");
    }

    @Test
    void shouldRejectNullPluginManager() {
        MixerChannel channel = new MixerChannel("Guitar");
        assertThatThrownBy(() -> new RemoveClapEffectAction(channel, 0, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("pluginManager");
    }

    @Test
    void shouldRemoveAndReinsertAtCorrectIndex() {
        MixerChannel channel = new MixerChannel("Guitar");
        InsertSlot slot0 = new InsertSlot("EQ", new StubProcessor());
        InsertSlot slot1 = createClapSlot("ClapDelay");
        InsertSlot slot2 = new InsertSlot("Comp", new StubProcessor());
        channel.addInsert(slot0);
        channel.addInsert(slot1);
        channel.addInsert(slot2);

        ClapPluginManager manager = new ClapPluginManager(new ClapPluginScanner(List.of()));

        RemoveClapEffectAction action = new RemoveClapEffectAction(channel, 1, manager);
        action.execute();

        assertThat(channel.getInsertCount()).isEqualTo(2);
        assertThat(channel.getInsertSlot(0)).isSameAs(slot0);
        assertThat(channel.getInsertSlot(1)).isSameAs(slot2);

        action.undo();

        assertThat(channel.getInsertCount()).isEqualTo(3);
        assertThat(channel.getInsertSlot(0)).isSameAs(slot0);
        assertThat(channel.getInsertSlot(1)).isSameAs(slot1);
        assertThat(channel.getInsertSlot(2)).isSameAs(slot2);
    }

    private static InsertSlot createClapSlot(String name) {
        ClapPluginHost host = new ClapPluginHost(Path.of("/plugins/test.clap"));
        ClapInsertEffect effect = new ClapInsertEffect(host);
        return new InsertSlot(name, effect);
    }

    private static final class StubProcessor implements AudioProcessor {
        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            for (int ch = 0; ch < inputBuffer.length; ch++) {
                System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
            }
        }

        @Override
        public void reset() {
        }

        @Override
        public int getInputChannelCount() {
            return 2;
        }

        @Override
        public int getOutputChannelCount() {
            return 2;
        }
    }
}
