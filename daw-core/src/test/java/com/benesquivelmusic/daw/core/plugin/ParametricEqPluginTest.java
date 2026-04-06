package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.ParametricEqProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ParametricEqPluginTest {

    @Test
    void shouldHavePublicNoArgConstructor() {
        ParametricEqPlugin plugin = new ParametricEqPlugin();
        assertThat(plugin).isNotNull();
    }

    @Test
    void shouldReturnMenuLabel() {
        assertThat(new ParametricEqPlugin().getMenuLabel()).isEqualTo("Parametric EQ");
    }

    @Test
    void shouldReturnMenuIcon() {
        assertThat(new ParametricEqPlugin().getMenuIcon()).isEqualTo("eq");
    }

    @Test
    void shouldReturnEffectCategory() {
        assertThat(new ParametricEqPlugin().getCategory()).isEqualTo(BuiltInPluginCategory.EFFECT);
    }

    @Test
    void shouldReturnDescriptorWithEffectType() {
        var descriptor = new ParametricEqPlugin().getDescriptor();
        assertThat(descriptor.type()).isEqualTo(PluginType.EFFECT);
        assertThat(descriptor.name()).isEqualTo("Parametric EQ");
        assertThat(descriptor.id()).isNotBlank();
        assertThat(descriptor.vendor()).isEqualTo("DAW Built-in");
    }

    @Test
    void shouldImplementDawPluginLifecycle() {
        var plugin = new ParametricEqPlugin();
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.deactivate();
        plugin.dispose();
    }

    @Test
    void shouldReturnProcessorAfterInitialize() {
        var plugin = new ParametricEqPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.getProcessor()).isNotNull();
        assertThat(plugin.asAudioProcessor()).isPresent();
    }

    @Test
    void shouldReturnProcessorOfCorrectType() {
        var plugin = new ParametricEqPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.getProcessor()).isInstanceOf(ParametricEqProcessor.class);
    }

    @Test
    void asAudioProcessorShouldReturnSameInstanceAsGetProcessor() {
        var plugin = new ParametricEqPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.asAudioProcessor().orElseThrow()).isSameAs(plugin.getProcessor());
    }

    @Test
    void shouldReturnEmptyProcessorBeforeInitialize() {
        var plugin = new ParametricEqPlugin();
        assertThat(plugin.asAudioProcessor()).isEmpty();
    }

    @Test
    void shouldClearProcessorOnDispose() {
        var plugin = new ParametricEqPlugin();
        plugin.initialize(stubContext());
        plugin.dispose();
        assertThat(plugin.asAudioProcessor()).isEmpty();
    }

    @Test
    void shouldDefaultToFlatResponse() {
        var plugin = new ParametricEqPlugin();
        plugin.initialize(stubContext());
        // Flat response means no EQ bands have been added
        assertThat(plugin.getProcessor().getBands()).isEmpty();
    }

    @Test
    void shouldExposeEmptyParameterDescriptors() {
        var plugin = new ParametricEqPlugin();
        // EQ band parameters are dynamic; no fixed parameter descriptors
        assertThat(plugin.getParameters()).isNotNull().isEmpty();
    }

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return 44100; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) {}
        };
    }
}
