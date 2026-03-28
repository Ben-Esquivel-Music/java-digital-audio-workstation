package com.benesquivelmusic.daw.core.spatial.ambisonics;

import com.benesquivelmusic.daw.sdk.spatial.AmbisonicOrder;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class AmbisonicEnhancerTest {

    private static final double TOLERANCE = 1e-3;

    // Use a buffer size large enough for at least one full STFT frame with default FFT size (1024)
    private static final int FFT_SIZE = 1024;
    private static final int NUM_FRAMES = FFT_SIZE * 4;

    // ---- Construction ----

    @Test
    void shouldCreateWithDefaults() {
        AmbisonicEnhancer enhancer = new AmbisonicEnhancer();
        assertThat(enhancer.getFftSize()).isEqualTo(1024);
        assertThat(enhancer.getEnhancementStrength()).isCloseTo(1.0, within(1e-10));
        assertThat(enhancer.getDirectDiffuseThreshold()).isCloseTo(0.5, within(1e-10));
        assertThat(enhancer.getTemporalSmoothing()).isCloseTo(0.8, within(1e-10));
        assertThat(enhancer.getInputChannelCount()).isEqualTo(4);
        assertThat(enhancer.getOutputChannelCount()).isEqualTo(4);
    }

    @Test
    void shouldCreateWithCustomParameters() {
        AmbisonicEnhancer enhancer = new AmbisonicEnhancer(256, 0.5, 0.3, 0.9);
        assertThat(enhancer.getFftSize()).isEqualTo(256);
        assertThat(enhancer.getEnhancementStrength()).isCloseTo(0.5, within(1e-10));
        assertThat(enhancer.getDirectDiffuseThreshold()).isCloseTo(0.3, within(1e-10));
        assertThat(enhancer.getTemporalSmoothing()).isCloseTo(0.9, within(1e-10));
    }

    @Test
    void shouldRejectInvalidFftSize() {
        assertThatThrownBy(() -> new AmbisonicEnhancer(100, 1.0, 0.5, 0.8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AmbisonicEnhancer(32, 1.0, 0.5, 0.8))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidEnhancementStrength() {
        assertThatThrownBy(() -> new AmbisonicEnhancer(256, -0.1, 0.5, 0.8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AmbisonicEnhancer(256, 1.1, 0.5, 0.8))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidThreshold() {
        assertThatThrownBy(() -> new AmbisonicEnhancer(256, 1.0, -0.1, 0.8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AmbisonicEnhancer(256, 1.0, 1.1, 0.8))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidTemporalSmoothing() {
        assertThatThrownBy(() -> new AmbisonicEnhancer(256, 1.0, 0.5, -0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AmbisonicEnhancer(256, 1.0, 0.5, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Parameter Setters ----

    @Test
    void shouldUpdateEnhancementStrength() {
        AmbisonicEnhancer enhancer = new AmbisonicEnhancer();
        enhancer.setEnhancementStrength(0.3);
        assertThat(enhancer.getEnhancementStrength()).isCloseTo(0.3, within(1e-10));
    }

    @Test
    void shouldRejectInvalidEnhancementStrengthUpdate() {
        AmbisonicEnhancer enhancer = new AmbisonicEnhancer();
        assertThatThrownBy(() -> enhancer.setEnhancementStrength(1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldUpdateDirectDiffuseThreshold() {
        AmbisonicEnhancer enhancer = new AmbisonicEnhancer();
        enhancer.setDirectDiffuseThreshold(0.7);
        assertThat(enhancer.getDirectDiffuseThreshold()).isCloseTo(0.7, within(1e-10));
    }

    @Test
    void shouldRejectInvalidThresholdUpdate() {
        AmbisonicEnhancer enhancer = new AmbisonicEnhancer();
        assertThatThrownBy(() -> enhancer.setDirectDiffuseThreshold(-0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldUpdateTemporalSmoothing() {
        AmbisonicEnhancer enhancer = new AmbisonicEnhancer();
        enhancer.setTemporalSmoothing(0.5);
        assertThat(enhancer.getTemporalSmoothing()).isCloseTo(0.5, within(1e-10));
    }

    @Test
    void shouldRejectInvalidTemporalSmoothingUpdate() {
        AmbisonicEnhancer enhancer = new AmbisonicEnhancer();
        assertThatThrownBy(() -> enhancer.setTemporalSmoothing(1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Processing ----

    @Test
    void shouldProduceSilenceFromSilentInput() {
        AmbisonicEnhancer enhancer = new AmbisonicEnhancer(64, 1.0, 0.5, 0.0);

        float[][] input = new float[4][NUM_FRAMES];
        float[][] output = new float[4][NUM_FRAMES];
        enhancer.process(input, output, NUM_FRAMES);

        for (float[] ch : output) {
            for (float v : ch) {
                assertThat((double) v).isCloseTo(0.0, within(TOLERANCE));
            }
        }
    }

    @Test
    void shouldPassthroughWithZeroEnhancement() {
        int fft = 64;
        int frames = fft * 8;
        AmbisonicEnhancer enhancer = new AmbisonicEnhancer(fft, 0.0, 0.5, 0.0);

        // Create a directional FOA signal
        AmbisonicEncoder encoder = new AmbisonicEncoder(AmbisonicOrder.FIRST);
        encoder.setDirection(0, 0);

        float[][] monoInput = {sineBuffer(440.0f, 48000, frames)};
        float[][] foaBuffer = new float[4][frames];
        encoder.process(monoInput, foaBuffer, frames);

        float[][] output = new float[4][frames];
        enhancer.process(foaBuffer, output, frames);

        // With zero enhancement, the mask is approximately 1.0 for all bins,
        // so the output should be close to the input (modulo STFT windowing effects)
        // Skip leading/trailing frames for STFT settling
        int skip = fft * 2;
        for (int ch = 0; ch < 4; ch++) {
            double inputEnergy = energy(foaBuffer[ch], skip, frames - skip);
            double outputEnergy = energy(output[ch], skip, frames - skip);
            if (inputEnergy > 1e-10) {
                double ratio = outputEnergy / inputEnergy;
                assertThat(ratio).as("channel %d energy ratio", ch)
                        .isBetween(0.3, 3.0);
            }
        }
    }

    @Test
    void shouldEnhanceDirectionalSignal() {
        int fft = 64;
        int frames = fft * 8;
        // Strong enhancement, low threshold, no smoothing for deterministic test
        AmbisonicEnhancer enhancer = new AmbisonicEnhancer(fft, 1.0, 0.2, 0.0);

        // Create a strongly directional FOA signal from the front
        AmbisonicEncoder encoder = new AmbisonicEncoder(AmbisonicOrder.FIRST);
        encoder.setDirection(0, 0);

        float[][] monoInput = {sineBuffer(440.0f, 48000, frames)};
        float[][] foaBuffer = new float[4][frames];
        encoder.process(monoInput, foaBuffer, frames);

        float[][] output = new float[4][frames];
        enhancer.process(foaBuffer, output, frames);

        // Enhanced output should have significant energy in the directional channels
        int skip = fft * 2;
        double outputEnergy = 0;
        for (int ch = 0; ch < 4; ch++) {
            outputEnergy += energy(output[ch], skip, frames - skip);
        }
        assertThat(outputEnergy).isGreaterThan(0.0);
    }

    @Test
    void shouldPreserveFourChannelLayout() {
        AmbisonicEnhancer enhancer = new AmbisonicEnhancer();
        assertThat(enhancer.getInputChannelCount()).isEqualTo(4);
        assertThat(enhancer.getOutputChannelCount()).isEqualTo(4);
    }

    // ---- Reset ----

    @Test
    void shouldResetState() {
        int fft = 64;
        int frames = fft * 4;
        AmbisonicEnhancer enhancer = new AmbisonicEnhancer(fft, 1.0, 0.5, 0.5);

        // Process some data to modify internal state
        float[][] input = new float[4][frames];
        Arrays.fill(input[0], 0.5f);
        Arrays.fill(input[3], 0.5f);
        float[][] output = new float[4][frames];
        enhancer.process(input, output, frames);

        enhancer.reset();

        // After reset, processing silence should produce silence
        float[][] silentInput = new float[4][frames];
        float[][] silentOutput = new float[4][frames];
        enhancer.process(silentInput, silentOutput, frames);

        for (float[] ch : silentOutput) {
            for (float v : ch) {
                assertThat((double) v).isCloseTo(0.0, within(TOLERANCE));
            }
        }
    }

    @Test
    void shouldProcessAfterReset() {
        int fft = 64;
        int frames = fft * 4;
        AmbisonicEnhancer enhancer = new AmbisonicEnhancer(fft, 1.0, 0.5, 0.0);

        float[][] input = new float[4][frames];
        Arrays.fill(input[0], 0.5f);
        Arrays.fill(input[3], 0.5f);
        float[][] output = new float[4][frames];
        enhancer.process(input, output, frames);

        enhancer.reset();

        // Should not throw after reset
        enhancer.process(input, output, frames);
    }

    // ---- Edge Cases ----

    @Test
    void shouldHandleSmallBufferSize() {
        int fft = 64;
        AmbisonicEnhancer enhancer = new AmbisonicEnhancer(fft, 1.0, 0.5, 0.0);

        // Process a buffer smaller than FFT size
        int frames = 32;
        float[][] input = new float[4][frames];
        Arrays.fill(input[0], 0.5f);
        float[][] output = new float[4][frames];

        // Should not throw
        enhancer.process(input, output, frames);
    }

    @Test
    void shouldHandleMultipleConsecutiveProcessCalls() {
        int fft = 64;
        int frames = fft * 2;
        AmbisonicEnhancer enhancer = new AmbisonicEnhancer(fft, 0.5, 0.5, 0.5);

        AmbisonicEncoder encoder = new AmbisonicEncoder(AmbisonicOrder.FIRST);
        encoder.setDirection(Math.PI / 4.0, 0);

        float[][] monoInput = {sineBuffer(220.0f, 48000, frames)};
        float[][] foaBuffer = new float[4][frames];
        encoder.process(monoInput, foaBuffer, frames);

        float[][] output = new float[4][frames];

        // Multiple calls should not throw or produce NaN
        for (int call = 0; call < 5; call++) {
            enhancer.process(foaBuffer, output, frames);
            for (int ch = 0; ch < 4; ch++) {
                for (int i = 0; i < frames; i++) {
                    assertThat(Float.isNaN(output[ch][i]))
                            .as("NaN at call=%d ch=%d frame=%d", call, ch, i)
                            .isFalse();
                    assertThat(Float.isInfinite(output[ch][i]))
                            .as("Inf at call=%d ch=%d frame=%d", call, ch, i)
                            .isFalse();
                }
            }
        }
    }

    @Test
    void shouldAcceptMinimumValidFftSize() {
        AmbisonicEnhancer enhancer = new AmbisonicEnhancer(64, 1.0, 0.5, 0.0);
        assertThat(enhancer.getFftSize()).isEqualTo(64);
    }

    @Test
    void shouldAcceptBoundaryParameterValues() {
        // All boundary values should be accepted
        AmbisonicEnhancer enhancer = new AmbisonicEnhancer(64, 0.0, 0.0, 0.0);
        assertThat(enhancer.getEnhancementStrength()).isCloseTo(0.0, within(1e-10));
        assertThat(enhancer.getDirectDiffuseThreshold()).isCloseTo(0.0, within(1e-10));
        assertThat(enhancer.getTemporalSmoothing()).isCloseTo(0.0, within(1e-10));

        AmbisonicEnhancer enhancer2 = new AmbisonicEnhancer(64, 1.0, 1.0, 0.99);
        assertThat(enhancer2.getEnhancementStrength()).isCloseTo(1.0, within(1e-10));
        assertThat(enhancer2.getDirectDiffuseThreshold()).isCloseTo(1.0, within(1e-10));
        assertThat(enhancer2.getTemporalSmoothing()).isCloseTo(0.99, within(1e-10));
    }

    // ---- Helpers ----

    private static float[] sineBuffer(float frequencyHz, int sampleRate, int size) {
        float[] buffer = new float[size];
        for (int i = 0; i < size; i++) {
            buffer[i] = (float) Math.sin(2.0 * Math.PI * frequencyHz * i / sampleRate);
        }
        return buffer;
    }

    private static double energy(float[] buffer, int start, int end) {
        double e = 0;
        for (int i = start; i < end; i++) {
            e += buffer[i] * buffer[i];
        }
        return e;
    }
}
