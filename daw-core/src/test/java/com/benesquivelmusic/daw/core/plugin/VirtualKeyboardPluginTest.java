package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VirtualKeyboardPluginTest {

    @Test
    void shouldHavePublicNoArgConstructor() {
        VirtualKeyboardPlugin plugin = new VirtualKeyboardPlugin();
        assertThat(plugin).isNotNull();
    }

    @Test
    void shouldReturnMenuLabel() {
        assertThat(new VirtualKeyboardPlugin().getMenuLabel()).isEqualTo("Virtual Keyboard");
    }

    @Test
    void shouldReturnMenuIcon() {
        assertThat(new VirtualKeyboardPlugin().getMenuIcon()).isEqualTo("keyboard");
    }

    @Test
    void shouldReturnInstrumentCategory() {
        assertThat(new VirtualKeyboardPlugin().getCategory()).isEqualTo(BuiltInPluginCategory.INSTRUMENT);
    }

    @Test
    void shouldReturnDescriptorWithInstrumentType() {
        var descriptor = new VirtualKeyboardPlugin().getDescriptor();
        assertThat(descriptor.type()).isEqualTo(PluginType.INSTRUMENT);
        assertThat(descriptor.name()).isEqualTo("Virtual Keyboard");
        assertThat(descriptor.id()).isNotBlank();
        assertThat(descriptor.vendor()).isEqualTo("DAW Built-in");
    }

    @Test
    void shouldImplementDawPluginLifecycle() {
        var plugin = new VirtualKeyboardPlugin();
        plugin.initialize(null);
        plugin.activate();
        plugin.deactivate();
        plugin.dispose();
    }
}
