package com.benesquivelmusic.daw.core.mastering;

import com.benesquivelmusic.daw.core.dsp.*;
import com.benesquivelmusic.daw.core.dsp.mastering.DitherProcessor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.mastering.MasteringStageConfig;
import com.benesquivelmusic.daw.sdk.mastering.MasteringStageType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MasteringProcessorFactoryTest {

    private static final int CHANNELS = 2;
    private static final double SAMPLE_RATE = 44100.0;

    @Test
    void shouldCreateGainStagingProcessor() {
        MasteringStageConfig config = MasteringStageConfig.of(
                MasteringStageType.GAIN_STAGING, "Input Gain",
                Map.of("gainDb", 3.0));

        AudioProcessor processor = MasteringProcessorFactory.createProcessor(config, CHANNELS, SAMPLE_RATE);

        assertThat(processor).isInstanceOf(GainStagingProcessor.class);
        assertThat(((GainStagingProcessor) processor).getGainDb()).isEqualTo(3.0);
    }

    @Test
    void shouldCreateCorrectiveEqProcessor() {
        MasteringStageConfig config = MasteringStageConfig.of(
                MasteringStageType.EQ_CORRECTIVE, "Corrective EQ",
                Map.of("highPassHz", 30.0, "highPassQ", 0.707));

        AudioProcessor processor = MasteringProcessorFactory.createProcessor(config, CHANNELS, SAMPLE_RATE);

        assertThat(processor).isInstanceOf(ParametricEqProcessor.class);
        ParametricEqProcessor eq = (ParametricEqProcessor) processor;
        assertThat(eq.getBands()).hasSize(1);
    }

    @Test
    void shouldCreateCompressorProcessor() {
        MasteringStageConfig config = MasteringStageConfig.of(
                MasteringStageType.COMPRESSION, "Glue Comp",
                Map.of("thresholdDb", -16.0, "ratio", 3.0,
                        "attackMs", 15.0, "releaseMs", 150.0, "kneeDb", 6.0));

        AudioProcessor processor = MasteringProcessorFactory.createProcessor(config, CHANNELS, SAMPLE_RATE);

        assertThat(processor).isInstanceOf(CompressorProcessor.class);
        CompressorProcessor comp = (CompressorProcessor) processor;
        assertThat(comp.getThresholdDb()).isEqualTo(-16.0);
        assertThat(comp.getRatio()).isEqualTo(3.0);
        assertThat(comp.getAttackMs()).isEqualTo(15.0);
        assertThat(comp.getReleaseMs()).isEqualTo(150.0);
        assertThat(comp.getKneeDb()).isEqualTo(6.0);
    }

    @Test
    void shouldCreateTonalEqProcessor() {
        MasteringStageConfig config = MasteringStageConfig.of(
                MasteringStageType.EQ_TONAL, "Tonal EQ",
                Map.of("lowShelfHz", 80.0, "lowShelfGainDb", 1.5,
                        "highShelfHz", 12000.0, "highShelfGainDb", 2.0));

        AudioProcessor processor = MasteringProcessorFactory.createProcessor(config, CHANNELS, SAMPLE_RATE);

        assertThat(processor).isInstanceOf(ParametricEqProcessor.class);
        ParametricEqProcessor eq = (ParametricEqProcessor) processor;
        assertThat(eq.getBands()).hasSize(2);
    }

    @Test
    void shouldCreateStereoImagerProcessor() {
        MasteringStageConfig config = MasteringStageConfig.of(
                MasteringStageType.STEREO_IMAGING, "Stereo Width",
                Map.of("width", 1.3));

        AudioProcessor processor = MasteringProcessorFactory.createProcessor(config, CHANNELS, SAMPLE_RATE);

        assertThat(processor).isInstanceOf(StereoImagerProcessor.class);
        assertThat(((StereoImagerProcessor) processor).getWidth()).isEqualTo(1.3);
    }

    @Test
    void shouldCreateLimiterProcessor() {
        MasteringStageConfig config = MasteringStageConfig.of(
                MasteringStageType.LIMITING, "Limiter",
                Map.of("ceilingDb", -0.3, "releaseMs", 50.0));

        AudioProcessor processor = MasteringProcessorFactory.createProcessor(config, CHANNELS, SAMPLE_RATE);

        assertThat(processor).isInstanceOf(LimiterProcessor.class);
        LimiterProcessor limiter = (LimiterProcessor) processor;
        assertThat(limiter.getCeilingDb()).isEqualTo(-0.3);
        assertThat(limiter.getReleaseMs()).isEqualTo(50.0);
    }

    @Test
    void shouldCreateDitherProcessor() {
        MasteringStageConfig config = MasteringStageConfig.of(
                MasteringStageType.DITHERING, "Dither",
                Map.of("bitDepth", 16.0));

        AudioProcessor processor = MasteringProcessorFactory.createProcessor(config, CHANNELS, SAMPLE_RATE);

        assertThat(processor).isInstanceOf(DitherProcessor.class);
        assertThat(((DitherProcessor) processor).getTargetBitDepth()).isEqualTo(16);
    }

    @Test
    void shouldUseDefaultParametersWhenMissing() {
        // Empty parameters — all stages should still create valid processors
        for (MasteringStageType type : MasteringStageType.values()) {
            MasteringStageConfig config = MasteringStageConfig.of(type, "Test", Map.of());
            AudioProcessor processor = MasteringProcessorFactory.createProcessor(config, CHANNELS, SAMPLE_RATE);
            assertThat(processor).isNotNull();
        }
    }

    @Test
    void shouldCreateProcessorsFromPopEdmPreset() {
        var preset = MasteringChainPresets.popEdm();
        for (var stageConfig : preset.stages()) {
            AudioProcessor processor = MasteringProcessorFactory.createProcessor(
                    stageConfig, CHANNELS, SAMPLE_RATE);
            assertThat(processor).isNotNull();
        }
    }

    @Test
    void shouldCreateProcessorsFromAllPresets() {
        for (var preset : MasteringChainPresets.allDefaults()) {
            for (var stageConfig : preset.stages()) {
                AudioProcessor processor = MasteringProcessorFactory.createProcessor(
                        stageConfig, CHANNELS, SAMPLE_RATE);
                assertThat(processor).isNotNull();
            }
        }
    }

    @Test
    void shouldProcessAudioThroughCreatedChain() {
        // Build a full chain from a preset and process audio through it
        var preset = MasteringChainPresets.popEdm();
        MasteringChain chain = new MasteringChain();
        for (var stageConfig : preset.stages()) {
            AudioProcessor processor = MasteringProcessorFactory.createProcessor(
                    stageConfig, CHANNELS, SAMPLE_RATE);
            chain.addStage(stageConfig.stageType(), stageConfig.name(), processor);
        }
        chain.allocateIntermediateBuffers(CHANNELS, 4);

        float[][] input = {{0.5f, -0.3f, 0.1f, 0.0f}, {0.5f, -0.3f, 0.1f, 0.0f}};
        float[][] output = new float[2][4];

        chain.process(input, output, 4);

        // Output should be finite (no NaN or infinity) and the chain
        // should actually modify the signal
        boolean changed = false;
        for (int ch = 0; ch < 2; ch++) {
            for (int i = 0; i < 4; i++) {
                assertThat(Float.isFinite(output[ch][i])).isTrue();
                if (output[ch][i] != input[ch][i]) {
                    changed = true;
                }
            }
        }
        assertThat(changed).isTrue();
    }

    @Test
    void shouldRejectNonStereoChannelsForStereoImaging() {
        MasteringStageConfig config = MasteringStageConfig.of(
                MasteringStageType.STEREO_IMAGING, "Stereo Width",
                Map.of("width", 1.3));

        assertThatThrownBy(() -> MasteringProcessorFactory.createProcessor(config, 1, SAMPLE_RATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("STEREO_IMAGING");

        assertThatThrownBy(() -> MasteringProcessorFactory.createProcessor(config, 4, SAMPLE_RATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("STEREO_IMAGING");
    }
}
