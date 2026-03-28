package com.benesquivelmusic.daw.core.plugin.parameter;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltInEffectPresetsTest {

    @Test
    void shouldProvideCompressorPresets() {
        List<ParameterPreset> presets = BuiltInEffectPresets.compressorPresets();

        assertThat(presets).isNotEmpty();
        assertThat(presets).allMatch(ParameterPreset::factory);
        assertThat(presets.stream().map(ParameterPreset::name))
                .contains("Drum Bus Compression", "Gentle Vocal Compression",
                        "Parallel Crush", "Master Bus Glue");
    }

    @Test
    void shouldProvideReverbPresets() {
        List<ParameterPreset> presets = BuiltInEffectPresets.reverbPresets();

        assertThat(presets).isNotEmpty();
        assertThat(presets).allMatch(ParameterPreset::factory);
        assertThat(presets.stream().map(ParameterPreset::name))
                .contains("Small Room", "Large Hall", "Plate Vocal", "Ambient Wash");
    }

    @Test
    void shouldProvideDelayPresets() {
        List<ParameterPreset> presets = BuiltInEffectPresets.delayPresets();

        assertThat(presets).isNotEmpty();
        assertThat(presets).allMatch(ParameterPreset::factory);
        assertThat(presets.stream().map(ParameterPreset::name))
                .contains("Slapback", "Quarter Note Echo");
    }

    @Test
    void shouldProvideEqPresets() {
        List<ParameterPreset> presets = BuiltInEffectPresets.eqPresets();

        assertThat(presets).isNotEmpty();
        assertThat(presets).allMatch(ParameterPreset::factory);
        assertThat(presets.stream().map(ParameterPreset::name))
                .contains("Warm Vocal EQ", "Bright Acoustic Guitar");
    }

    @Test
    void shouldProvideAllPresetsGroupedByEffect() {
        Map<String, List<ParameterPreset>> all = BuiltInEffectPresets.allPresets();

        assertThat(all).containsKeys("Compressor", "Reverb", "Delay", "Parametric EQ");
        assertThat(all.get("Compressor")).isEqualTo(BuiltInEffectPresets.compressorPresets());
        assertThat(all.get("Reverb")).isEqualTo(BuiltInEffectPresets.reverbPresets());
    }

    @Test
    void shouldHaveNonEmptyValuesInAllPresets() {
        Map<String, List<ParameterPreset>> all = BuiltInEffectPresets.allPresets();

        for (Map.Entry<String, List<ParameterPreset>> entry : all.entrySet()) {
            for (ParameterPreset preset : entry.getValue()) {
                assertThat(preset.values())
                        .as("Preset '%s' in '%s' should have values", preset.name(), entry.getKey())
                        .isNotEmpty();
            }
        }
    }

    @Test
    void shouldHaveUniquePresetNamesPerEffect() {
        Map<String, List<ParameterPreset>> all = BuiltInEffectPresets.allPresets();

        for (Map.Entry<String, List<ParameterPreset>> entry : all.entrySet()) {
            List<String> names = entry.getValue().stream()
                    .map(ParameterPreset::name)
                    .toList();
            assertThat(names)
                    .as("Preset names in '%s' should be unique", entry.getKey())
                    .doesNotHaveDuplicates();
        }
    }

    @Test
    void shouldReturnUnmodifiableAllPresetsMap() {
        Map<String, List<ParameterPreset>> all = BuiltInEffectPresets.allPresets();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> all.put("New", List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
