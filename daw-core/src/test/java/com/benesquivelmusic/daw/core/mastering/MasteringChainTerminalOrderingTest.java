package com.benesquivelmusic.daw.core.mastering;

import com.benesquivelmusic.daw.core.dsp.mastering.DitherProcessor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.mastering.MasteringStageType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates that {@link MasteringChain} enforces terminal-stage ordering:
 * a terminal stage (e.g. {@link DitherProcessor dither}) must always be the
 * very last stage of the chain. Inserting a non-terminal stage after the
 * terminal stage — or a second terminal stage anywhere — is rejected with
 * an {@link IllegalStateException}.
 */
class MasteringChainTerminalOrderingTest {

    @Test
    void appendingAfterTerminalStageIsRejected() {
        MasteringChain chain = new MasteringChain(2);
        chain.addStage(MasteringStageType.LIMITING, "Limiter", new PassthroughProcessor(2));
        chain.addStage(MasteringStageType.DITHERING, "Dither",
                new DitherProcessor(2, 16));
        assertThatThrownBy(() -> chain.addStage(
                MasteringStageType.LIMITING, "Late Limiter", new PassthroughProcessor(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal");
    }

    @Test
    void insertingAfterTerminalStageIsRejected() {
        MasteringChain chain = new MasteringChain(2);
        chain.addStage(MasteringStageType.GAIN_STAGING, "Gain",
                new PassthroughProcessor(2));
        chain.addStage(MasteringStageType.DITHERING, "Dither",
                new DitherProcessor(2, 16));
        assertThatThrownBy(() -> chain.insertStage(
                2, MasteringStageType.LIMITING, "After Dither",
                new PassthroughProcessor(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void insertingBeforeTerminalStageIsAllowed() {
        MasteringChain chain = new MasteringChain(2);
        chain.addStage(MasteringStageType.DITHERING, "Dither",
                new DitherProcessor(2, 16));
        // Insert at the front — fine.
        chain.insertStage(0, MasteringStageType.LIMITING, "Limiter",
                new PassthroughProcessor(2));
        assertThat(chain.size()).isEqualTo(2);
        assertThat(chain.getStages().getFirst().getType())
                .isEqualTo(MasteringStageType.LIMITING);
        assertThat(chain.getStages().getLast().isTerminal()).isTrue();
    }

    @Test
    void appendingASecondTerminalStageIsRejected() {
        MasteringChain chain = new MasteringChain(2);
        chain.addStage(MasteringStageType.DITHERING, "Dither A",
                new DitherProcessor(2, 16));
        // The "DITHERING" stage type is terminal by default — adding a second
        // terminal stage is forbidden because nothing may follow a terminal
        // stage and only one terminal stage is allowed.
        assertThatThrownBy(() -> chain.addStage(
                MasteringStageType.DITHERING, "Dither B", new DitherProcessor(2, 16)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void terminalFlagDefaultsToTrueForDitheringStageType() {
        MasteringChain chain = new MasteringChain(2);
        chain.addStage(MasteringStageType.DITHERING, "Dither",
                new DitherProcessor(2, 16));
        assertThat(chain.getStages().getFirst().isTerminal()).isTrue();
    }

    @Test
    void terminalFlagDefaultsToFalseForOtherStageTypes() {
        MasteringChain chain = new MasteringChain(2);
        chain.addStage(MasteringStageType.LIMITING, "Limiter",
                new PassthroughProcessor(2));
        assertThat(chain.getStages().getFirst().isTerminal()).isFalse();
    }

    @Test
    void explicitTerminalFalseAllowsAppendAfterDithering() {
        MasteringChain chain = new MasteringChain(2);
        // Caller chooses to mark the dither stage as non-terminal — they
        // accept responsibility for any subsequent stages they add.
        chain.addStage(MasteringStageType.DITHERING, "Dither",
                new DitherProcessor(2, 16), /* terminal */ false);
        chain.addStage(MasteringStageType.LIMITING, "Late Limiter",
                new PassthroughProcessor(2));
        assertThat(chain.size()).isEqualTo(2);
    }

    @Test
    void terminalStageMustBeInsertedAtEnd() {
        MasteringChain chain = new MasteringChain(2);
        chain.addStage(MasteringStageType.LIMITING, "Limiter",
                new PassthroughProcessor(2));
        // Insert terminal at index 0 → should fail (must be the last stage).
        assertThatThrownBy(() -> chain.insertStage(
                0, MasteringStageType.DITHERING, "Dither",
                new DitherProcessor(2, 16), /* terminal */ true))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Tiny pass-through processor for ordering tests. */
    private static final class PassthroughProcessor implements AudioProcessor {
        private final int channels;
        PassthroughProcessor(int channels) { this.channels = channels; }
        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            for (int ch = 0; ch < Math.min(inputBuffer.length, outputBuffer.length); ch++) {
                System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
            }
        }
        @Override public void reset() {}
        @Override public int getInputChannelCount() { return channels; }
        @Override public int getOutputChannelCount() { return channels; }
    }
}
