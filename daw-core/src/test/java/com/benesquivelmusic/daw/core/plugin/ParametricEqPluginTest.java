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
    void shouldReturnCorrectProcessorType() {
        var plugin = new ParametricEqPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.getProcessor()).isInstanceOf(ParametricEqProcessor.class);
        assertThat(plugin.asAudioProcessor().get()).isInstanceOf(ParametricEqProcessor.class);
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
    void shouldHaveFlatResponseByDefault() {
        var plugin = new ParametricEqPlugin();
        plugin.initialize(stubContext());
        var processor = plugin.getProcessor();
        assertThat(processor.getBands()).isEmpty();
    }

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return 44100; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) {}
        };
    }
}
