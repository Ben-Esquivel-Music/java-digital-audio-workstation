package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.mixer.ProcessorRegistry;
import com.benesquivelmusic.daw.core.plugin.builtin.midi.MidiMessage;
import com.benesquivelmusic.daw.core.plugin.builtin.midi.MidiProcessContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuiltInPluginGraphTest {
    @Test
    void midiEffectsAreRejectedBeforeInitializationAndDisposedOnce() {
        var plugin = new MidiFixture();
        var graph = new BuiltInPluginGraph(new ProcessorRegistry());

        assertThatThrownBy(() -> graph.createSlot(plugin, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MIDI effects are not supported in audio insert slots");

        assertThat(plugin.initialized).isFalse();
        assertThat(plugin.activated).isFalse();
        assertThat(plugin.disposalCount).isOne();
    }

    private static final class MidiFixture implements MidiEffectPlugin {
        private boolean initialized;
        private boolean activated;
        private int disposalCount;

        @Override public PluginDescriptor getDescriptor() {
            return new PluginDescriptor("test.midi", "MIDI fixture", "1", "Test", PluginType.MIDI_EFFECT);
        }
        @Override public void initialize(PluginContext context) { initialized = true; }
        @Override public void activate() { activated = true; }
        @Override public void deactivate() { activated = false; }
        @Override public void dispose() { disposalCount++; }
        @Override public MidiMessage[] process(MidiMessage[] in, int sampleOffset, MidiProcessContext context) {
            return in;
        }
    }
}
