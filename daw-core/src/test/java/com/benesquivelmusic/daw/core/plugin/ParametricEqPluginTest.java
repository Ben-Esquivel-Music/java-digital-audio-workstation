package com.benesquivelmusic.daw.core.plugin;

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
        plugin.initialize(null);
        plugin.activate();
        plugin.deactivate();
        plugin.dispose();
    }
}
