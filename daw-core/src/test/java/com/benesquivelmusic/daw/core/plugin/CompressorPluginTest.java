package com.benesquivelmusic.daw.core.plugin;

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
        plugin.initialize(null);
        plugin.activate();
        plugin.deactivate();
        plugin.dispose();
    }
}
