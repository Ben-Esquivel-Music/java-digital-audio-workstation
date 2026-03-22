package com.benesquivelmusic.daw.core.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnsetDetectorTest {

    @Test
    void shouldCreateWithDefaults() {
        OnsetDetector detector = new OnsetDetector(1024, 44100.0);
        assertThat(detector.getFftSize()).isEqualTo(1024);
        assertThat(detector.getHopSize()).isEqualTo(512);
        assertThat(detector.getSampleRate()).isEqualTo(44100.0);
        assertThat(detector.getSensitivityThreshold()).isEqualTo(1.5);
    }

    @Test
    void shouldCreateWithCustomParameters() {
        OnsetDetector detector = new OnsetDetector(2048, 256, 48000.0, 2.0);
        assertThat(detector.getFftSize()).isEqualTo(2048);
        assertThat(detector.getHopSize()).isEqualTo(256);
        assertThat(detector.getSampleRate()).isEqualTo(48000.0);
        assertThat(detector.getSensitivityThreshold()).isEqualTo(2.0);
    }

    @Test
    void shouldDetectOnsetInSilenceToBurst() {
        double sampleRate = 44100.0;
        int fftSize = 1024;
        OnsetDetector detector = new OnsetDetector(fftSize, fftSize / 4, sampleRate, 1.5);

        // Create signal: silence followed by a loud sine burst
        int totalSamples = 44100; // 1 second
        float[] samples = new float[totalSamples];

        // Silent for first half
        // Loud sine burst for second half
        int onsetSample = totalSamples / 2;
        for (int i = onsetSample; i < totalSamples; i++) {
            samples[i] = (float) (0.8 * Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate));
        }

        List<OnsetDetector.Onset> onsets = detector.detect(samples);

        assertThat(onsets).isNotEmpty();
        // The first onset should be near the burst start
        double firstOnsetTime = detector.frameIndexToSeconds(onsets.getFirst().frameIndex());
        double expectedTime = (double) onsetSample / sampleRate;
        assertThat(firstOnsetTime).isCloseTo(expectedTime,
                org.assertj.core.data.Offset.offset(0.05));
    }

    @Test
    void shouldDetectMultipleOnsets() {
        double sampleRate = 44100.0;
        int fftSize = 1024;
        OnsetDetector detector = new OnsetDetector(fftSize, fftSize / 4, sampleRate, 1.5);

        // Create signal with two bursts separated by silence
        int totalSamples = 88200; // 2 seconds
        float[] samples = new float[totalSamples];

        // First burst: 0.2s–0.4s
        fillSine(samples, sampleRate, 440.0, 0.8f, 8820, 17640);
        // Second burst: 1.2s–1.4s
        fillSine(samples, sampleRate, 880.0, 0.8f, 52920, 61740);

        List<OnsetDetector.Onset> onsets = detector.detect(samples);

        assertThat(onsets.size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void shouldReturnNoOnsetsForSilence() {
        OnsetDetector detector = new OnsetDetector(1024, 44100.0);
        float[] silence = new float[44100];

        List<OnsetDetector.Onset> onsets = detector.detect(silence);

        assertThat(onsets).isEmpty();
    }

    @Test
    void shouldReturnNoOnsetsForConstantTone() {
        double sampleRate = 44100.0;
        OnsetDetector detector = new OnsetDetector(1024, 256, sampleRate, 1.5);

        // Constant sine wave with no onset
        float[] samples = new float[44100];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate));
        }

        List<OnsetDetector.Onset> onsets = detector.detect(samples);

        // A constant tone should produce at most one onset at the very beginning
        assertThat(onsets.size()).isLessThanOrEqualTo(1);
    }

    @Test
    void shouldConvertFrameIndexToSeconds() {
        OnsetDetector detector = new OnsetDetector(1024, 256, 44100.0, 1.5);
        double seconds = detector.frameIndexToSeconds(10);
        assertThat(seconds).isCloseTo(10.0 * 256 / 44100.0,
                org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void shouldReturnEmptyForTooShortBuffer() {
        OnsetDetector detector = new OnsetDetector(1024, 44100.0);
        float[] tooShort = new float[512];

        List<OnsetDetector.Onset> onsets = detector.detect(tooShort);

        assertThat(onsets).isEmpty();
    }

    @Test
    void shouldRejectNonPowerOfTwoFftSize() {
        assertThatThrownBy(() -> new OnsetDetector(1000, 44100.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidHopSize() {
        assertThatThrownBy(() -> new OnsetDetector(1024, 0, 44100.0, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OnsetDetector(1024, 2048, 44100.0, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidSampleRate() {
        assertThatThrownBy(() -> new OnsetDetector(1024, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidSensitivity() {
        assertThatThrownBy(() -> new OnsetDetector(1024, 512, 44100.0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onsetStrengthShouldBePositive() {
        double sampleRate = 44100.0;
        OnsetDetector detector = new OnsetDetector(1024, 256, sampleRate, 1.5);

        float[] samples = new float[44100];
        int onset = 22050;
        for (int i = onset; i < samples.length; i++) {
            samples[i] = (float) (0.9 * Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate));
        }

        List<OnsetDetector.Onset> onsets = detector.detect(samples);
        for (OnsetDetector.Onset o : onsets) {
            assertThat(o.strength()).isGreaterThan(0);
        }
    }

    private static void fillSine(float[] buffer, double sampleRate,
                                  double freq, float amplitude, int start, int end) {
        for (int i = start; i < end && i < buffer.length; i++) {
            buffer[i] = (float) (amplitude * Math.sin(2.0 * Math.PI * freq * i / sampleRate));
        }
    }
}
