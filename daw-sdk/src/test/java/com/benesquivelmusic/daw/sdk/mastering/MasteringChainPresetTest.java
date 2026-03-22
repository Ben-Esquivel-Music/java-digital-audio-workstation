package com.benesquivelmusic.daw.sdk.mastering;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MasteringChainPresetTest {

    @Test
    void shouldCreatePresetWithAllFields() {
        List<MasteringStageConfig> stages = List.of(
                MasteringStageConfig.of(MasteringStageType.GAIN_STAGING, "Gain",
                        Map.of("gainDb", 0.0)),
                MasteringStageConfig.of(MasteringStageType.COMPRESSION, "Compressor",
                        Map.of("thresholdDb", -18.0, "ratio", 4.0))
        );
        MasteringChainPreset preset = new MasteringChainPreset("Test Preset", "Rock", stages);

        assertThat(preset.name()).isEqualTo("Test Preset");
        assertThat(preset.genre()).isEqualTo("Rock");
        assertThat(preset.stages()).hasSize(2);
    }

    @Test
    void shouldPreserveStageOrder() {
        List<MasteringStageConfig> stages = List.of(
                MasteringStageConfig.of(MasteringStageType.GAIN_STAGING, "Gain", Map.of()),
                MasteringStageConfig.of(MasteringStageType.EQ_CORRECTIVE, "EQ1", Map.of()),
                MasteringStageConfig.of(MasteringStageType.COMPRESSION, "Comp", Map.of()),
                MasteringStageConfig.of(MasteringStageType.EQ_TONAL, "EQ2", Map.of()),
                MasteringStageConfig.of(MasteringStageType.STEREO_IMAGING, "Image", Map.of()),
                MasteringStageConfig.of(MasteringStageType.LIMITING, "Limiter", Map.of()),
                MasteringStageConfig.of(MasteringStageType.DITHERING, "Dither", Map.of())
        );
        MasteringChainPreset preset = new MasteringChainPreset("Full Chain", "Pop/EDM", stages);

        assertThat(preset.stages()).extracting(MasteringStageConfig::stageType)
                .containsExactly(
                        MasteringStageType.GAIN_STAGING,
                        MasteringStageType.EQ_CORRECTIVE,
                        MasteringStageType.COMPRESSION,
                        MasteringStageType.EQ_TONAL,
                        MasteringStageType.STEREO_IMAGING,
                        MasteringStageType.LIMITING,
                        MasteringStageType.DITHERING
                );
    }

    @Test
    void shouldHaveImmutableStageList() {
        List<MasteringStageConfig> stages = List.of(
                MasteringStageConfig.of(MasteringStageType.GAIN_STAGING, "Gain", Map.of())
        );
        MasteringChainPreset preset = new MasteringChainPreset("Test", "Genre", stages);

        assertThatThrownBy(() -> preset.stages().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldSerializeAndDeserializeRoundTrip() {
        // Create a preset, extract its data, rebuild it, and verify equality
        MasteringChainPreset original = new MasteringChainPreset("Round-Trip", "Rock", List.of(
                MasteringStageConfig.of(MasteringStageType.COMPRESSION, "Comp",
                        Map.of("thresholdDb", -18.0, "ratio", 4.0, "attackMs", 10.0)),
                new MasteringStageConfig(MasteringStageType.LIMITING, "Limiter",
                        Map.of("ceilingDb", -0.5), true)
        ));

        // Simulate serialization round-trip by reconstructing from accessors
        MasteringChainPreset rebuilt = new MasteringChainPreset(
                original.name(),
                original.genre(),
                original.stages().stream()
                        .map(s -> new MasteringStageConfig(
                                s.stageType(), s.name(), s.parameters(), s.bypassed()))
                        .toList()
        );

        assertThat(rebuilt).isEqualTo(original);
        assertThat(rebuilt.hashCode()).isEqualTo(original.hashCode());
    }

    @Test
    void shouldPreserveParameterValues() {
        Map<String, Double> params = Map.of("thresholdDb", -18.0, "ratio", 4.0, "attackMs", 10.0);
        MasteringStageConfig config = MasteringStageConfig.of(MasteringStageType.COMPRESSION, "Comp", params);

        assertThat(config.parameters()).containsEntry("thresholdDb", -18.0);
        assertThat(config.parameters()).containsEntry("ratio", 4.0);
        assertThat(config.parameters()).containsEntry("attackMs", 10.0);
    }

    @Test
    void shouldPreserveBypassState() {
        MasteringStageConfig bypassed = new MasteringStageConfig(
                MasteringStageType.EQ_CORRECTIVE, "EQ", Map.of(), true);
        MasteringStageConfig enabled = MasteringStageConfig.of(
                MasteringStageType.EQ_CORRECTIVE, "EQ", Map.of());

        assertThat(bypassed.bypassed()).isTrue();
        assertThat(enabled.bypassed()).isFalse();
    }

    @Test
    void shouldRejectNullFields() {
        assertThatThrownBy(() -> new MasteringChainPreset(null, "Genre", List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MasteringChainPreset("Name", null, List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MasteringChainPreset("Name", "Genre", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullStageConfigFields() {
        assertThatThrownBy(() -> MasteringStageConfig.of(null, "Name", Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MasteringStageConfig.of(MasteringStageType.LIMITING, null, Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MasteringStageConfig.of(MasteringStageType.LIMITING, "Name", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldHaveImmutableParameterMap() {
        MasteringStageConfig config = MasteringStageConfig.of(MasteringStageType.COMPRESSION, "Comp",
                Map.of("thresholdDb", -18.0));

        assertThatThrownBy(() -> config.parameters().put("newKey", 1.0))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
