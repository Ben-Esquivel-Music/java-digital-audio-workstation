package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.CompressorProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompressorPluginTest {

    @Test
    void shouldHavePublicNoArgConstructor() {
        CompressorPlugin plugin = new CompressorPlugin();
        assertThat(plugin).isNotNull();
    }

    @Test
    void shouldReturnMenuLabel() {
        assertThat(new CompressorPlugin().getMenuLabel()).isEqualTo("Compressor");
    }

    @Test
    void shouldReturnMenuIcon() {
        assertThat(new CompressorPlugin().getMenuIcon()).isEqualTo("compressor");
    }

    @Test
    void shouldReturnEffectCategory() {
        assertThat(new CompressorPlugin().getCategory()).isEqualTo(BuiltInPluginCategory.EFFECT);
    }

    @Test
    void shouldReturnDescriptorWithEffectType() {
        var descriptor = new CompressorPlugin().getDescriptor();
        assertThat(descriptor.type()).isEqualTo(PluginType.EFFECT);
        assertThat(descriptor.name()).isEqualTo("Compressor");
        assertThat(descriptor.id()).isNotBlank();
        assertThat(descriptor.vendor()).isEqualTo("DAW Built-in");
    }

    @Test
    void shouldImplementDawPluginLifecycle() {
        var plugin = new CompressorPlugin();
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.deactivate();
        plugin.dispose();
    }

    @Test
    void shouldReturnProcessorAfterInitialize() {
        var plugin = new CompressorPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.getProcessor()).isNotNull();
        assertThat(plugin.asAudioProcessor()).isPresent();
    }

    @Test
    void shouldReturnCorrectProcessorType() {
        var plugin = new CompressorPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.getProcessor()).isInstanceOf(CompressorProcessor.class);
        assertThat(plugin.asAudioProcessor().get()).isInstanceOf(CompressorProcessor.class);
    }

    @Test
    void shouldReturnEmptyProcessorBeforeInitialize() {
        var plugin = new CompressorPlugin();
        assertThat(plugin.asAudioProcessor()).isEmpty();
    }

    @Test
    void shouldClearProcessorOnDispose() {
        var plugin = new CompressorPlugin();
        plugin.initialize(stubContext());
        plugin.dispose();
        assertThat(plugin.asAudioProcessor()).isEmpty();
    }

    @Test
    void shouldHaveReasonableDefaultParameters() {
        var plugin = new CompressorPlugin();
        plugin.initialize(stubContext());
        var processor = plugin.getProcessor();
        assertThat(processor.getThresholdDb()).isEqualTo(-20.0);
        assertThat(processor.getRatio()).isEqualTo(4.0);
        assertThat(processor.getAttackMs()).isEqualTo(10.0);
        assertThat(processor.getReleaseMs()).isEqualTo(100.0);
    }

    @Test
    void shouldExposeParameterDescriptors() {
        var plugin = new CompressorPlugin();
        var params = plugin.getParameters();
        assertThat(params).isNotEmpty();
        assertThat(params).hasSize(6);
        assertThat(params.stream().map(p -> p.name())).contains(
                "Threshold (dB)", "Ratio", "Attack (ms)", "Release (ms)");
    }

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return 44100; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) {}
        };
    }
}
