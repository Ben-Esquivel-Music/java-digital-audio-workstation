package com.benesquivelmusic.daw.core.analysis;

import com.benesquivelmusic.daw.sdk.analysis.WindowType;
import com.benesquivelmusic.daw.sdk.visualization.SpectrumData;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpectrumAnalyzerTest {

    @Test
    void shouldCreateWithValidParameters() {
        SpectrumAnalyzer analyzer = new SpectrumAnalyzer(1024, 44100.0, 0.5);

        assertThat(analyzer.getFftSize()).isEqualTo(1024);
        assertThat(analyzer.getSampleRate()).isEqualTo(44100.0);
        assertThat(analyzer.hasData()).isFalse();
        assertThat(analyzer.getLatestData()).isNull();
    }

    @Test
    void shouldRejectNonPowerOfTwoFftSize() {
        assertThatThrownBy(() -> new SpectrumAnalyzer(100, 44100.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectZeroFftSize() {
        assertThatThrownBy(() -> new SpectrumAnalyzer(0, 44100.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidSmoothingFactor() {
        assertThatThrownBy(() -> new SpectrumAnalyzer(1024, 44100.0, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SpectrumAnalyzer(1024, 44100.0, -0.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldProduceSpectrumDataAfterProcessing() {
        SpectrumAnalyzer analyzer = new SpectrumAnalyzer(256, 44100.0, 0.0);
        float[] samples = generateSineWave(1000.0, 44100.0, 256);
        analyzer.process(samples);

        assertThat(analyzer.hasData()).isTrue();
        SpectrumData data = analyzer.getLatestData();
        assertThat(data).isNotNull();
        assertThat(data.binCount()).isEqualTo(128);
        assertThat(data.fftSize()).isEqualTo(256);
    }

    @Test
    void shouldDetectPeakAtSineFrequency() {
        int fftSize = 1024;
        double sampleRate = 44100.0;
        double frequency = 1000.0;
        SpectrumAnalyzer analyzer = new SpectrumAnalyzer(fftSize, sampleRate, 0.0);

        float[] samples = generateSineWave(frequency, sampleRate, fftSize);
        analyzer.process(samples);

        SpectrumData data = analyzer.getLatestData();
        float[] magnitudes = data.magnitudesDb();

        // Find the peak bin
        int peakBin = 0;
        float peakMag = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < magnitudes.length; i++) {
            if (magnitudes[i] > peakMag) {
                peakMag = magnitudes[i];
                peakBin = i;
            }
        }

        // The peak should be near the expected bin for 1 kHz
        double expectedBin = frequency * fftSize / sampleRate;
        assertThat((double) peakBin).isCloseTo(expectedBin, org.assertj.core.data.Offset.offset(2.0));
    }

    @Test
    void shouldProduceLowMagnitudesForSilence() {
        SpectrumAnalyzer analyzer = new SpectrumAnalyzer(256, 44100.0, 0.0);
        float[] silence = new float[256];
        analyzer.process(silence);

        SpectrumData data = analyzer.getLatestData();
        for (float mag : data.magnitudesDb()) {
            assertThat(mag).isLessThanOrEqualTo(-100.0f);
        }
    }

    @Test
    void shouldResetState() {
        SpectrumAnalyzer analyzer = new SpectrumAnalyzer(256, 44100.0, 0.5);
        analyzer.process(generateSineWave(1000, 44100, 256));
        assertThat(analyzer.hasData()).isTrue();

        analyzer.reset();
        assertThat(analyzer.hasData()).isFalse();
    }

    @Test
    void shouldApplySmoothing() {
        SpectrumAnalyzer analyzer = new SpectrumAnalyzer(256, 44100.0, 0.8);

        // Process a loud signal
        analyzer.process(generateSineWave(1000, 44100, 256));
        SpectrumData loud = analyzer.getLatestData();

        // Find the peak bin from the loud signal
        int peakBin = 0;
        float peakMag = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < loud.binCount(); i++) {
            if (loud.magnitudesDb()[i] > peakMag) {
                peakMag = loud.magnitudesDb()[i];
                peakBin = i;
            }
        }

        // Process silence — with smoothing, the peak bin should not drop to floor immediately
        analyzer.process(new float[256]);
        SpectrumData afterSilence = analyzer.getLatestData();

        // The peak bin should retain residual energy from smoothing
        assertThat(afterSilence.magnitudesDb()[peakBin]).isGreaterThan(-110);
    }

    @Test
    void fftUtilsShouldCorrectlyTransformKnownSignal() {
        // Test with a simple signal: DC component
        double[] real = {1.0, 1.0, 1.0, 1.0};
        double[] imag = {0.0, 0.0, 0.0, 0.0};
        FftUtils.fft(real, imag);

        // DC bin should have all the energy (sum = 4)
        assertThat(real[0]).isCloseTo(4.0, org.assertj.core.data.Offset.offset(1e-10));
        assertThat(real[1]).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-10));
    }

    // --- New tests for configurable window type ---

    @Test
    void shouldCreateWithWindowType() {
        SpectrumAnalyzer analyzer = new SpectrumAnalyzer(
                1024, 44100.0, 0.5, WindowType.HAMMING, false, 0.0);

        assertThat(analyzer.getWindowType()).isEqualTo(WindowType.HAMMING);
        assertThat(analyzer.isPeakHoldEnabled()).isFalse();
    }

    @Test
    void shouldDefaultToHannWindow() {
        SpectrumAnalyzer analyzer = new SpectrumAnalyzer(1024, 44100.0, 0.5);

        assertThat(analyzer.getWindowType()).isEqualTo(WindowType.HANN);
    }

    @Test
    void shouldDetectPeakWithHammingWindow() {
        int fftSize = 1024;
        double sampleRate = 44100.0;
        double frequency = 1000.0;
        SpectrumAnalyzer analyzer = new SpectrumAnalyzer(
                fftSize, sampleRate, 0.0, WindowType.HAMMING, false, 0.0);

        float[] samples = generateSineWave(frequency, sampleRate, fftSize);
        analyzer.process(samples);

        SpectrumData data = analyzer.getLatestData();
        float[] magnitudes = data.magnitudesDb();

        int peakBin = findPeakBin(magnitudes);
        double expectedBin = frequency * fftSize / sampleRate;
        assertThat((double) peakBin).isCloseTo(expectedBin, org.assertj.core.data.Offset.offset(2.0));
    }

    @Test
    void shouldDetectPeakWithBlackmanHarrisWindow() {
        int fftSize = 1024;
        double sampleRate = 44100.0;
        double frequency = 1000.0;
        SpectrumAnalyzer analyzer = new SpectrumAnalyzer(
                fftSize, sampleRate, 0.0, WindowType.BLACKMAN_HARRIS, false, 0.0);

        float[] samples = generateSineWave(frequency, sampleRate, fftSize);
        analyzer.process(samples);

        SpectrumData data = analyzer.getLatestData();
        float[] magnitudes = data.magnitudesDb();

        int peakBin = findPeakBin(magnitudes);
        double expectedBin = frequency * fftSize / sampleRate;
        assertThat((double) peakBin).isCloseTo(expectedBin, org.assertj.core.data.Offset.offset(2.0));
    }

    // --- New tests for peak hold ---

    @Test
    void shouldTrackPeakHoldMagnitudes() {
        SpectrumAnalyzer analyzer = new SpectrumAnalyzer(
                256, 44100.0, 0.0, WindowType.HANN, true, 0.0);

        float[] samples = generateSineWave(1000.0, 44100.0, 256);
        analyzer.process(samples);

        SpectrumData data = analyzer.getLatestData();
        assertThat(data.hasPeakHold()).isTrue();
        assertThat(data.peakHoldDb()).isNotNull();
        assertThat(data.peakHoldDb().length).isEqualTo(data.binCount());
    }

    @Test
    void shouldNotProducePeakHoldWhenDisabled() {
        SpectrumAnalyzer analyzer = new SpectrumAnalyzer(
                256, 44100.0, 0.0, WindowType.HANN, false, 0.0);

        float[] samples = generateSineWave(1000.0, 44100.0, 256);
        analyzer.process(samples);

        SpectrumData data = analyzer.getLatestData();
        assertThat(data.hasPeakHold()).isFalse();
    }

    @Test
    void peakHoldShouldRetainMaximumAfterSilence() {
        SpectrumAnalyzer analyzer = new SpectrumAnalyzer(
                256, 44100.0, 0.0, WindowType.HANN, true, 0.0);

        float[] loud = generateSineWave(1000.0, 44100.0, 256);
        analyzer.process(loud);

        SpectrumData loudData = analyzer.getLatestData();
        int peakBin = findPeakBin(loudData.magnitudesDb());
        float peakValue = loudData.peakHoldDb()[peakBin];

        // Process silence — peak hold should stay at the previous peak (no decay)
        analyzer.process(new float[256]);
        SpectrumData afterSilence = analyzer.getLatestData();

        assertThat(afterSilence.peakHoldDb()[peakBin]).isEqualTo(peakValue);
    }

    @Test
    void peakHoldShouldDecayWithNonZeroRate() {
        double decayRate = 3.0;
        SpectrumAnalyzer analyzer = new SpectrumAnalyzer(
                256, 44100.0, 0.0, WindowType.HANN, true, decayRate);

        float[] loud = generateSineWave(1000.0, 44100.0, 256);
        analyzer.process(loud);

        SpectrumData loudData = analyzer.getLatestData();
        int peakBin = findPeakBin(loudData.magnitudesDb());
        float peakValue = loudData.peakHoldDb()[peakBin];

        // Process silence — peak hold should decay
        analyzer.process(new float[256]);
        SpectrumData afterSilence = analyzer.getLatestData();

        assertThat(afterSilence.peakHoldDb()[peakBin]).isLessThan(peakValue);
    }

    @Test
    void shouldRejectNegativePeakDecayRate() {
        assertThatThrownBy(() -> new SpectrumAnalyzer(
                256, 44100.0, 0.0, WindowType.HANN, true, -1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldResetPeakHoldData() {
        SpectrumAnalyzer analyzer = new SpectrumAnalyzer(
                256, 44100.0, 0.0, WindowType.HANN, true, 0.0);
        analyzer.process(generateSineWave(1000, 44100, 256));
        assertThat(analyzer.hasData()).isTrue();

        analyzer.reset();
        assertThat(analyzer.hasData()).isFalse();
    }

    // --- New tests for stereo processing ---

    @Test
    void shouldProcessStereoSamples() {
        SpectrumAnalyzer analyzer = new SpectrumAnalyzer(256, 44100.0, 0.0);

        float[] left = generateSineWave(1000.0, 44100.0, 256);
        float[] right = generateSineWave(1000.0, 44100.0, 256);
        analyzer.processStereo(left, right);

        assertThat(analyzer.hasData()).isTrue();
        SpectrumData data = analyzer.getLatestData();
        assertThat(data).isNotNull();
        assertThat(data.binCount()).isEqualTo(128);
    }

    @Test
    void shouldProcessStereoWithDifferentChannels() {
        SpectrumAnalyzer analyzer = new SpectrumAnalyzer(256, 44100.0, 0.0);

        float[] left = generateSineWave(500.0, 44100.0, 256);
        float[] right = generateSineWave(2000.0, 44100.0, 256);
        analyzer.processStereo(left, right);

        assertThat(analyzer.hasData()).isTrue();
    }

    // --- New tests for pre/post EQ snapshot ---

    @Test
    void shouldCapturePreEqSnapshot() {
        SpectrumAnalyzer analyzer = new SpectrumAnalyzer(256, 44100.0, 0.0);
        analyzer.process(generateSineWave(1000, 44100, 256));

        analyzer.capturePreEqSnapshot();
        SpectrumData preEq = analyzer.getPreEqData();
        assertThat(preEq).isNotNull();
        assertThat(preEq.binCount()).isEqualTo(128);
    }

    @Test
    void shouldReturnNullPreEqWhenNotCaptured() {
        SpectrumAnalyzer analyzer = new SpectrumAnalyzer(256, 44100.0, 0.0);
        assertThat(analyzer.getPreEqData()).isNull();
    }

    @Test
    void shouldResetPreEqSnapshot() {
        SpectrumAnalyzer analyzer = new SpectrumAnalyzer(256, 44100.0, 0.0);
        analyzer.process(generateSineWave(1000, 44100, 256));
        analyzer.capturePreEqSnapshot();
        assertThat(analyzer.getPreEqData()).isNotNull();

        analyzer.reset();
        assertThat(analyzer.getPreEqData()).isNull();
    }

    // --- New tests for FftUtils window functions ---

    @Test
    void hammingWindowShouldHaveCorrectProperties() {
        double[] window = FftUtils.createHammingWindow(256);
        assertThat(window).hasSize(256);

        // Hamming window endpoints are not zero (approximately 0.08)
        assertThat(window[0]).isCloseTo(0.08, org.assertj.core.data.Offset.offset(0.01));

        // Center should be close to 1.0
        assertThat(window[127]).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void blackmanHarrisWindowShouldHaveCorrectProperties() {
        double[] window = FftUtils.createBlackmanHarrisWindow(256);
        assertThat(window).hasSize(256);

        // Blackman-Harris window has very small endpoints (near 0.00006)
        assertThat(window[0]).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.001));

        // Center should be close to 1.0
        assertThat(window[127]).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void createWindowFactoryShouldReturnCorrectWindowType() {
        double[] hannWindow = FftUtils.createWindow(WindowType.HANN, 64);
        double[] hammingWindow = FftUtils.createWindow(WindowType.HAMMING, 64);
        double[] bhWindow = FftUtils.createWindow(WindowType.BLACKMAN_HARRIS, 64);

        assertThat(hannWindow).hasSize(64);
        assertThat(hammingWindow).hasSize(64);
        assertThat(bhWindow).hasSize(64);

        // Hann and Hamming should differ at endpoints
        assertThat(hannWindow[0]).isNotEqualTo(hammingWindow[0]);

        // All windows should peak near center
        assertThat(hannWindow[31]).isGreaterThan(0.9);
        assertThat(hammingWindow[31]).isGreaterThan(0.9);
        assertThat(bhWindow[31]).isGreaterThan(0.9);
    }

    // --- Configurable FFT sizes ---

    @Test
    void shouldSupportFftSize1024() {
        SpectrumAnalyzer analyzer = new SpectrumAnalyzer(1024, 44100.0, 0.0);
        analyzer.process(generateSineWave(1000.0, 44100.0, 1024));
        assertThat(analyzer.getLatestData().binCount()).isEqualTo(512);
    }

    @Test
    void shouldSupportFftSize2048() {
        SpectrumAnalyzer analyzer = new SpectrumAnalyzer(2048, 44100.0, 0.0);
        analyzer.process(generateSineWave(1000.0, 44100.0, 2048));
        assertThat(analyzer.getLatestData().binCount()).isEqualTo(1024);
    }

    @Test
    void shouldSupportFftSize4096() {
        SpectrumAnalyzer analyzer = new SpectrumAnalyzer(4096, 44100.0, 0.0);
        analyzer.process(generateSineWave(1000.0, 44100.0, 4096));
        assertThat(analyzer.getLatestData().binCount()).isEqualTo(2048);
    }

    @Test
    void shouldSupportFftSize8192() {
        SpectrumAnalyzer analyzer = new SpectrumAnalyzer(8192, 44100.0, 0.0);
        analyzer.process(generateSineWave(1000.0, 44100.0, 8192));
        assertThat(analyzer.getLatestData().binCount()).isEqualTo(4096);
    }

    private static float[] generateSineWave(double frequency, double sampleRate, int length) {
        float[] samples = new float[length];
        for (int i = 0; i < length; i++) {
            samples[i] = (float) Math.sin(2.0 * Math.PI * frequency * i / sampleRate);
        }
        return samples;
    }

    private static int findPeakBin(float[] magnitudes) {
        int peakBin = 0;
        float peakMag = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < magnitudes.length; i++) {
            if (magnitudes[i] > peakMag) {
                peakMag = magnitudes[i];
                peakBin = i;
            }
        }
        return peakBin;
    }
}
