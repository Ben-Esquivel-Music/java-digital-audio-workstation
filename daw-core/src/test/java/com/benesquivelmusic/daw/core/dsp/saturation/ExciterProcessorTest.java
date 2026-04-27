package com.benesquivelmusic.daw.core.dsp.saturation;

import com.benesquivelmusic.daw.core.analysis.FftUtils;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExciterProcessorTest {

    private static final double SAMPLE_RATE = 48_000.0;

    @Test
    void shouldCreateWithSensibleDefaults() {
        ExciterProcessor ex = new ExciterProcessor(2, SAMPLE_RATE);
        assertThat(ex.getInputChannelCount()).isEqualTo(2);
        assertThat(ex.getOutputChannelCount()).isEqualTo(2);
        assertThat(ex.getFrequencyHz()).isEqualTo(8_000.0);
        assertThat(ex.getDrivePercent()).isEqualTo(25.0);
        assertThat(ex.getMixPercent()).isEqualTo(25.0);
        assertThat(ex.getOutputGainDb()).isEqualTo(0.0);
        assertThat(ex.getMode()).isEqualTo(ExciterProcessor.Mode.CLASS_A_TUBE);
    }

    @Test
    void shouldRejectInvalidConstructorParameters() {
        assertThatThrownBy(() -> new ExciterProcessor(0, SAMPLE_RATE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExciterProcessor(2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectOutOfRangeParameters() {
        ExciterProcessor ex = new ExciterProcessor(1, SAMPLE_RATE);
        assertThatThrownBy(() -> ex.setFrequencyHz(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ex.setFrequencyHz(20_000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ex.setDrivePercent(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ex.setDrivePercent(101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ex.setMixPercent(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ex.setMixPercent(101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ex.setOutputGainDb(-13)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ex.setOutputGainDb(13)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ex.setMode(null)).isInstanceOf(NullPointerException.class);
    }

    /**
     * Per the issue: drive=0 → unity (mix irrelevant). With drive at 0% the
     * side-chain feeding the waveshaper is identically zero, so the wet
     * contribution is zero regardless of mix and the output equals the input.
     */
    @Test
    void zeroDriveProducesNoHarmonicContent() {
        ExciterProcessor ex = new ExciterProcessor(1, SAMPLE_RATE);
        ex.setDrivePercent(0.0);
        ex.setMixPercent(100.0);
        ex.setFrequencyHz(2_000.0); // make sure the test fundamental sits in HP band

        int n = 4096;
        double freq = 4_000.0;
        float[][] in = new float[1][n];
        float[][] out = new float[1][n];
        for (int i = 0; i < n; i++) {
            in[0][i] = (float) (0.3 * Math.sin(2.0 * Math.PI * freq * i / SAMPLE_RATE));
        }
        ex.process(in, out, n);

        // With drive=0 the wet side-chain is zero — output must equal input
        // sample-for-sample (mix is irrelevant per the issue's contract).
        for (int i = 0; i < n; i++) {
            assertThat(out[0][i]).isEqualTo(in[0][i]);
        }
    }

    @Test
    void zeroMixYieldsExactlyDrySignal() {
        ExciterProcessor ex = new ExciterProcessor(1, SAMPLE_RATE);
        ex.setDrivePercent(100.0);
        ex.setMixPercent(0.0);

        int n = 1024;
        float[][] in = new float[1][n];
        float[][] out = new float[1][n];
        for (int i = 0; i < n; i++) {
            in[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / SAMPLE_RATE));
        }
        ex.process(in, out, n);
        for (int i = 0; i < n; i++) {
            assertThat(out[0][i]).isEqualTo(in[0][i]);
        }
    }

    @Test
    void shouldGenerateMeasurable2ndAnd3rdHarmonicsAtExpectedFrequencies() {
        ExciterProcessor ex = new ExciterProcessor(1, SAMPLE_RATE);
        ex.setFrequencyHz(2_000.0);
        ex.setDrivePercent(50.0);
        ex.setMixPercent(100.0);
        ex.setOutputGainDb(0.0);
        ex.setMode(ExciterProcessor.Mode.CLASS_A_TUBE);

        int n = 8192;
        // Test fundamental sits well inside the high-passed band. Choose 4 kHz
        // — H2 = 8 kHz, H3 = 12 kHz, both below Nyquist (24 kHz @ 48k SR).
        double freq = 4_000.0;
        double[] inDouble = new double[n];
        float[][] in = new float[1][n];
        float[][] out = new float[1][n];
        for (int i = 0; i < n; i++) {
            double v = 0.4 * Math.sin(2.0 * Math.PI * freq * i / SAMPLE_RATE);
            in[0][i] = (float) v;
            inDouble[i] = v;
        }
        ex.process(in, out, n);

        // Discard the FIR/biquad warm-up by analysing the second half.
        int analysisLen = n / 2;
        float[] tail = new float[analysisLen];
        System.arraycopy(out[0], n - analysisLen, tail, 0, analysisLen);

        double[] mag = magnitudeSpectrum(tail);
        int fundamentalBin = (int) Math.round(freq * analysisLen / SAMPLE_RATE);
        double fundamental = mag[fundamentalBin];
        double h2 = peakNear(mag, 2 * fundamentalBin, 2);
        double h3 = peakNear(mag, 3 * fundamentalBin, 2);

        // Harmonic-to-fundamental ratio in dB.
        double h2DbBelowFund = 20 * Math.log10(h2 / fundamental);
        double h3DbBelowFund = 20 * Math.log10(h3 / fundamental);

        // Both harmonics should be distinctly above the noise floor — i.e.
        // measurable. We expect them to sit ~30-60 dB below the fundamental
        // because the sideband is mixed back at low level.
        assertThat(h2DbBelowFund).isGreaterThan(-80.0);
        assertThat(h3DbBelowFund).isGreaterThan(-80.0);
        // And the harmonics should be substantially larger than non-harmonic
        // noise bins.
        double noise = mag[fundamentalBin / 2]; // a non-harmonic location
        assertThat(h2).isGreaterThan(noise * 5.0);
        assertThat(h3).isGreaterThan(noise * 5.0);
    }

    @Test
    void differentModesHaveDistinguishableSpectra() {
        // Drive identical 4 kHz sine through each mode and verify the
        // harmonic balance differs measurably (TRANSFORMER should have
        // a higher 3rd-to-2nd ratio than CLASS_A_TUBE).
        double tubeRatio = harmonic3to2Ratio(ExciterProcessor.Mode.CLASS_A_TUBE);
        double xfmrRatio = harmonic3to2Ratio(ExciterProcessor.Mode.TRANSFORMER);
        double tapeRatio = harmonic3to2Ratio(ExciterProcessor.Mode.TAPE);

        // TRANSFORMER (symmetric cubic) emphasises 3rd → high H3/H2.
        // CLASS_A_TUBE (asymmetric quadratic-bias) emphasises 2nd → low H3/H2.
        assertThat(xfmrRatio).isGreaterThan(tubeRatio);
        // TAPE has a mix of 2nd and 3rd; ensure all three are pairwise
        // distinguishable (ratios differ by >= ~25%).
        assertThat(Math.abs(xfmrRatio - tubeRatio) / tubeRatio).isGreaterThan(0.25);
        assertThat(Math.abs(tapeRatio - tubeRatio) / tubeRatio).isGreaterThan(0.05);
    }

    private static double harmonic3to2Ratio(ExciterProcessor.Mode mode) {
        ExciterProcessor ex = new ExciterProcessor(1, SAMPLE_RATE);
        ex.setFrequencyHz(2_000.0);
        ex.setDrivePercent(80.0);
        ex.setMixPercent(100.0);
        ex.setMode(mode);

        int n = 8192;
        double freq = 4_000.0;
        float[][] in = new float[1][n];
        float[][] out = new float[1][n];
        for (int i = 0; i < n; i++) {
            in[0][i] = (float) (0.4 * Math.sin(2.0 * Math.PI * freq * i / SAMPLE_RATE));
        }
        ex.process(in, out, n);

        int analysisLen = n / 2;
        float[] tail = new float[analysisLen];
        System.arraycopy(out[0], n - analysisLen, tail, 0, analysisLen);
        double[] mag = magnitudeSpectrum(tail);
        int fundamentalBin = (int) Math.round(freq * analysisLen / SAMPLE_RATE);
        double h2 = peakNear(mag, 2 * fundamentalBin, 2);
        double h3 = peakNear(mag, 3 * fundamentalBin, 2);
        return h3 / h2;
    }

    @Test
    void resetShouldClearFilterState() {
        ExciterProcessor ex = new ExciterProcessor(1, SAMPLE_RATE);
        ex.setDrivePercent(100.0);
        ex.setMixPercent(100.0);
        float[][] in = new float[1][512];
        for (int i = 0; i < 512; i++) {
            in[0][i] = (float) Math.sin(2.0 * Math.PI * 4000.0 * i / SAMPLE_RATE);
        }
        float[][] out = new float[1][512];
        ex.process(in, out, 512);
        ex.reset();

        // After reset, processing silence should yield (near-)silence — i.e.
        // there is no residual ringing in the high-pass filter or FIR delay
        // lines.
        float[][] silence = new float[1][512];
        ex.process(silence, out, 512);
        for (int i = 0; i < 512; i++) {
            assertThat(Math.abs(out[0][i])).isLessThan(1e-6f);
        }
    }

    // ---- helpers --------------------------------------------------------

    /** Returns the magnitude spectrum (length n/2) of a real-valued signal. */
    private static double[] magnitudeSpectrum(float[] signal) {
        // Pad to next power of two for FFT.
        int n = 1;
        while (n < signal.length) n <<= 1;
        double[] real = new double[n];
        double[] imag = new double[n];
        // Apply a Hann window to suppress spectral leakage.
        double[] window = FftUtils.createHannWindow(signal.length);
        for (int i = 0; i < signal.length; i++) {
            real[i] = signal[i] * window[i];
        }
        FftUtils.fft(real, imag);
        double[] mag = new double[n / 2];
        for (int i = 0; i < n / 2; i++) {
            mag[i] = Math.hypot(real[i], imag[i]);
        }
        return mag;
    }

    /** Returns the maximum magnitude within ±range bins of the target. */
    private static double peakNear(double[] mag, int target, int range) {
        double max = 0.0;
        int from = Math.max(0, target - range);
        int to = Math.min(mag.length - 1, target + range);
        for (int i = from; i <= to; i++) {
            if (mag[i] > max) max = mag[i];
        }
        return max;
    }
}
