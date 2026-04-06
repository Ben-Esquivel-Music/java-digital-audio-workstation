package com.benesquivelmusic.daw.core.plugin;

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

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return 44100; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) {}
        };
    }
}
