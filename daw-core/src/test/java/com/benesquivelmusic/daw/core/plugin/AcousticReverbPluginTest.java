package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.acoustics.AcousticReverbProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AcousticReverbPluginTest {

    @Test
    void shouldHavePublicNoArgConstructor() {
        assertThat(new AcousticReverbPlugin()).isNotNull();
    }

    @Test
    void shouldReturnMenuMetadata() {
        var plugin = new AcousticReverbPlugin();
        assertThat(plugin.getMenuLabel()).isEqualTo("Acoustic Reverb");
        assertThat(plugin.getMenuIcon()).isEqualTo("acoustic-reverb");
        assertThat(plugin.getCategory()).isEqualTo(BuiltInPluginCategory.EFFECT);
    }

    @Test
    void shouldReturnEffectDescriptor() {
        var d = new AcousticReverbPlugin().getDescriptor();
        assertThat(d.type()).isEqualTo(PluginType.EFFECT);
        assertThat(d.name()).isEqualTo("Acoustic Reverb");
        assertThat(d.id()).isEqualTo(AcousticReverbPlugin.PLUGIN_ID);
        assertThat(d.vendor()).isEqualTo("DAW Built-in");
    }

    @Test
    void shouldReturnProcessorAfterInitialize() {
        var plugin = new AcousticReverbPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.asAudioProcessor()).isPresent();
        assertThat(plugin.getProcessor()).isInstanceOf(AcousticReverbProcessor.class);
        assertThat(plugin.asAudioProcessor().orElseThrow()).isSameAs(plugin.getProcessor());
    }

    @Test
    void shouldReturnEmptyBeforeInitialize() {
        assertThat(new AcousticReverbPlugin().asAudioProcessor()).isEmpty();
    }

    @Test
    void shouldClearProcessorOnDispose() {
        var plugin = new AcousticReverbPlugin();
        plugin.initialize(stubContext());
        plugin.dispose();
        assertThat(plugin.asAudioProcessor()).isEmpty();
    }

    @Test
    void shouldCompleteLifecycleWithoutErrors() {
        var plugin = new AcousticReverbPlugin();
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.deactivate();
        plugin.dispose();
    }

    @Test
    void shouldExposeParameterDescriptors() {
        var params = new AcousticReverbPlugin().getParameters();
        assertThat(params).hasSize(3);
        assertThat(params.stream().map(p -> p.name()))
                .contains("Preset", "T60 (s)", "Mix");
    }

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return 44100; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) {}
        };
    }
}
