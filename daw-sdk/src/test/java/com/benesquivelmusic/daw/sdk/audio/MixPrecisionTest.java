package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MixPrecisionTest {

    @Test
    void defaultShouldBeDouble64() {
        assertThat(MixPrecision.DEFAULT).isEqualTo(MixPrecision.DOUBLE_64);
    }

    @Test
    void bytesPerSampleShouldReportFourForFloat32() {
        assertThat(MixPrecision.FLOAT_32.bytesPerSample()).isEqualTo(Float.BYTES);
    }

    @Test
    void bytesPerSampleShouldReportEightForDouble64() {
        assertThat(MixPrecision.DOUBLE_64.bytesPerSample()).isEqualTo(Double.BYTES);
    }

    @Test
    void processDoubleDefaultAdapterShouldRoundTripFloatProcessor() {
        // A simple gain processor implemented only via the float callback —
        // calling processDouble should transparently adapt the float I/O.
        AudioProcessor gain = new AudioProcessor() {
            @Override
            public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
                for (int ch = 0; ch < inputBuffer.length; ch++) {
                    for (int f = 0; f < numFrames; f++) {
                        outputBuffer[ch][f] = inputBuffer[ch][f] * 0.5f;
                    }
                }
            }
            @Override public void reset() {}
            @Override public int getInputChannelCount() { return 1; }
            @Override public int getOutputChannelCount() { return 1; }
        };

        assertThat(gain.supportsDouble()).isFalse();

        double[][] in = {{1.0, 0.5, -0.5, 0.25}};
        double[][] out = new double[1][4];
        gain.processDouble(in, out, 4);

        assertThat(out[0]).containsExactly(0.5, 0.25, -0.25, 0.125);
    }

    @Test
    void processorThatOverridesDoubleShouldAdvertiseSupport() {
        AudioProcessor doubleAware = new AudioProcessor() {
            @Override
            public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
                // no-op for this test
            }
            @Override public void reset() {}
            @Override public int getInputChannelCount() { return 1; }
            @Override public int getOutputChannelCount() { return 1; }

            @Override public boolean supportsDouble() { return true; }

            @Override
            public void processDouble(double[][] inputBuffer, double[][] outputBuffer, int numFrames) {
                for (int ch = 0; ch < inputBuffer.length; ch++) {
                    for (int f = 0; f < numFrames; f++) {
                        outputBuffer[ch][f] = inputBuffer[ch][f] + 1.0;
                    }
                }
            }
        };

        assertThat(doubleAware.supportsDouble()).isTrue();

        double[][] in = {{0.0, -1.0, 0.5}};
        double[][] out = new double[1][3];
        doubleAware.processDouble(in, out, 3);

        assertThat(out[0]).containsExactly(1.0, 0.0, 1.5);
    }
}
