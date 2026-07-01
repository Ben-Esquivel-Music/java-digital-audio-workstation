package com.benesquivelmusic.daw.sdk.plugin;

import org.junit.jupiter.api.Test;

import com.benesquivelmusic.daw.sdk.editor.PluginCategory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the backwards-compatible widening of {@link PluginDescriptor} with the
 * {@code category} and {@code iconHint} components (Plugin View Design Book §4.4,
 * §8.1.3): the legacy five-argument constructor still compiles and defaults the
 * new fields, while the seven-argument constructor round-trips non-default values.
 */
class PluginDescriptorBackCompatTest {

    @Test
    void legacyConstructorDefaultsCategoryToUtilityAndIconHintToEmpty() {
        PluginDescriptor descriptor = new PluginDescriptor(
                "com.example.reverb", "Reverb", "1.0.0", "Example Audio", PluginType.EFFECT);

        assertThat(descriptor.category()).isEqualTo(PluginCategory.UTILITY);
        assertThat(descriptor.iconHint()).isEmpty();
    }

    @Test
    void sevenArgConstructorRoundTripsNonDefaultCategoryAndIconHint() {
        PluginDescriptor descriptor = new PluginDescriptor(
                "com.example.comp", "Compressor", "2.1.0", "Example Audio",
                PluginType.EFFECT, PluginCategory.DYNAMICS, "compressor");

        assertThat(descriptor.category()).isEqualTo(PluginCategory.DYNAMICS);
        assertThat(descriptor.iconHint()).isEqualTo("compressor");
    }

    @Test
    void legacyDescriptorEqualsExplicitSevenArgWithDefaults() {
        PluginDescriptor legacy = new PluginDescriptor(
                "id", "Name", "1.0", "Vendor", PluginType.EFFECT);
        PluginDescriptor explicit = new PluginDescriptor(
                "id", "Name", "1.0", "Vendor", PluginType.EFFECT, PluginCategory.UTILITY, "");

        assertThat(legacy).isEqualTo(explicit);
        assertThat(legacy.hashCode()).isEqualTo(explicit.hashCode());
    }

    @Test
    void rejectsNullCategory() {
        assertThatThrownBy(() -> new PluginDescriptor(
                "id", "Name", "1.0", "Vendor", PluginType.EFFECT, null, ""))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("category");
    }

    @Test
    void rejectsNullIconHint() {
        assertThatThrownBy(() -> new PluginDescriptor(
                "id", "Name", "1.0", "Vendor", PluginType.EFFECT, PluginCategory.UTILITY, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("iconHint");
    }
}
