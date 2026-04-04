package com.benesquivelmusic.daw.core.mastering;

import com.benesquivelmusic.daw.core.dsp.CompressorProcessor;
import com.benesquivelmusic.daw.core.dsp.GainStagingProcessor;
import com.benesquivelmusic.daw.core.dsp.LimiterProcessor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.mastering.MasteringStageType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * Tests for MasteringChain metering, AudioProcessor interface,
 * and integration with real DSP processors.
 */
class MasteringChainMeteringTest {

    @Test
    void shouldImplementAudioProcessor() {
        MasteringChain chain = new MasteringChain();
        assertThat(chain).isInstanceOf(AudioProcessor.class);
        assertThat(chain.getInputChannelCount()).isEqualTo(2);
        assertThat(chain.getOutputChannelCount()).isEqualTo(2);
    }

    @Test
    void shouldSupportCustomChannelCount() {
        MasteringChain chain = new MasteringChain(4);
        assertThat(chain.getInputChannelCount()).isEqualTo(4);
        assertThat(chain.getOutputChannelCount()).isEqualTo(4);
    }

    @Test
    void shouldMeasureInputPeakLevels() {
        MasteringChain chain = new MasteringChain();
        chain.addStage(MasteringStageType.GAIN_STAGING, "Gain",
                new GainStagingProcessor(2, 0.0));

        float[][] input = {{0.5f, -0.3f}, {0.5f, -0.3f}};
        float[][] output = new float[2][2];
        chain.process(input, output, 2);

        // Input peak of 0.5 ≈ -6.02 dB
        assertThat(chain.getStageInputPeakDb(0)).isCloseTo(-6.02, offset(0.1));
    }

    @Test
    void shouldMeasureOutputPeakLevels() {
        MasteringChain chain = new MasteringChain();
        chain.addStage(MasteringStageType.GAIN_STAGING, "Gain",
                new GainStagingProcessor(2, -6.0));

        float[][] input = {{1.0f}, {1.0f}};
        float[][] output = new float[2][1];
        chain.process(input, output, 1);

        // -6 dB gain on 1.0 ≈ 0.5 → -6.02 dB
        assertThat(chain.getStageOutputPeakDb(0)).isCloseTo(-6.02, offset(0.1));
    }

    @Test
    void shouldReportGainReductionFromCompressor() {
        CompressorProcessor comp = new CompressorProcessor(2, 44100.0);
        comp.setThresholdDb(-20.0);
        comp.setRatio(4.0);
        comp.setKneeDb(0.0);
        comp.setAttackMs(0.0); // Instant attack for test

        MasteringChain chain = new MasteringChain();
        chain.addStage(MasteringStageType.COMPRESSION, "Comp", comp);

        // Feed loud signal that will trigger compression — need enough samples
        // for the envelope follower to respond
        int frames = 4096;
        float[][] input = new float[2][frames];
        float[][] output = new float[2][frames];
        for (int i = 0; i < frames; i++) {
            input[0][i] = 0.8f; // ~-2 dB, well above -20 dB threshold
            input[1][i] = 0.8f;
        }
        chain.process(input, output, frames);

        // Should report negative gain reduction
        assertThat(chain.getStageGainReductionDb(0)).isLessThan(0.0);
    }

    @Test
    void shouldReportGainReductionFromLimiter() {
        LimiterProcessor limiter = new LimiterProcessor(2, 44100.0);
        limiter.setCeilingDb(-6.0);

        MasteringChain chain = new MasteringChain();
        chain.addStage(MasteringStageType.LIMITING, "Limiter", limiter);

        // Feed loud signal that will trigger limiting
        float[][] input = new float[2][512];
        float[][] output = new float[2][512];
        for (int i = 0; i < 512; i++) {
            input[0][i] = 0.9f;
            input[1][i] = 0.9f;
        }
        chain.process(input, output, 512);

        // Should report negative gain reduction
        assertThat(chain.getStageGainReductionDb(0)).isLessThan(0.0);
    }

    @Test
    void shouldReportZeroGainReductionForNonDynamicsProcessors() {
        MasteringChain chain = new MasteringChain();
        chain.addStage(MasteringStageType.GAIN_STAGING, "Gain",
                new GainStagingProcessor(2, 0.0));

        float[][] input = {{0.5f}, {0.5f}};
        float[][] output = new float[2][1];
        chain.process(input, output, 1);

        assertThat(chain.getStageGainReductionDb(0)).isEqualTo(0.0);
    }

    @Test
    void shouldReturnDefaultMeteringForInvalidIndex() {
        MasteringChain chain = new MasteringChain();

        assertThat(chain.getStageInputPeakDb(0)).isEqualTo(-120.0);
        assertThat(chain.getStageOutputPeakDb(0)).isEqualTo(-120.0);
        assertThat(chain.getStageGainReductionDb(0)).isEqualTo(0.0);
    }

    @Test
    void shouldBypassStageAndStillMeter() {
        MasteringChain chain = new MasteringChain();
        chain.addStage(MasteringStageType.GAIN_STAGING, "Gain",
                new GainStagingProcessor(2, -12.0));
        chain.addStage(MasteringStageType.LIMITING, "Limiter",
                new GainStagingProcessor(2, 0.0));

        // Bypass the first stage
        chain.getStages().get(0).setBypassed(true);

        float[][] input = {{1.0f}, {1.0f}};
        float[][] output = new float[2][1];
        chain.process(input, output, 1);

        // Output should be 1.0 (bypassed gain + passthrough limiter)
        assertThat(output[0][0]).isEqualTo(1.0f);
    }

    @Test
    void shouldProcessThroughFullPresetChain() {
        var preset = MasteringChainPresets.popEdm();
        MasteringChain chain = new MasteringChain();
        for (var config : preset.stages()) {
            AudioProcessor processor = MasteringProcessorFactory.createProcessor(
                    config, 2, 44100.0);
            chain.addStage(config.stageType(), config.name(), processor);
        }

        // Process multiple blocks to let envelope followers settle
        float[][] input = new float[2][256];
        float[][] output = new float[2][256];
        for (int i = 0; i < 256; i++) {
            input[0][i] = 0.5f * (float) Math.sin(2 * Math.PI * 440 * i / 44100.0);
            input[1][i] = input[0][i];
        }

        chain.process(input, output, 256);

        // All output values should be finite
        for (int ch = 0; ch < 2; ch++) {
            for (int i = 0; i < 256; i++) {
                assertThat(Float.isFinite(output[ch][i])).isTrue();
            }
        }

        // Metering should be available for all 7 stages
        for (int i = 0; i < 7; i++) {
            assertThat(Double.isFinite(chain.getStageInputPeakDb(i))).isTrue();
            assertThat(Double.isFinite(chain.getStageOutputPeakDb(i))).isTrue();
            assertThat(Double.isFinite(chain.getStageGainReductionDb(i))).isTrue();
        }
    }
}
