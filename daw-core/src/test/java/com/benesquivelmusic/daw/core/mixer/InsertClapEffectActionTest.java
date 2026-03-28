package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.plugin.clap.ClapPluginHost;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InsertClapEffectActionTest {

    @Test
    void shouldInsertClapEffectOnExecute() {
        MixerChannel channel = new MixerChannel("Guitar");
        InsertSlot slot = createClapSlot("TestPlugin");

        InsertClapEffectAction action = new InsertClapEffectAction(channel, 0, slot);
        action.execute();

        assertThat(channel.getInsertCount()).isEqualTo(1);
        assertThat(channel.getInsertSlot(0)).isSameAs(slot);
    }

    @Test
    void shouldRemoveClapEffectOnUndo() {
        MixerChannel channel = new MixerChannel("Guitar");
        InsertSlot slot = createClapSlot("TestPlugin");

        InsertClapEffectAction action = new InsertClapEffectAction(channel, 0, slot);
        action.execute();
        action.undo();

        assertThat(channel.getInsertCount()).isZero();
    }

    @Test
    void shouldInsertAtSpecificIndex() {
        MixerChannel channel = new MixerChannel("Guitar");
        InsertSlot builtIn = new InsertSlot("EQ", new StubProcessor());
        channel.addInsert(builtIn);

        InsertSlot clapSlot = createClapSlot("ClapReverb");
        InsertClapEffectAction action = new InsertClapEffectAction(channel, 0, clapSlot);
        action.execute();

        assertThat(channel.getInsertCount()).isEqualTo(2);
        assertThat(channel.getInsertSlot(0)).isSameAs(clapSlot);
        assertThat(channel.getInsertSlot(1)).isSameAs(builtIn);
    }

    @Test
    void shouldHaveCorrectDescription() {
        MixerChannel channel = new MixerChannel("Guitar");
        InsertSlot slot = createClapSlot("TestPlugin");
        InsertClapEffectAction action = new InsertClapEffectAction(channel, 0, slot);

        assertThat(action.description()).isEqualTo("Insert CLAP Effect");
    }

    @Test
    void shouldExposeSlot() {
        MixerChannel channel = new MixerChannel("Guitar");
        InsertSlot slot = createClapSlot("TestPlugin");
        InsertClapEffectAction action = new InsertClapEffectAction(channel, 0, slot);

        assertThat(action.getSlot()).isSameAs(slot);
    }

    @Test
    void shouldRejectNullChannel() {
        InsertSlot slot = createClapSlot("Test");
        assertThatThrownBy(() -> new InsertClapEffectAction(null, 0, slot))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("channel");
    }

    @Test
    void shouldRejectNullSlot() {
        MixerChannel channel = new MixerChannel("Guitar");
        assertThatThrownBy(() -> new InsertClapEffectAction(channel, 0, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("slot");
    }

    @Test
    void shouldUpdateEffectsChainOnExecuteAndUndo() {
        MixerChannel channel = new MixerChannel("Guitar");
        InsertSlot slot = createClapSlot("TestPlugin");

        InsertClapEffectAction action = new InsertClapEffectAction(channel, 0, slot);

        action.execute();
        assertThat(channel.getEffectsChain().size()).isEqualTo(1);

        action.undo();
        assertThat(channel.getEffectsChain().isEmpty()).isTrue();
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
