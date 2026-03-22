package com.benesquivelmusic.daw.sdk.plugin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginDescriptorTest {

    @Test
    void shouldCreateDescriptorWithValidArguments() {
        PluginDescriptor descriptor = new PluginDescriptor(
                "com.example.reverb", "Reverb", "1.0.0", "Example Audio", PluginType.EFFECT);

        assertThat(descriptor.id()).isEqualTo("com.example.reverb");
        assertThat(descriptor.name()).isEqualTo("Reverb");
        assertThat(descriptor.version()).isEqualTo("1.0.0");
        assertThat(descriptor.vendor()).isEqualTo("Example Audio");
        assertThat(descriptor.type()).isEqualTo(PluginType.EFFECT);
    }

    @Test
    void shouldRejectNullId() {
        assertThatThrownBy(() -> new PluginDescriptor(null, "Test", "1.0", "Vendor", PluginType.EFFECT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("id");
    }

    @Test
    void shouldRejectBlankId() {
        assertThatThrownBy(() -> new PluginDescriptor("  ", "Test", "1.0", "Vendor", PluginType.EFFECT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
    }

    @Test
    void shouldRejectNullName() {
        assertThatThrownBy(() -> new PluginDescriptor("id", null, "1.0", "Vendor", PluginType.EFFECT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("name");
    }

    @Test
    void shouldRejectBlankName() {
        assertThatThrownBy(() -> new PluginDescriptor("id", "  ", "1.0", "Vendor", PluginType.EFFECT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void shouldRejectNullVersion() {
        assertThatThrownBy(() -> new PluginDescriptor("id", "Test", null, "Vendor", PluginType.EFFECT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("version");
    }

    @Test
    void shouldRejectNullVendor() {
        assertThatThrownBy(() -> new PluginDescriptor("id", "Test", "1.0", null, PluginType.EFFECT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("vendor");
    }

    @Test
    void shouldRejectNullType() {
        assertThatThrownBy(() -> new PluginDescriptor("id", "Test", "1.0", "Vendor", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("type");
    }

    @Test
    void shouldSupportAllPluginTypes() {
        for (PluginType type : PluginType.values()) {
            PluginDescriptor descriptor = new PluginDescriptor("id", "Test", "1.0", "Vendor", type);
            assertThat(descriptor.type()).isEqualTo(type);
        }
    }

    @Test
    void shouldImplementEqualsAndHashCode() {
        PluginDescriptor a = new PluginDescriptor("id", "Name", "1.0", "Vendor", PluginType.EFFECT);
        PluginDescriptor b = new PluginDescriptor("id", "Name", "1.0", "Vendor", PluginType.EFFECT);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void shouldImplementToString() {
        PluginDescriptor descriptor = new PluginDescriptor("id", "Name", "1.0", "Vendor", PluginType.INSTRUMENT);
        assertThat(descriptor.toString()).contains("id", "Name", "1.0", "Vendor", "INSTRUMENT");
    }
}
