package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.core.dsp.BandwidthExtender.GenerationMethod;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BandwidthExtenderTest {

    private static final double SAMPLE_RATE = 48_000.0;

    @Test
    void shouldCreateWithDefaults() {
        BandwidthExtender proc = new BandwidthExtender(2, SAMPLE_RATE);
        assertThat(proc.getInputChannelCount()).isEqualTo(2);
        assertThat(proc.getOutputChannelCount()).isEqualTo(2);
        assertThat(proc.getCutoffHz()).isEqualTo(16_000.0);
        assertThat(proc.getTargetBandwidthHz()).isEqualTo(20_000.0);
        assertThat(proc.getMethod()).isEqualTo(GenerationMethod.SBR);
        assertThat(proc.getIntensity()).isEqualTo(0.5);
        assertThat(proc.getBlend()).isEqualTo(1.0);
    }

    @Test
    void shouldRejectInvalidConstructorParameters() {
        assertThatThrownBy(() -> new BandwidthExtender(0, SAMPLE_RATE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BandwidthExtender(-1, SAMPLE_RATE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BandwidthExtender(2, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BandwidthExtender(2, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidCutoffHz() {
        BandwidthExtender proc = new BandwidthExtender(1, SAMPLE_RATE);
        assertThatThrownBy(() -> proc.setCutoffHz(BandwidthExtender.MIN_CUTOFF_HZ - 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> proc.setCutoffHz(BandwidthExtender.MAX_CUTOFF_HZ + 1))
                .isInstanceOf(IllegalArgumentException.class);
        // Cutoff must be strictly less than target bandwidth.
        proc.setTargetBandwidthHz(18_000.0);
        assertThatThrownBy(() -> proc.setCutoffHz(18_000.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidTargetBandwidthHz() {
        BandwidthExtender proc = new BandwidthExtender(1, SAMPLE_RATE);
        assertThatThrownBy(() ->
                proc.setTargetBandwidthHz(BandwidthExtender.MIN_TARGET_BANDWIDTH_HZ - 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                proc.setTargetBandwidthHz(BandwidthExtender.MAX_TARGET_BANDWIDTH_HZ + 1))
                .isInstanceOf(IllegalArgumentException.class);
        // Target must be strictly greater than cutoff.
        proc.setCutoffHz(15_000.0);
        assertThatThrownBy(() -> proc.setTargetBandwidthHz(15_000.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidIntensity() {
        BandwidthExtender proc = new BandwidthExtender(1, SAMPLE_RATE);
        assertThatThrownBy(() -> proc.setIntensity(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> proc.setIntensity(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidBlend() {
        BandwidthExtender proc = new BandwidthExtender(1, SAMPLE_RATE);
        assertThatThrownBy(() -> proc.setBlend(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> proc.setBlend(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullMethod() {
        BandwidthExtender proc = new BandwidthExtender(1, SAMPLE_RATE);
        assertThatThrownBy(() -> proc.setMethod(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldPassDrySignalWithZeroBlend() {
        BandwidthExtender proc = new BandwidthExtender(1, SAMPLE_RATE);
        proc.setBlend(0.0);

        float[][] input = new float[1][512];
        float[][] output = new float[1][512];
        for (int i = 0; i < 512; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 1_000.0 * i / SAMPLE_RATE));
        }
        proc.process(input, output, 512);

        for (int i = 0; i < 512; i++) {
            assertThat(output[0][i]).isCloseTo(input[0][i],
                    org.assertj.core.data.Offset.offset(1e-6f));
        }
    }

    @Test
    void shouldPassDrySignalWithZeroIntensity() {
        BandwidthExtender proc = new BandwidthExtender(1, SAMPLE_RATE);
        proc.setIntensity(0.0);

        float[][] input = new float[1][512];
        float[][] output = new float[1][512];
        for (int i = 0; i < 512; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 1_000.0 * i / SAMPLE_RATE));
        }
        proc.process(input, output, 512);

        for (int i = 0; i < 512; i++) {
            assertThat(output[0][i]).isCloseTo(input[0][i],
                    org.assertj.core.data.Offset.offset(1e-6f));
        }
    }

    @Test
    void shouldProcessSilenceToSilence() {
        BandwidthExtender proc = new BandwidthExtender(1, SAMPLE_RATE);
        proc.setMethod(GenerationMethod.SBR);
        proc.setIntensity(1.0);

        float[][] input = new float[1][1024];
        float[][] output = new float[1][1024];
        proc.process(input, output, 1024);

        for (int i = 0; i < 1024; i++) {
            assertThat(output[0][i]).isCloseTo(0.0f,
                    org.assertj.core.data.Offset.offset(1e-6f));
        }
    }

    @Test
    void sbrShouldAddEnergyAboveCutoffForBandLimitedSource() {
        int length = 32_768;
        // Band-limited source: sum of mid-band tones, no content above 8 kHz.
        float[][] input = new float[1][length];
        for (int i = 0; i < length; i++) {
            double t = i / SAMPLE_RATE;
            input[0][i] = (float) (0.25 * Math.sin(2 * Math.PI * 440 * t)
                    + 0.25 * Math.sin(2 * Math.PI * 2_500 * t)
                    + 0.25 * Math.sin(2 * Math.PI * 6_000 * t));
        }

        BandwidthExtender proc = new BandwidthExtender(1, SAMPLE_RATE);
        proc.setCutoffHz(8_000.0);
        proc.setTargetBandwidthHz(20_000.0);
        proc.setMethod(GenerationMethod.SBR);
        proc.setIntensity(1.0);
        proc.setBlend(1.0);

        float[][] output = new float[1][length];
        proc.process(input, output, length);

        double inputHfEnergy = bandEnergy(input[0], SAMPLE_RATE, 9_000, 18_000);
        double outputHfEnergy = bandEnergy(output[0], SAMPLE_RATE, 9_000, 18_000);
        assertThat(outputHfEnergy).isGreaterThan(inputHfEnergy * 2.0);
    }

    @Test
    void allMethodsShouldProduceHighFrequencyContent() {
        int length = 16_384;
        float[][] input = new float[1][length];
        for (int i = 0; i < length; i++) {
            double t = i / SAMPLE_RATE;
            input[0][i] = (float) (0.3 * Math.sin(2 * Math.PI * 500 * t)
                    + 0.3 * Math.sin(2 * Math.PI * 3_000 * t));
        }

        for (GenerationMethod method : GenerationMethod.values()) {
            BandwidthExtender proc = new BandwidthExtender(1, SAMPLE_RATE);
            proc.setCutoffHz(8_000.0);
            proc.setTargetBandwidthHz(18_000.0);
            proc.setMethod(method);
            proc.setIntensity(1.0);
            proc.setBlend(1.0);

            float[][] output = new float[1][length];
            proc.process(input, output, length);

            double outputHf = bandEnergy(output[0], SAMPLE_RATE, 9_000, 17_000);
            double inputHf = bandEnergy(input[0], SAMPLE_RATE, 9_000, 17_000);
            assertThat(outputHf)
                    .as("method %s must produce HF content", method)
                    .isGreaterThan(inputHf);
        }
    }

    @Test
    void generatedContentShouldBeBandLimitedBelowTargetBandwidth() {
        int length = 16_384;
        float[][] input = new float[1][length];
        for (int i = 0; i < length; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2 * Math.PI * 1_000 * i / SAMPLE_RATE));
        }

        BandwidthExtender proc = new BandwidthExtender(1, SAMPLE_RATE);
        proc.setCutoffHz(6_000.0);
        proc.setTargetBandwidthHz(12_000.0);
        proc.setMethod(GenerationMethod.HARMONIC);
        proc.setIntensity(1.0);

        float[][] output = new float[1][length];
        proc.process(input, output, length);

        // Energy above 2x the target bandwidth should be negligible (allowed
        // leakage from biquad roll-off is tolerated by using a loose ratio).
        double inBand = bandEnergy(output[0], SAMPLE_RATE, 6_500, 11_500);
        double farAboveTarget = bandEnergy(output[0], SAMPLE_RATE,
                (int) (proc.getTargetBandwidthHz() * 1.5),
                (int) (SAMPLE_RATE * 0.49));
        assertThat(farAboveTarget).isLessThan(inBand);
    }

    @Test
    void intensityShouldScaleGeneratedContent() {
        int length = 16_384;
        float[][] input = new float[1][length];
        for (int i = 0; i < length; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2 * Math.PI * 2_000 * i / SAMPLE_RATE));
        }

        BandwidthExtender low = new BandwidthExtender(1, SAMPLE_RATE);
        low.setCutoffHz(8_000.0);
        low.setMethod(GenerationMethod.SBR);
        low.setIntensity(0.2);

        BandwidthExtender high = new BandwidthExtender(1, SAMPLE_RATE);
        high.setCutoffHz(8_000.0);
        high.setMethod(GenerationMethod.SBR);
        high.setIntensity(1.0);

        float[][] outputLow = new float[1][length];
        float[][] outputHigh = new float[1][length];
        low.process(input, outputLow, length);
        high.process(input, outputHigh, length);

        double hfLow = bandEnergy(outputLow[0], SAMPLE_RATE, 9_000, 18_000);
        double hfHigh = bandEnergy(outputHigh[0], SAMPLE_RATE, 9_000, 18_000);
        assertThat(hfHigh).isGreaterThan(hfLow);
    }

    @Test
    void blendShouldInterpolateBetweenDryAndExtended() {
        int length = 8_192;
        float[][] input = new float[1][length];
        for (int i = 0; i < length; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2 * Math.PI * 2_000 * i / SAMPLE_RATE));
        }

        BandwidthExtender fullWet = new BandwidthExtender(1, SAMPLE_RATE);
        fullWet.setBlend(1.0);
        fullWet.setIntensity(1.0);

        BandwidthExtender halfWet = new BandwidthExtender(1, SAMPLE_RATE);
        halfWet.setBlend(0.5);
        halfWet.setIntensity(1.0);

        float[][] outFull = new float[1][length];
        float[][] outHalf = new float[1][length];
        fullWet.process(input, outFull, length);
        halfWet.process(input, outHalf, length);

        boolean differs = false;
        for (int i = length / 2; i < length; i++) {
            if (Math.abs(outFull[0][i] - outHalf[0][i]) > 1e-4f) {
                differs = true;
                break;
            }
        }
        assertThat(differs).isTrue();
    }

    @Test
    void shouldResetState() {
        BandwidthExtender proc = new BandwidthExtender(1, SAMPLE_RATE);
        proc.setIntensity(1.0);

        float[][] input = new float[1][1024];
        float[][] output = new float[1][1024];
        for (int i = 0; i < 1024; i++) {
            input[0][i] = 0.9f;
        }
        proc.process(input, output, 1024);

        proc.reset();

        float[][] silence = new float[1][512];
        float[][] resetOut = new float[1][512];
        // NOISE mode self-generates carrier samples; for reset idempotency we
        // verify the deterministic SBR mode yields silence after reset.
        proc.setMethod(GenerationMethod.SBR);
        proc.process(silence, resetOut, 512);

        for (int i = 0; i < 512; i++) {
            assertThat(resetOut[0][i]).isCloseTo(0.0f,
                    org.assertj.core.data.Offset.offset(1e-6f));
        }
    }

    @Test
    void shouldProcessStereoChannelsIndependently() {
        BandwidthExtender proc = new BandwidthExtender(2, SAMPLE_RATE);
        proc.setIntensity(1.0);
        proc.setMethod(GenerationMethod.NOISE);

        int length = 8_192;
        float[][] input = new float[2][length];
        float[][] output = new float[2][length];
        for (int i = 0; i < length; i++) {
            input[0][i] = (float) (0.6 * Math.sin(2 * Math.PI * 1_000 * i / SAMPLE_RATE));
            input[1][i] = (float) (0.6 * Math.sin(2 * Math.PI * 2_000 * i / SAMPLE_RATE));
        }
        proc.process(input, output, length);

        // Both channels should carry energy and differ (independent noise carriers).
        double l = rms(output[0], length / 2, length);
        double r = rms(output[1], length / 2, length);
        assertThat(l).isGreaterThan(0.01);
        assertThat(r).isGreaterThan(0.01);

        boolean differ = false;
        for (int i = length / 2; i < length; i++) {
            if (Math.abs(output[0][i] - output[1][i]) > 1e-4f) {
                differ = true;
                break;
            }
        }
        assertThat(differ).isTrue();
    }

    @Test
    void shouldAcceptBoundaryParameterValues() {
        BandwidthExtender proc = new BandwidthExtender(1, SAMPLE_RATE);
        // Start with a wide target so the minimum cutoff is always valid.
        proc.setTargetBandwidthHz(BandwidthExtender.MAX_TARGET_BANDWIDTH_HZ);
        proc.setCutoffHz(BandwidthExtender.MIN_CUTOFF_HZ);
        assertThat(proc.getCutoffHz()).isEqualTo(BandwidthExtender.MIN_CUTOFF_HZ);

        // Lower the target to its minimum — still above cutoff.
        proc.setTargetBandwidthHz(BandwidthExtender.MIN_TARGET_BANDWIDTH_HZ);
        assertThat(proc.getTargetBandwidthHz())
                .isEqualTo(BandwidthExtender.MIN_TARGET_BANDWIDTH_HZ);

        // Raise the cutoff just under its max, ordering target first so
        // the cutoff<target invariant always holds.
        proc.setTargetBandwidthHz(BandwidthExtender.MAX_TARGET_BANDWIDTH_HZ);
        proc.setCutoffHz(BandwidthExtender.MAX_CUTOFF_HZ - 1);
        assertThat(proc.getCutoffHz()).isEqualTo(BandwidthExtender.MAX_CUTOFF_HZ - 1);

        proc.setIntensity(0.0);
        proc.setIntensity(1.0);
        proc.setBlend(0.0);
        proc.setBlend(1.0);
    }

    // --- Cutoff detection tests --------------------------------------------

    @Test
    void detectCutoffShouldRejectInvalidArgs() {
        assertThatThrownBy(() -> BandwidthExtender.detectCutoffHz(null, SAMPLE_RATE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BandwidthExtender.detectCutoffHz(new float[8_192], 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void detectCutoffShouldReturnMinusOneForTooShortSignal() {
        assertThat(BandwidthExtender.detectCutoffHz(new float[256], SAMPLE_RATE))
                .isEqualTo(-1.0);
    }

    @Test
    void detectCutoffShouldReturnMinusOneForSilence() {
        assertThat(BandwidthExtender.detectCutoffHz(new float[16_384], SAMPLE_RATE))
                .isEqualTo(-1.0);
    }

    @Test
    void detectCutoffShouldReturnMinusOneForBroadbandNoise() {
        int length = 32_768;
        float[] samples = new float[length];
        java.util.Random rng = new java.util.Random(42);
        for (int i = 0; i < length; i++) {
            samples[i] = (float) (rng.nextGaussian() * 0.1);
        }
        // Broadband white noise extends to Nyquist, so no sharp cliff should be found.
        double cutoff = BandwidthExtender.detectCutoffHz(samples, SAMPLE_RATE);
        assertThat(cutoff).isEqualTo(-1.0);
    }

    @Test
    void detectCutoffShouldFindSharpLowpassCliff() {
        int length = 32_768;
        float[] samples = new float[length];
        java.util.Random rng = new java.util.Random(7);
        for (int i = 0; i < length; i++) {
            samples[i] = (float) (rng.nextGaussian() * 0.1);
        }
        // Apply a very steep lowpass at ~12 kHz by cascading 8 biquads so
        // the detector sees a clear -40 dB cliff.
        double cutoffFreq = 12_000.0;
        int stages = 8;
        BiquadFilter[] stagesFilt = new BiquadFilter[stages];
        for (int s = 0; s < stages; s++) {
            stagesFilt[s] = BiquadFilter.create(
                    BiquadFilter.FilterType.LOW_PASS, SAMPLE_RATE, cutoffFreq, 0.707, 0);
        }
        for (int i = 0; i < length; i++) {
            float v = samples[i];
            for (int s = 0; s < stages; s++) {
                v = stagesFilt[s].processSample(v);
            }
            samples[i] = v;
        }

        double detected = BandwidthExtender.detectCutoffHz(samples, SAMPLE_RATE);
        // With 8 cascaded biquads the -40 dB cliff lands within ±4 kHz of
        // the design frequency — verify the detector locates it in that range.
        assertThat(detected).isGreaterThan(8_000.0).isLessThan(16_000.0);
    }

    // --- helpers ----------------------------------------------------------

    private static double rms(float[] buf, int start, int end) {
        double s = 0.0;
        for (int i = start; i < end; i++) {
            s += (double) buf[i] * buf[i];
        }
        return Math.sqrt(s / (end - start));
    }

    /**
     * Computes the energy in a frequency band via a Goertzel-bank-style DFT
     * sweep. Simple and good enough for verifying that a processor added or
     * removed energy in a spectral region.
     */
    private static double bandEnergy(float[] buf, double sampleRate,
                                     double loHz, double hiHz) {
        // Use an FFT via the processor's own FftUtils.
        int n = 1;
        while (n < buf.length) n <<= 1;
        if (n > buf.length) n >>>= 1;
        double[] real = new double[n];
        double[] imag = new double[n];
        for (int i = 0; i < n; i++) {
            real[i] = buf[i];
        }
        com.benesquivelmusic.daw.core.analysis.FftUtils.fft(real, imag);
        double binHz = sampleRate / n;
        int kLo = Math.max(1, (int) Math.floor(loHz / binHz));
        int kHi = Math.min(n / 2 - 1, (int) Math.ceil(hiHz / binHz));
        double sum = 0.0;
        for (int k = kLo; k <= kHi; k++) {
            sum += real[k] * real[k] + imag[k] * imag[k];
        }
        return sum;
    }
}
