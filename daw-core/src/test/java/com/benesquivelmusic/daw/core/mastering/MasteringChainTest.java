package com.benesquivelmusic.daw.core.mastering;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.mastering.MasteringChainPreset;
import com.benesquivelmusic.daw.sdk.mastering.MasteringStageConfig;
import com.benesquivelmusic.daw.sdk.mastering.MasteringStageType;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class MasteringChainTest {

    @Test
    void shouldStartEmpty() {
        MasteringChain chain = new MasteringChain();

        assertThat(chain.isEmpty()).isTrue();
        assertThat(chain.size()).isZero();
        assertThat(chain.isChainBypassed()).isFalse();
    }

    @Test
    void shouldAddStages() {
        MasteringChain chain = new MasteringChain();
        chain.addStage(MasteringStageType.GAIN_STAGING, "Gain", new PassthroughProcessor());
        chain.addStage(MasteringStageType.COMPRESSION, "Comp", new GainProcessor(0.5f));

        assertThat(chain.size()).isEqualTo(2);
        assertThat(chain.getStages().get(0).getType()).isEqualTo(MasteringStageType.GAIN_STAGING);
        assertThat(chain.getStages().get(1).getType()).isEqualTo(MasteringStageType.COMPRESSION);
    }

    @Test
    void shouldInsertStageAtIndex() {
        MasteringChain chain = new MasteringChain();
        chain.addStage(MasteringStageType.GAIN_STAGING, "Gain", new PassthroughProcessor());
        chain.addStage(MasteringStageType.LIMITING, "Limiter", new PassthroughProcessor());
        chain.insertStage(1, MasteringStageType.COMPRESSION, "Comp", new GainProcessor(0.5f));

        assertThat(chain.getStages().get(1).getType()).isEqualTo(MasteringStageType.COMPRESSION);
    }

    @Test
    void shouldRemoveStage() {
        MasteringChain chain = new MasteringChain();
        chain.addStage(MasteringStageType.GAIN_STAGING, "Gain", new PassthroughProcessor());
        chain.addStage(MasteringStageType.COMPRESSION, "Comp", new GainProcessor(0.5f));

        MasteringChain.Stage removed = chain.removeStage(0);
        assertThat(removed.getType()).isEqualTo(MasteringStageType.GAIN_STAGING);
        assertThat(chain.size()).isEqualTo(1);
    }

    @Test
    void shouldProcessThroughChain() {
        MasteringChain chain = new MasteringChain(1);
        chain.addStage(MasteringStageType.GAIN_STAGING, "Gain", new GainProcessor(0.5f));
        chain.addStage(MasteringStageType.LIMITING, "Limiter", new GainProcessor(0.5f));
        chain.allocateIntermediateBuffers(1, 2);

        float[][] input = {{1.0f, -1.0f}};
        float[][] output = {{0.0f, 0.0f}};
        chain.process(input, output, 2);

        assertThat(output[0][0]).isEqualTo(0.25f);
        assertThat(output[0][1]).isEqualTo(-0.25f);
    }

    @Test
    void shouldBypassEntireChainForAbComparison() {
        MasteringChain chain = new MasteringChain();
        chain.addStage(MasteringStageType.LIMITING, "Limiter", new GainProcessor(0.5f));
        chain.setChainBypassed(true);

        float[][] input = {{1.0f}};
        float[][] output = {{0.0f}};
        chain.process(input, output, 1);

        // Chain bypassed — input should pass through unchanged
        assertThat(output[0][0]).isEqualTo(1.0f);
    }

    @Test
    void shouldApplyReferenceGainDuringBypass() {
        MasteringChain chain = new MasteringChain();
        chain.addStage(MasteringStageType.LIMITING, "Limiter", new GainProcessor(0.5f));
        chain.setChainBypassed(true);
        chain.setReferenceGainDb(-6.0); // approximately 0.5× gain

        float[][] input = {{1.0f}};
        float[][] output = {{0.0f}};
        chain.process(input, output, 1);

        // -6 dB ≈ 0.501
        assertThat((double) output[0][0]).isCloseTo(0.501, offset(0.01));
    }

    @Test
    void shouldBypassIndividualStage() {
        MasteringChain chain = new MasteringChain(1);
        chain.addStage(MasteringStageType.GAIN_STAGING, "Gain", new GainProcessor(0.5f));
        chain.addStage(MasteringStageType.LIMITING, "Limiter", new GainProcessor(0.5f));

        // Bypass first stage
        chain.getStages().get(0).setBypassed(true);

        float[][] input = {{1.0f}};
        float[][] output = {{0.0f}};
        chain.process(input, output, 1);

        // Only the limiter (0.5×) should be active
        assertThat(output[0][0]).isEqualTo(0.5f);
    }

    @Test
    void shouldSoloIndividualStage() {
        MasteringChain chain = new MasteringChain(1);
        chain.addStage(MasteringStageType.GAIN_STAGING, "Gain", new GainProcessor(0.5f));
        chain.addStage(MasteringStageType.COMPRESSION, "Comp", new GainProcessor(0.25f));
        chain.addStage(MasteringStageType.LIMITING, "Limiter", new GainProcessor(0.1f));

        // Solo the compression stage
        chain.getStages().get(1).setSolo(true);

        float[][] input = {{1.0f}};
        float[][] output = {{0.0f}};
        chain.process(input, output, 1);

        // Only the comp stage (0.25×) should be active
        assertThat(output[0][0]).isEqualTo(0.25f);
    }

    @Test
    void shouldPassThroughWhenEmpty() {
        MasteringChain chain = new MasteringChain();
        float[][] input = {{0.7f, -0.3f}};
        float[][] output = {{0.0f, 0.0f}};
        chain.process(input, output, 2);

        assertThat(output[0]).containsExactly(0.7f, -0.3f);
    }

    @Test
    void shouldResetAllProcessors() {
        PassthroughProcessor p1 = new PassthroughProcessor();
        PassthroughProcessor p2 = new PassthroughProcessor();
        MasteringChain chain = new MasteringChain();
        chain.addStage(MasteringStageType.GAIN_STAGING, "Gain", p1);
        chain.addStage(MasteringStageType.LIMITING, "Limiter", p2);

        chain.reset();

        assertThat(p1.resetCount).isEqualTo(1);
        assertThat(p2.resetCount).isEqualTo(1);
    }

    @Test
    void shouldProcessWithPreAllocatedBuffers() {
        MasteringChain chain = new MasteringChain(1);
        chain.addStage(MasteringStageType.GAIN_STAGING, "Gain", new GainProcessor(0.5f));
        chain.addStage(MasteringStageType.LIMITING, "Limiter", new GainProcessor(0.5f));
        chain.allocateIntermediateBuffers(1, 4);

        float[][] input = {{1.0f, 0.8f, 0.6f, 0.4f}};
        float[][] output = {{0.0f, 0.0f, 0.0f, 0.0f}};
        chain.process(input, output, 4);

        assertThat(output[0][0]).isEqualTo(0.25f);
        assertThat(output[0][1]).isEqualTo(0.2f);
    }

    @Test
    void shouldSavePresetWithParameters() {
        MasteringChain chain = new MasteringChain();
        chain.addStage(MasteringStageType.COMPRESSION, "Comp", new GainProcessor(0.5f));
        chain.getStages().get(0).setBypassed(true);

        MasteringChainPreset preset = chain.savePreset("Test Preset", "Rock",
                processor -> Map.of("gain", 0.5));

        assertThat(preset.name()).isEqualTo("Test Preset");
        assertThat(preset.genre()).isEqualTo("Rock");
        assertThat(preset.stages()).hasSize(1);

        MasteringStageConfig config = preset.stages().get(0);
        assertThat(config.stageType()).isEqualTo(MasteringStageType.COMPRESSION);
        assertThat(config.name()).isEqualTo("Comp");
        assertThat(config.parameters()).containsEntry("gain", 0.5);
        assertThat(config.bypassed()).isTrue();
    }

    @Test
    void shouldSavePresetWithoutExtractor() {
        MasteringChain chain = new MasteringChain();
        chain.addStage(MasteringStageType.GAIN_STAGING, "Gain", new PassthroughProcessor());

        MasteringChainPreset preset = chain.savePreset("Simple", "Pop/EDM");

        assertThat(preset.stages()).hasSize(1);
        assertThat(preset.stages().get(0).parameters()).isEmpty();
    }

    @Test
    void shouldRoundTripPresetData() {
        MasteringChain chain = new MasteringChain();
        chain.addStage(MasteringStageType.GAIN_STAGING, "Input Gain",
                new GainProcessor(1.0f));
        chain.addStage(MasteringStageType.COMPRESSION, "Glue Comp",
                new GainProcessor(0.5f));
        chain.getStages().get(1).setBypassed(true);

        // Save
        MasteringChainPreset preset = chain.savePreset("RT Test", "Jazz/Classical",
                p -> {
                    if (p instanceof GainProcessor gp) {
                        return Map.of("gain", (double) gp.gain());
                    }
                    return Map.of();
                });

        // Verify round-trip
        assertThat(preset.name()).isEqualTo("RT Test");
        assertThat(preset.genre()).isEqualTo("Jazz/Classical");
        assertThat(preset.stages()).hasSize(2);
        assertThat(preset.stages().get(0).parameters()).containsEntry("gain", 1.0);
        assertThat(preset.stages().get(1).parameters()).containsEntry("gain", 0.5);
        assertThat(preset.stages().get(0).bypassed()).isFalse();
        assertThat(preset.stages().get(1).bypassed()).isTrue();

        // Rebuild from preset and verify equality
        MasteringChainPreset rebuilt = new MasteringChainPreset(
                preset.name(), preset.genre(),
                preset.stages().stream()
                        .map(s -> new MasteringStageConfig(
                                s.stageType(), s.name(), s.parameters(), s.bypassed()))
                        .toList());
        assertThat(rebuilt).isEqualTo(preset);
    }

    @Test
    void shouldReturnUnmodifiableStageList() {
        MasteringChain chain = new MasteringChain();
        chain.addStage(MasteringStageType.GAIN_STAGING, "Gain", new PassthroughProcessor());

        assertThatThrownBy(() -> chain.getStages().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // --- Test processors ---

    private static class PassthroughProcessor implements AudioProcessor {
        int resetCount = 0;

        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            for (int ch = 0; ch < inputBuffer.length; ch++) {
                System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
            }
        }

        @Override
        public void reset() { resetCount++; }

        @Override
        public int getInputChannelCount() { return 1; }

        @Override
        public int getOutputChannelCount() { return 1; }
    }

    private record GainProcessor(float gain) implements AudioProcessor {
        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            for (int ch = 0; ch < inputBuffer.length; ch++) {
                for (int i = 0; i < numFrames; i++) {
                    outputBuffer[ch][i] = inputBuffer[ch][i] * gain;
                }
            }
        }

        @Override
        public void reset() {}

        @Override
        public int getInputChannelCount() { return 1; }

        @Override
        public int getOutputChannelCount() { return 1; }
    }
}
