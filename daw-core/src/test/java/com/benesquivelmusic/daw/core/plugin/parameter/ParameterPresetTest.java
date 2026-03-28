package com.benesquivelmusic.daw.core.plugin.parameter;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParameterPresetTest {

    @Test
    void shouldCreateUserPreset() {
        ParameterPreset preset = ParameterPreset.user("My Preset", Map.of(0, 1.0, 1, 2.0));

        assertThat(preset.name()).isEqualTo("My Preset");
        assertThat(preset.values()).containsEntry(0, 1.0);
        assertThat(preset.values()).containsEntry(1, 2.0);
        assertThat(preset.factory()).isFalse();
    }

    @Test
    void shouldCreateFactoryPreset() {
        ParameterPreset preset = ParameterPreset.factory("Factory", Map.of(0, 5.0));

        assertThat(preset.name()).isEqualTo("Factory");
        assertThat(preset.factory()).isTrue();
    }

    @Test
    void shouldHaveImmutableValues() {
        Map<Integer, Double> original = new java.util.HashMap<>();
        original.put(0, 1.0);
        ParameterPreset preset = ParameterPreset.user("Test", original);

        assertThatThrownBy(() -> preset.values().put(1, 2.0))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldRejectNullName() {
        assertThatThrownBy(() -> new ParameterPreset(null, Map.of(), false))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("name");
    }

    @Test
    void shouldRejectBlankName() {
        assertThatThrownBy(() -> new ParameterPreset("  ", Map.of(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void shouldRejectNullValues() {
        assertThatThrownBy(() -> new ParameterPreset("Test", null, false))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("values");
    }

    @Test
    void shouldAllowEmptyValues() {
        ParameterPreset preset = ParameterPreset.user("Empty", Map.of());
        assertThat(preset.values()).isEmpty();
    }

    @Test
    void shouldImplementEqualsAndHashCode() {
        ParameterPreset a = ParameterPreset.user("Test", Map.of(0, 1.0));
        ParameterPreset b = ParameterPreset.user("Test", Map.of(0, 1.0));

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void shouldDifferByFactoryFlag() {
        ParameterPreset user = ParameterPreset.user("Test", Map.of(0, 1.0));
        ParameterPreset factory = ParameterPreset.factory("Test", Map.of(0, 1.0));

        assertThat(user).isNotEqualTo(factory);
    }

    @Test
    void shouldImplementToString() {
        ParameterPreset preset = ParameterPreset.user("My Preset", Map.of(0, 1.0));
        assertThat(preset.toString()).contains("My Preset");
    }
}
