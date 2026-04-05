package com.benesquivelmusic.daw.core.spatial.binaural;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class PartitionedConvolverTest {

    private static final int BLOCK_SIZE = 64;

    @Test
    void shouldConvolveWithDeltaFunction() {
        // Convolving with a delta function should produce the original signal (delayed by one block)
        float[] deltaIr = new float[BLOCK_SIZE];
        deltaIr[0] = 1.0f;
        PartitionedConvolver convolver = new PartitionedConvolver(deltaIr, BLOCK_SIZE);

        float[] input = new float[BLOCK_SIZE];
        for (int i = 0; i < BLOCK_SIZE; i++) {
            input[i] = (float) Math.sin(2.0 * Math.PI * i / BLOCK_SIZE);
        }

        // First block: pump in zeros to prime the delay line
        float[] zeros = new float[BLOCK_SIZE];
        float[] output1 = new float[BLOCK_SIZE];
        convolver.processBlock(zeros, output1, 0, 0);

        // Second block: process the actual signal
        float[] output2 = new float[BLOCK_SIZE];
        convolver.processBlock(input, output2, 0, 0);

        // Output should match the input (delta convolution = identity)
        for (int i = 0; i < BLOCK_SIZE; i++) {
            assertThat(output2[i]).isCloseTo(input[i], within(1e-5f));
        }
    }

    @Test
    void shouldApplyScaling() {
        // IR is a scaled delta: should scale the signal
        float[] scaledIr = new float[BLOCK_SIZE];
        scaledIr[0] = 0.5f;
        PartitionedConvolver convolver = new PartitionedConvolver(scaledIr, BLOCK_SIZE);

        float[] input = new float[BLOCK_SIZE];
        for (int i = 0; i < BLOCK_SIZE; i++) {
            input[i] = 1.0f; // DC signal
        }

        float[] primeOutput = new float[BLOCK_SIZE];
        convolver.processBlock(new float[BLOCK_SIZE], primeOutput, 0, 0);

        float[] output = new float[BLOCK_SIZE];
        convolver.processBlock(input, output, 0, 0);

        // Output should be 0.5 × input
        for (int i = 0; i < BLOCK_SIZE; i++) {
            assertThat(output[i]).isCloseTo(0.5f, within(0.01f));
        }
    }

    @Test
    void shouldHandleMultiPartitionIr() {
        // IR longer than blockSize → multiple partitions
        float[] longIr = new float[BLOCK_SIZE * 3];
        longIr[0] = 1.0f; // delta at start

        PartitionedConvolver convolver = new PartitionedConvolver(longIr, BLOCK_SIZE);

        float[] input = new float[BLOCK_SIZE];
        input[0] = 1.0f;

        // Process several blocks
        float[] out1 = new float[BLOCK_SIZE];
        float[] out2 = new float[BLOCK_SIZE];
        float[] out3 = new float[BLOCK_SIZE];
        float[] out4 = new float[BLOCK_SIZE];

        convolver.processBlock(new float[BLOCK_SIZE], out1, 0, 0); // prime
        convolver.processBlock(input, out2, 0, 0);
        convolver.processBlock(new float[BLOCK_SIZE], out3, 0, 0);
        convolver.processBlock(new float[BLOCK_SIZE], out4, 0, 0);

        // The impulse should appear in out2
        assertThat(out2[0]).isCloseTo(1.0f, within(0.01f));
    }

    @Test
    void shouldResetState() {
        float[] ir = new float[BLOCK_SIZE];
        ir[0] = 1.0f;
        PartitionedConvolver convolver = new PartitionedConvolver(ir, BLOCK_SIZE);

        float[] input = new float[BLOCK_SIZE];
        input[0] = 1.0f;
        float[] output = new float[BLOCK_SIZE];
        convolver.processBlock(input, output, 0, 0);

        convolver.reset();

        // After reset, processing zeros should produce zeros
        float[] zeros = new float[BLOCK_SIZE];
        float[] afterReset = new float[BLOCK_SIZE];
        convolver.processBlock(zeros, afterReset, 0, 0);

        for (int i = 0; i < BLOCK_SIZE; i++) {
            assertThat(afterReset[i]).isCloseTo(0.0f, within(1e-6f));
        }
    }

    @Test
    void shouldRejectNullIr() {
        assertThatThrownBy(() -> new PartitionedConvolver(null, BLOCK_SIZE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectEmptyIr() {
        assertThatThrownBy(() -> new PartitionedConvolver(new float[0], BLOCK_SIZE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNonPowerOfTwoBlockSize() {
        float[] ir = {1.0f};
        assertThatThrownBy(() -> new PartitionedConvolver(ir, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("power of 2");
    }

    @Test
    void shouldReturnCorrectBlockSize() {
        float[] ir = {1.0f};
        PartitionedConvolver convolver = new PartitionedConvolver(ir, 256);
        assertThat(convolver.getBlockSize()).isEqualTo(256);
    }

    @Test
    void shouldVerifyFftRoundTrip() {
        // Verify FFT → IFFT round-trip preserves the signal
        int n = 128;
        double[] real = new double[n];
        double[] imag = new double[n];
        for (int i = 0; i < n; i++) {
            real[i] = Math.sin(2.0 * Math.PI * 3 * i / n) + 0.5 * Math.cos(2.0 * Math.PI * 7 * i / n);
        }
        double[] originalReal = real.clone();

        PartitionedConvolver.fft(real, imag, false);
        PartitionedConvolver.fft(real, imag, true);

        for (int i = 0; i < n; i++) {
            assertThat(real[i]).isCloseTo(originalReal[i], within(1e-10));
            assertThat(imag[i]).isCloseTo(0.0, within(1e-10));
        }
    }
}
