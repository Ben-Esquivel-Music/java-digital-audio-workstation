package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.dynamics.BusCompressorProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BusCompressorPluginTest {

    @Test
    void shouldHavePublicNoArgConstructor() {
        assertThat(new BusCompressorPlugin()).isNotNull();
    }

    @Test
    void shouldReturnMenuLabel() {
        assertThat(new BusCompressorPlugin().getMenuLabel()).isEqualTo("Bus Compressor");
    }

    @Test
    void shouldReturnEffectCategory() {
        assertThat(new BusCompressorPlugin().getCategory()).isEqualTo(BuiltInPluginCategory.EFFECT);
    }

    @Test
    void shouldReturnDescriptorWithEffectType() {
        var d = new BusCompressorPlugin().getDescriptor();
        assertThat(d.type()).isEqualTo(PluginType.EFFECT);
        assertThat(d.name()).isEqualTo("Bus Compressor");
        assertThat(d.id()).isEqualTo(BusCompressorPlugin.PLUGIN_ID);
        assertThat(d.vendor()).isEqualTo("DAW Built-in");
    }

    @Test
    void shouldImplementDawPluginLifecycle() {
        var plugin = new BusCompressorPlugin();
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.deactivate();
        plugin.dispose();
    }

    @Test
    void shouldReturnProcessorAfterInitialize() {
        var plugin = new BusCompressorPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.getProcessor()).isInstanceOf(BusCompressorProcessor.class);
        assertThat(plugin.asAudioProcessor()).isPresent();
        assertThat(plugin.asAudioProcessor().orElseThrow()).isSameAs(plugin.getProcessor());
    }

    @Test
    void shouldClearProcessorOnDispose() {
        var plugin = new BusCompressorPlugin();
        plugin.initialize(stubContext());
        plugin.dispose();
        assertThat(plugin.asAudioProcessor()).isEmpty();
    }

    @Test
    void shouldExposeSixParameterDescriptors() {
        var plugin = new BusCompressorPlugin();
        assertThat(plugin.getParameters()).hasSize(6);
        assertThat(plugin.getParameters().stream().map(p -> p.name()))
                .containsExactly("Threshold (dB)", "Ratio", "Attack (ms)",
                        "Release (s)", "Makeup Gain (dB)", "Mix");
    }

    @Test
    void shouldHaveDistinctIdFromCompressorPlugin() {
        assertThat(BusCompressorPlugin.PLUGIN_ID).isNotEqualTo(CompressorPlugin.PLUGIN_ID);
    }

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return 44100; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) {}
        };
    }
}
