package com.benesquivelmusic.daw.core.dsp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdaaWaveshaperTest {

    private static final org.assertj.core.data.Offset<Double> TOLERANCE =
            org.assertj.core.data.Offset.offset(1e-6);

    // --- Constructor validation ---

    @Test
    void shouldRejectZeroChannels() {
        assertThatThrownBy(() -> new AdaaWaveshaper(AdaaWaveshaper.TANH, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeChannels() {
        assertThatThrownBy(() -> new AdaaWaveshaper(AdaaWaveshaper.TANH, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullTransferFunction() {
        assertThatThrownBy(() -> new AdaaWaveshaper(null, 1))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldCreateWithValidParameters() {
        AdaaWaveshaper ws = new AdaaWaveshaper(AdaaWaveshaper.TANH, 2);
        assertThat(ws.getChannels()).isEqualTo(2);
        assertThat(ws.getTransferFunction()).isSameAs(AdaaWaveshaper.TANH);
    }

    // --- TANH transfer function ---

    @Test
    void tanhApplyShouldMatchMathTanh() {
        assertThat(AdaaWaveshaper.TANH.apply(0.0)).isCloseTo(0.0, TOLERANCE);
        assertThat(AdaaWaveshaper.TANH.apply(1.0)).isCloseTo(Math.tanh(1.0), TOLERANCE);
        assertThat(AdaaWaveshaper.TANH.apply(-1.0)).isCloseTo(Math.tanh(-1.0), TOLERANCE);
    }

    @Test
    void tanhAntiderivativeShouldBeLogCosh() {
        // F(x) = ln(cosh(x))
        assertThat(AdaaWaveshaper.TANH.antiderivative(0.0)).isCloseTo(0.0, TOLERANCE);
        assertThat(AdaaWaveshaper.TANH.antiderivative(1.0))
                .isCloseTo(Math.log(Math.cosh(1.0)), TOLERANCE);
        assertThat(AdaaWaveshaper.TANH.antiderivative(-1.0))
                .isCloseTo(Math.log(Math.cosh(-1.0)), TOLERANCE);
    }

    @Test
    void tanhAntiderivativeShouldBeStableForLargeValues() {
        // For large |x|, ln(cosh(x)) ≈ |x| - ln(2)
        double large = 50.0;
        double expected = large - Math.log(2.0);
        assertThat(AdaaWaveshaper.TANH.antiderivative(large))
                .isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-3));
        assertThat(AdaaWaveshaper.TANH.antiderivative(-large))
                .isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-3));
    }

    @Test
    void tanhAntiderivativeDerivativeShouldMatchApply() {
        // Numerical derivative of antiderivative should approximate apply()
        double h = 1e-8;
        for (double x : new double[]{-2.0, -0.5, 0.0, 0.5, 2.0}) {
            double numerical = (AdaaWaveshaper.TANH.antiderivative(x + h)
                    - AdaaWaveshaper.TANH.antiderivative(x - h)) / (2.0 * h);
            assertThat(numerical).isCloseTo(AdaaWaveshaper.TANH.apply(x),
                    org.assertj.core.data.Offset.offset(1e-4));
        }
    }

    // --- HARD_CLIP transfer function ---

    @Test
    void hardClipApplyShouldClamp() {
        assertThat(AdaaWaveshaper.HARD_CLIP.apply(0.5)).isCloseTo(0.5, TOLERANCE);
        assertThat(AdaaWaveshaper.HARD_CLIP.apply(2.0)).isCloseTo(1.0, TOLERANCE);
        assertThat(AdaaWaveshaper.HARD_CLIP.apply(-2.0)).isCloseTo(-1.0, TOLERANCE);
        assertThat(AdaaWaveshaper.HARD_CLIP.apply(0.0)).isCloseTo(0.0, TOLERANCE);
    }

    @Test
    void hardClipAntiderivativeShouldBePiecewise() {
        // |x| <= 1: F(x) = x²/2
        assertThat(AdaaWaveshaper.HARD_CLIP.antiderivative(0.0)).isCloseTo(0.0, TOLERANCE);
        assertThat(AdaaWaveshaper.HARD_CLIP.antiderivative(0.5)).isCloseTo(0.125, TOLERANCE);

        // x > 1: F(x) = x - 0.5
        assertThat(AdaaWaveshaper.HARD_CLIP.antiderivative(1.0)).isCloseTo(0.5, TOLERANCE);
        assertThat(AdaaWaveshaper.HARD_CLIP.antiderivative(2.0)).isCloseTo(1.5, TOLERANCE);

        // x < -1: F(x) = -x - 0.5
        assertThat(AdaaWaveshaper.HARD_CLIP.antiderivative(-1.0)).isCloseTo(0.5, TOLERANCE);
        assertThat(AdaaWaveshaper.HARD_CLIP.antiderivative(-2.0)).isCloseTo(1.5, TOLERANCE);
    }

    @Test
    void hardClipAntiderivativeShouldBeContinuous() {
        // Check continuity at boundaries x = ±1
        double belowPlus = AdaaWaveshaper.HARD_CLIP.antiderivative(1.0 - 1e-10);
        double atPlus = AdaaWaveshaper.HARD_CLIP.antiderivative(1.0);
        double abovePlus = AdaaWaveshaper.HARD_CLIP.antiderivative(1.0 + 1e-10);

        assertThat(belowPlus).isCloseTo(atPlus, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(abovePlus).isCloseTo(atPlus, org.assertj.core.data.Offset.offset(1e-6));

        double belowMinus = AdaaWaveshaper.HARD_CLIP.antiderivative(-1.0 + 1e-10);
        double atMinus = AdaaWaveshaper.HARD_CLIP.antiderivative(-1.0);
        double aboveMinus = AdaaWaveshaper.HARD_CLIP.antiderivative(-1.0 - 1e-10);

        assertThat(belowMinus).isCloseTo(atMinus, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(aboveMinus).isCloseTo(atMinus, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void hardClipAntiderivativeDerivativeShouldMatchApply() {
        double h = 1e-8;
        for (double x : new double[]{-2.0, -0.5, 0.0, 0.5, 2.0}) {
            double numerical = (AdaaWaveshaper.HARD_CLIP.antiderivative(x + h)
                    - AdaaWaveshaper.HARD_CLIP.antiderivative(x - h)) / (2.0 * h);
            assertThat(numerical).isCloseTo(AdaaWaveshaper.HARD_CLIP.apply(x),
                    org.assertj.core.data.Offset.offset(1e-4));
        }
    }

    // --- SOFT_CLIP transfer function ---

    @Test
    void softClipApplyShouldReturnRationalFunction() {
        assertThat(AdaaWaveshaper.SOFT_CLIP.apply(0.0)).isCloseTo(0.0, TOLERANCE);
        assertThat(AdaaWaveshaper.SOFT_CLIP.apply(1.0)).isCloseTo(0.5, TOLERANCE);
        assertThat(AdaaWaveshaper.SOFT_CLIP.apply(-1.0)).isCloseTo(-0.5, TOLERANCE);
    }

    @Test
    void softClipAntiderivativeAtZeroShouldBeZero() {
        assertThat(AdaaWaveshaper.SOFT_CLIP.antiderivative(0.0)).isCloseTo(0.0, TOLERANCE);
    }

    @Test
    void softClipAntiderivativeShouldBeEvenFunction() {
        // The antiderivative of an odd function f(x) = x/(1+|x|) is even: F(-x) = F(x)
        for (double x : new double[]{0.5, 1.0, 2.0, 5.0}) {
            assertThat(AdaaWaveshaper.SOFT_CLIP.antiderivative(-x))
                    .isCloseTo(AdaaWaveshaper.SOFT_CLIP.antiderivative(x), TOLERANCE);
        }
    }

    @Test
    void softClipAntiderivativeDerivativeShouldMatchApply() {
        double h = 1e-8;
        for (double x : new double[]{-2.0, -0.5, 0.0, 0.5, 2.0}) {
            double numerical = (AdaaWaveshaper.SOFT_CLIP.antiderivative(x + h)
                    - AdaaWaveshaper.SOFT_CLIP.antiderivative(x - h)) / (2.0 * h);
            assertThat(numerical).isCloseTo(AdaaWaveshaper.SOFT_CLIP.apply(x),
                    org.assertj.core.data.Offset.offset(1e-4));
        }
    }

    // --- ADAA processing ---

    @Test
    void processShouldMapZeroToZero() {
        AdaaWaveshaper ws = new AdaaWaveshaper(AdaaWaveshaper.TANH, 1);
        assertThat(ws.process(0.0, 0)).isCloseTo(0.0, TOLERANCE);
    }

    @Test
    void processShouldFallBackToDirectEvaluationForIdenticalInputs() {
        AdaaWaveshaper ws = new AdaaWaveshaper(AdaaWaveshaper.TANH, 1);
        // First sample initializes state
        ws.process(0.5, 0);
        // Second sample identical to first triggers L'Hôpital fallback
        double result = ws.process(0.5, 0);
        assertThat(result).isCloseTo(Math.tanh(0.5), TOLERANCE);
    }

    @Test
    void processShouldApproximateTransferFunctionForSlowlyVaryingInput() {
        // For slowly varying input, ADAA should closely approximate the direct
        // transfer function (since the finite difference approximates f(x))
        AdaaWaveshaper ws = new AdaaWaveshaper(AdaaWaveshaper.TANH, 1);
        double x0 = 0.5;
        double x1 = 0.5 + 1e-3; // very small step

        ws.process(x0, 0);
        double result = ws.process(x1, 0);

        // Should be close to tanh(x1)
        assertThat(result).isCloseTo(Math.tanh(x1),
                org.assertj.core.data.Offset.offset(1e-3));
    }

    @Test
    void processBlockShouldModifyBufferInPlace() {
        AdaaWaveshaper ws = new AdaaWaveshaper(AdaaWaveshaper.TANH, 1);
        float[] buffer = new float[256];
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] = (float) (0.8 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }

        ws.processBlock(buffer, 0, 256, 0);

        // Output should be bounded by [-1, 1] (tanh range)
        for (float sample : buffer) {
            assertThat(Math.abs(sample)).isLessThanOrEqualTo(1.0f);
        }

        // Output should have significant energy
        assertThat(rms(buffer, 0, 256)).isGreaterThan(0.1);
    }

    @Test
    void processBlockShouldRespectOffsetAndLength() {
        AdaaWaveshaper ws = new AdaaWaveshaper(AdaaWaveshaper.TANH, 1);
        float[] buffer = new float[512];
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] = 0.5f;
        }
        float[] original = buffer.clone();

        // Process only the middle portion
        ws.processBlock(buffer, 128, 256, 0);

        // Before offset should be unchanged
        for (int i = 0; i < 128; i++) {
            assertThat(buffer[i]).isEqualTo(original[i]);
        }
        // After offset+length should be unchanged
        for (int i = 384; i < 512; i++) {
            assertThat(buffer[i]).isEqualTo(original[i]);
        }
    }

    @Test
    void shouldProcessChannelsIndependently() {
        AdaaWaveshaper ws = new AdaaWaveshaper(AdaaWaveshaper.TANH, 2);

        // Feed different values to different channels
        ws.process(0.5, 0);
        ws.process(0.9, 1);

        // Subsequent processing should reflect independent history
        double ch0 = ws.process(0.6, 0);
        double ch1 = ws.process(0.1, 1);

        // Channel 0: small step from 0.5 to 0.6 — should be near tanh(0.55)
        assertThat(ch0).isCloseTo(Math.tanh(0.55),
                org.assertj.core.data.Offset.offset(0.05));

        // Channel 1: large step from 0.9 to 0.1 — ADAA formula applies
        // (F(0.1) - F(0.9)) / (0.1 - 0.9) — not close to tanh(0.1) due to averaging
        assertThat(ch1).isNotCloseTo(0.0,
                org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void resetShouldClearState() {
        AdaaWaveshaper ws = new AdaaWaveshaper(AdaaWaveshaper.TANH, 1);
        ws.process(0.9, 0);

        ws.reset();

        // After reset, the previous sample should be 0, so processing 0 should
        // return 0 (the L'Hôpital fallback for identical near-zero inputs)
        double result = ws.process(0.0, 0);
        assertThat(result).isCloseTo(0.0, TOLERANCE);
    }

    @Test
    void silenceShouldProduceSilence() {
        AdaaWaveshaper ws = new AdaaWaveshaper(AdaaWaveshaper.HARD_CLIP, 1);
        float[] buffer = new float[256]; // all zeros
        ws.processBlock(buffer, 0, 256, 0);
        for (float sample : buffer) {
            assertThat(sample).isCloseTo(0.0f,
                    org.assertj.core.data.Offset.offset(1e-6f));
        }
    }

    @Test
    void hardClipAdaaShouldBoundOutput() {
        AdaaWaveshaper ws = new AdaaWaveshaper(AdaaWaveshaper.HARD_CLIP, 1);
        float[] buffer = new float[1024];
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] = (float) (2.0 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }

        ws.processBlock(buffer, 0, 1024, 0);

        for (float sample : buffer) {
            assertThat(Math.abs(sample)).isLessThanOrEqualTo(1.1f);
        }
    }

    @Test
    void softClipAdaaShouldBoundOutput() {
        AdaaWaveshaper ws = new AdaaWaveshaper(AdaaWaveshaper.SOFT_CLIP, 1);
        float[] buffer = new float[1024];
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] = (float) (5.0 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }

        ws.processBlock(buffer, 0, 1024, 0);

        for (float sample : buffer) {
            assertThat(Math.abs(sample)).isLessThanOrEqualTo(1.1f);
        }
    }

    @Test
    void allBuiltInFunctionsShouldProcessSineWave() {
        AdaaWaveshaper.TransferFunction[] functions = {
                AdaaWaveshaper.TANH,
                AdaaWaveshaper.HARD_CLIP,
                AdaaWaveshaper.SOFT_CLIP
        };

        for (AdaaWaveshaper.TransferFunction tf : functions) {
            AdaaWaveshaper ws = new AdaaWaveshaper(tf, 1);
            float[] buffer = new float[4096];
            for (int i = 0; i < buffer.length; i++) {
                buffer[i] = (float) (0.8 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
            }

            ws.processBlock(buffer, 0, 4096, 0);

            double outputRms = rms(buffer, 0, 4096);
            assertThat(outputRms)
                    .as("Transfer function %s should produce output", tf)
                    .isGreaterThan(0.05);
        }
    }

    @Test
    void customTransferFunctionShouldWork() {
        // Custom identity function with antiderivative x²/2
        AdaaWaveshaper.TransferFunction identity = new AdaaWaveshaper.TransferFunction() {
            @Override
            public double apply(double x) {
                return x;
            }

            @Override
            public double antiderivative(double x) {
                return x * x / 2.0;
            }
        };

        AdaaWaveshaper ws = new AdaaWaveshaper(identity, 1);
        float[] buffer = new float[512];
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }
        float[] original = buffer.clone();

        ws.processBlock(buffer, 0, 512, 0);

        // For an identity function, ADAA output should closely match input
        // (the finite difference of x²/2 is (x[n] + x[n-1])/2 — a simple average)
        for (int i = 1; i < 512; i++) {
            assertThat((double) buffer[i]).isCloseTo(
                    (original[i] + original[i - 1]) / 2.0,
                    org.assertj.core.data.Offset.offset(1e-4));
        }
    }

    private static double rms(float[] buffer, int start, int end) {
        double sum = 0;
        for (int i = start; i < end; i++) {
            sum += (double) buffer[i] * buffer[i];
        }
        return Math.sqrt(sum / (end - start));
    }
}
