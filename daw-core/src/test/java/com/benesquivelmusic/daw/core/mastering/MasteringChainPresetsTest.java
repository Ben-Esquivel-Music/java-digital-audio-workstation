package com.benesquivelmusic.daw.core.mastering;

import com.benesquivelmusic.daw.sdk.mastering.MasteringChainPreset;
import com.benesquivelmusic.daw.sdk.mastering.MasteringStageConfig;
import com.benesquivelmusic.daw.sdk.mastering.MasteringStageType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MasteringChainPresetsTest {

    @Test
    void shouldProvidePopEdmPreset() {
        MasteringChainPreset preset = MasteringChainPresets.popEdm();

        assertThat(preset.name()).isEqualTo("Pop/EDM Master");
        assertThat(preset.genre()).isEqualTo("Pop/EDM");
        assertThat(preset.stages()).hasSize(7);
        assertThat(preset.stages().get(0).stageType()).isEqualTo(MasteringStageType.GAIN_STAGING);
        assertThat(preset.stages().get(6).stageType()).isEqualTo(MasteringStageType.DITHERING);
    }

    @Test
    void shouldProvideRockPreset() {
        MasteringChainPreset preset = MasteringChainPresets.rock();

        assertThat(preset.name()).isEqualTo("Rock Master");
        assertThat(preset.genre()).isEqualTo("Rock");
        assertThat(preset.stages()).hasSize(7);
    }

    @Test
    void shouldProvideJazzClassicalPreset() {
        MasteringChainPreset preset = MasteringChainPresets.jazzClassical();

        assertThat(preset.name()).isEqualTo("Jazz/Classical Master");
        assertThat(preset.genre()).isEqualTo("Jazz/Classical");
        assertThat(preset.stages()).hasSize(7);
        // Jazz/Classical: gentle compression (lower ratio)
        assertThat(preset.stages().get(2).parameters().get("ratio")).isEqualTo(2.0);
    }

    @Test
    void shouldProvideHipHopRnBPreset() {
        MasteringChainPreset preset = MasteringChainPresets.hipHopRnB();

        assertThat(preset.name()).isEqualTo("Hip-Hop/R&B Master");
        assertThat(preset.genre()).isEqualTo("Hip-Hop/R&B");
        assertThat(preset.stages()).hasSize(7);
        // Hip-Hop: deep bass emphasis
        assertThat(preset.stages().get(3).parameters().get("lowShelfGainDb")).isEqualTo(3.0);
    }

    @Test
    void shouldReturnAllDefaults() {
        List<MasteringChainPreset> all = MasteringChainPresets.allDefaults();

        assertThat(all).hasSize(4);
        assertThat(all).extracting(MasteringChainPreset::genre)
                .containsExactly("Pop/EDM", "Rock", "Jazz/Classical", "Hip-Hop/R&B");
    }

    @Test
    void shouldFollowStandardMasteringChainOrder() {
        for (MasteringChainPreset preset : MasteringChainPresets.allDefaults()) {
            assertThat(preset.stages()).extracting(s -> s.stageType())
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
    }

    @Test
    void shouldHaveNonEmptyParametersForEachStage() {
        for (MasteringChainPreset preset : MasteringChainPresets.allDefaults()) {
            for (MasteringStageConfig stage : preset.stages()) {
                assertThat(stage.parameters())
                        .as("Stage %s in preset %s should have parameters",
                                stage.name(), preset.name())
                        .isNotEmpty();
            }
        }
    }
}
