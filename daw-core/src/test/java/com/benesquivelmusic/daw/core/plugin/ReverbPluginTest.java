package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.ReverbProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReverbPluginTest {

    @Test
    void shouldHavePublicNoArgConstructor() {
        ReverbPlugin plugin = new ReverbPlugin();
        assertThat(plugin).isNotNull();
    }

    @Test
    void shouldReturnMenuLabel() {
        assertThat(new ReverbPlugin().getMenuLabel()).isEqualTo("Reverb");
    }

    @Test
    void shouldReturnMenuIcon() {
        assertThat(new ReverbPlugin().getMenuIcon()).isEqualTo("reverb");
    }

    @Test
    void shouldReturnEffectCategory() {
        assertThat(new ReverbPlugin().getCategory()).isEqualTo(BuiltInPluginCategory.EFFECT);
    }

    @Test
    void shouldReturnDescriptorWithEffectType() {
        var descriptor = new ReverbPlugin().getDescriptor();
        assertThat(descriptor.type()).isEqualTo(PluginType.EFFECT);
        assertThat(descriptor.name()).isEqualTo("Reverb");
        assertThat(descriptor.id()).isNotBlank();
        assertThat(descriptor.vendor()).isEqualTo("DAW Built-in");
    }

    @Test
    void shouldImplementDawPluginLifecycle() {
        var plugin = new ReverbPlugin();
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.deactivate();
        plugin.dispose();
    }

    @Test
    void shouldReturnProcessorAfterInitialize() {
        var plugin = new ReverbPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.getProcessor()).isNotNull();
        assertThat(plugin.asAudioProcessor()).isPresent();
    }

    @Test
    void shouldReturnProcessorOfCorrectType() {
        var plugin = new ReverbPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.getProcessor()).isInstanceOf(ReverbProcessor.class);
    }

    @Test
    void asAudioProcessorShouldReturnSameInstanceAsGetProcessor() {
        var plugin = new ReverbPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.asAudioProcessor().orElseThrow()).isSameAs(plugin.getProcessor());
    }

    @Test
    void shouldReturnEmptyProcessorBeforeInitialize() {
        var plugin = new ReverbPlugin();
        assertThat(plugin.asAudioProcessor()).isEmpty();
    }

    @Test
    void shouldClearProcessorOnDispose() {
        var plugin = new ReverbPlugin();
        plugin.initialize(stubContext());
        plugin.dispose();
        assertThat(plugin.asAudioProcessor()).isEmpty();
    }

    @Test
    void shouldHaveReasonableDefaultParameters() {
        var plugin = new ReverbPlugin();
        plugin.initialize(stubContext());
        ReverbProcessor p = plugin.getProcessor();
        assertThat(p.getRoomSize()).isBetween(0.0, 1.0);
        assertThat(p.getDecay()).isBetween(0.0, 1.0);
        assertThat(p.getDamping()).isBetween(0.0, 1.0);
        assertThat(p.getMix()).isBetween(0.0, 1.0);
    }

    @Test
    void shouldExposeParameterDescriptors() {
        var plugin = new ReverbPlugin();
        assertThat(plugin.getParameters()).isNotNull().hasSize(4);
        assertThat(plugin.getParameters().stream().map(p -> p.name()))
                .contains("Room Size", "Decay", "Damping", "Mix");
    }

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return 44100; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) {}
        };
    }
}
