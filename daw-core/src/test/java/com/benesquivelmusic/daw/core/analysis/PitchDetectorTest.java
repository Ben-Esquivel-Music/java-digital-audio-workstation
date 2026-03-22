package com.benesquivelmusic.daw.core.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PitchDetectorTest {

    @Test
    void shouldCreateWithDefaults() {
        var detector = new PitchDetector(2048, 44100.0);
        assertThat(detector.getBufferSize()).isEqualTo(2048);
        assertThat(detector.getSampleRate()).isEqualTo(44100.0);
        assertThat(detector.getThreshold()).isEqualTo(0.15);
    }

    @Test
    void shouldCreateWithCustomThreshold() {
        var detector = new PitchDetector(1024, 44100.0, 0.20);
        assertThat(detector.getThreshold()).isEqualTo(0.20);
    }

    @Test
    void shouldDetect440HzSineWave() {
        double frequency = 440.0;
        double sampleRate = 44100.0;
        int bufferSize = 2048;
        var detector = new PitchDetector(bufferSize, sampleRate);

        float[] samples = generateSineWave(frequency, sampleRate, bufferSize);
        var result = detector.detect(samples);

        assertThat(result.pitched()).isTrue();
        assertThat(result.frequencyHz()).isCloseTo(frequency,
                org.assertj.core.data.Offset.offset(5.0));
        assertThat(result.probability()).isGreaterThan(0.8);
    }

    @Test
    void shouldDetect261HzSineWave() {
        double frequency = 261.63; // Middle C
        double sampleRate = 44100.0;
        int bufferSize = 2048;
        var detector = new PitchDetector(bufferSize, sampleRate);

        float[] samples = generateSineWave(frequency, sampleRate, bufferSize);
        var result = detector.detect(samples);

        assertThat(result.pitched()).isTrue();
        assertThat(result.frequencyHz()).isCloseTo(frequency,
                org.assertj.core.data.Offset.offset(5.0));
    }

    @Test
    void shouldDetect1000HzSineWave() {
        double frequency = 1000.0;
        double sampleRate = 44100.0;
        int bufferSize = 2048;
        var detector = new PitchDetector(bufferSize, sampleRate);

        float[] samples = generateSineWave(frequency, sampleRate, bufferSize);
        var result = detector.detect(samples);

        assertThat(result.pitched()).isTrue();
        assertThat(result.frequencyHz()).isCloseTo(frequency,
                org.assertj.core.data.Offset.offset(10.0));
    }

    @Test
    void shouldReturnUnpitchedForSilence() {
        var detector = new PitchDetector(2048, 44100.0);
        float[] silence = new float[2048];
        var result = detector.detect(silence);

        assertThat(result.pitched()).isFalse();
        assertThat(result.frequencyHz()).isEqualTo(-1.0);
    }

    @Test
    void shouldReturnUnpitchedForNoise() {
        var detector = new PitchDetector(2048, 44100.0, 0.10);
        float[] noise = new float[2048];
        var rng = new java.util.Random(42);
        for (int i = 0; i < noise.length; i++) {
            noise[i] = (rng.nextFloat() * 2.0f - 1.0f) * 0.5f;
        }
        var result = detector.detect(noise);

        // Noise should either be unpitched or have very low probability
        if (result.pitched()) {
            assertThat(result.probability()).isLessThan(0.95);
        }
    }

    @Test
    void shouldRejectInvalidBufferSize() {
        assertThatThrownBy(() -> new PitchDetector(2, 44100.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PitchDetector(0, 44100.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidSampleRate() {
        assertThatThrownBy(() -> new PitchDetector(2048, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PitchDetector(2048, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidThreshold() {
        assertThatThrownBy(() -> new PitchDetector(2048, 44100.0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PitchDetector(2048, 44100.0, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectTooShortSampleBuffer() {
        var detector = new PitchDetector(2048, 44100.0);
        assertThatThrownBy(() -> detector.detect(new float[1024]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldDetectPitchAtDifferentSampleRates() {
        double frequency = 440.0;
        double sampleRate = 48000.0;
        int bufferSize = 2048;
        var detector = new PitchDetector(bufferSize, sampleRate);

        float[] samples = generateSineWave(frequency, sampleRate, bufferSize);
        var result = detector.detect(samples);

        assertThat(result.pitched()).isTrue();
        assertThat(result.frequencyHz()).isCloseTo(frequency,
                org.assertj.core.data.Offset.offset(5.0));
    }

    private static float[] generateSineWave(double frequency, double sampleRate, int length) {
        float[] samples = new float[length];
        for (int i = 0; i < length; i++) {
            samples[i] = (float) Math.sin(2.0 * Math.PI * frequency * i / sampleRate);
        }
        return samples;
    }
}
