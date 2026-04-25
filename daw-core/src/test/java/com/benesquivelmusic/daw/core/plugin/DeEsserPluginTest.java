package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.dynamics.DeEsserProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeEsserPluginTest {

    @Test
    void shouldHavePublicNoArgConstructor() {
        assertThat(new DeEsserPlugin()).isNotNull();
    }

    @Test
    void shouldReturnMenuLabel() {
        assertThat(new DeEsserPlugin().getMenuLabel()).isEqualTo("De-Esser");
    }

    @Test
    void shouldReturnEffectCategory() {
        assertThat(new DeEsserPlugin().getCategory()).isEqualTo(BuiltInPluginCategory.EFFECT);
    }

    @Test
    void shouldReturnDescriptorWithEffectType() {
        var d = new DeEsserPlugin().getDescriptor();
        assertThat(d.type()).isEqualTo(PluginType.EFFECT);
        assertThat(d.name()).isEqualTo("De-Esser");
        assertThat(d.id()).isEqualTo(DeEsserPlugin.PLUGIN_ID);
        assertThat(d.vendor()).isEqualTo("DAW Built-in");
    }

    @Test
    void shouldImplementDawPluginLifecycle() {
        var plugin = new DeEsserPlugin();
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.deactivate();
        plugin.dispose();
    }

    @Test
    void shouldReturnProcessorAfterInitialize() {
        var plugin = new DeEsserPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.getProcessor()).isInstanceOf(DeEsserProcessor.class);
        assertThat(plugin.asAudioProcessor()).isPresent();
        assertThat(plugin.asAudioProcessor().orElseThrow()).isSameAs(plugin.getProcessor());
    }

    @Test
    void shouldClearProcessorOnDispose() {
        var plugin = new DeEsserPlugin();
        plugin.initialize(stubContext());
        plugin.dispose();
        assertThat(plugin.asAudioProcessor()).isEmpty();
    }

    @Test
    void shouldExposeSixParameterDescriptors() {
        var plugin = new DeEsserPlugin();
        assertThat(plugin.getParameters()).hasSize(6);
        assertThat(plugin.getParameters().stream().map(p -> p.name()))
                .containsExactly("Frequency (Hz)", "Q", "Threshold (dB)",
                        "Range (dB)", "Mode", "Listen");
    }

    @Test
    void shouldHaveDistinctIdFromOtherDynamicsPlugins() {
        assertThat(DeEsserPlugin.PLUGIN_ID).isNotEqualTo(CompressorPlugin.PLUGIN_ID);
        assertThat(DeEsserPlugin.PLUGIN_ID).isNotEqualTo(BusCompressorPlugin.PLUGIN_ID);
        assertThat(DeEsserPlugin.PLUGIN_ID).isNotEqualTo(MultibandCompressorPlugin.PLUGIN_ID);
    }

    @Test
    void shouldAppearInBuiltInDawPluginPermittedSet() {
        var classes = java.util.Arrays.stream(BuiltInDawPlugin.class.getPermittedSubclasses())
                .toList();
        assertThat(classes).contains(DeEsserPlugin.class);
    }

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return 44100; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) {}
        };
    }
}
