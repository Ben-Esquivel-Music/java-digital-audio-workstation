package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AudioProcessorTest {

    @Test
    void shouldImplementPassthroughProcessor() {
        AudioProcessor passthrough = new AudioProcessor() {
            @Override
            public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
                for (int ch = 0; ch < inputBuffer.length; ch++) {
                    System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
                }
            }

            @Override
            public void reset() {
                // no state to reset
            }

            @Override
            public int getInputChannelCount() {
                return 2;
            }

            @Override
            public int getOutputChannelCount() {
                return 2;
            }
        };

        float[][] input = {{0.5f, -0.5f}, {0.25f, -0.25f}};
        float[][] output = new float[2][2];

        passthrough.process(input, output, 2);

        assertThat(output[0]).containsExactly(0.5f, -0.5f);
        assertThat(output[1]).containsExactly(0.25f, -0.25f);
        assertThat(passthrough.getInputChannelCount()).isEqualTo(2);
        assertThat(passthrough.getOutputChannelCount()).isEqualTo(2);
    }
}
