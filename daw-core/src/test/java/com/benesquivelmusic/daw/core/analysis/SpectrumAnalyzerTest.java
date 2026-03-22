package com.benesquivelmusic.daw.core.analysis;

import com.benesquivelmusic.daw.sdk.visualization.SpectrumData;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpectrumAnalyzerTest {

    @Test
    void shouldCreateWithValidParameters() {
        var analyzer = new SpectrumAnalyzer(1024, 44100.0, 0.5);

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
        var analyzer = new SpectrumAnalyzer(256, 44100.0, 0.0);
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
        var analyzer = new SpectrumAnalyzer(fftSize, sampleRate, 0.0);

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
        var analyzer = new SpectrumAnalyzer(256, 44100.0, 0.0);
        float[] silence = new float[256];
        analyzer.process(silence);

        SpectrumData data = analyzer.getLatestData();
        for (float mag : data.magnitudesDb()) {
            assertThat(mag).isLessThanOrEqualTo(-100.0f);
        }
    }

    @Test
    void shouldResetState() {
        var analyzer = new SpectrumAnalyzer(256, 44100.0, 0.5);
        analyzer.process(generateSineWave(1000, 44100, 256));
        assertThat(analyzer.hasData()).isTrue();

        analyzer.reset();
        assertThat(analyzer.hasData()).isFalse();
    }

    @Test
    void shouldApplySmoothing() {
        var analyzer = new SpectrumAnalyzer(256, 44100.0, 0.8);

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

    private static float[] generateSineWave(double frequency, double sampleRate, int length) {
        float[] samples = new float[length];
        for (int i = 0; i < length; i++) {
            samples[i] = (float) Math.sin(2.0 * Math.PI * frequency * i / sampleRate);
        }
        return samples;
    }
}
