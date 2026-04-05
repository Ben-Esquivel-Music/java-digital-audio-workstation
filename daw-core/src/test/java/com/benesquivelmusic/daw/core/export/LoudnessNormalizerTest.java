package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.sdk.visualization.LoudnessTarget;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class LoudnessNormalizerTest {

    @Test
    void shouldMeasureLoudnessOfSilence() {
        float[][] audio = new float[2][44100]; // 1 second of silence
        double lufs = LoudnessNormalizer.measureIntegratedLoudness(audio, 44100);
        assertThat(lufs).isLessThan(-60.0);
    }

    @Test
    void shouldMeasureLoudnessOfSineWave() {
        float[][] audio = generateStereoSine(44100, 2.0, 1000.0, 0.5f);
        double lufs = LoudnessNormalizer.measureIntegratedLoudness(audio, 44100);
        // A -6 dBFS sine wave should measure roughly around -9 to -3 LUFS
        // depending on K-weighting at 1 kHz
        assertThat(lufs).isBetween(-20.0, 0.0);
    }

    @Test
    void shouldNotNormalizeSilence() {
        float[][] audio = new float[2][44100]; // silence
        double gainDb = LoudnessNormalizer.normalize(audio, 44100, -14.0);
        assertThat(gainDb).isEqualTo(0.0);
    }

    @Test
    void shouldNormalizeToTargetLufs() {
        float[][] audio = generateStereoSine(44100, 2.0, 1000.0, 0.5f);
        double originalLufs = LoudnessNormalizer.measureIntegratedLoudness(audio, 44100);

        double gainDb = LoudnessNormalizer.normalize(audio, 44100, -14.0);

        assertThat(gainDb).isNotEqualTo(0.0);
        double normalizedLufs = LoudnessNormalizer.measureIntegratedLoudness(audio, 44100);
        // The normalized loudness should be close to -14 LUFS (within a few LU tolerance
        // due to clipping and measurement accuracy)
        assertThat(normalizedLufs).isCloseTo(-14.0, offset(3.0));
    }

    @Test
    void shouldNormalizeWithLoudnessTarget() {
        float[][] audio = generateStereoSine(44100, 2.0, 1000.0, 0.5f);
        double gainDb = LoudnessNormalizer.normalize(audio, 44100, LoudnessTarget.SPOTIFY);
        // Should have applied some gain adjustment
        assertThat(gainDb).isNotEqualTo(0.0);
    }

    @Test
    void shouldClampToMinusOneAfterGain() {
        // Very loud signal that will need negative gain
        float[][] audio = generateStereoSine(44100, 2.0, 1000.0, 0.99f);
        LoudnessNormalizer.normalize(audio, 44100, -14.0);

        for (int ch = 0; ch < audio.length; ch++) {
            for (int i = 0; i < audio[ch].length; i++) {
                assertThat(audio[ch][i]).isBetween(-1.0f, 1.0f);
            }
        }
    }

    @Test
    void shouldRejectNullAudioData() {
        assertThatThrownBy(() -> LoudnessNormalizer.normalize(null, 44100, -14.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectEmptyAudioData() {
        assertThatThrownBy(() -> LoudnessNormalizer.normalize(new float[0][], 44100, -14.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNonPositiveSampleRate() {
        float[][] audio = new float[1][100];
        assertThatThrownBy(() -> LoudnessNormalizer.normalize(audio, 0, -14.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullTarget() {
        float[][] audio = new float[1][100];
        assertThatThrownBy(() -> LoudnessNormalizer.normalize(audio, 44100, (LoudnessTarget) null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldHandleMonoAudio() {
        float[][] audio = new float[1][44100 * 2];
        for (int i = 0; i < audio[0].length; i++) {
            audio[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 1000.0 * i / 44100));
        }
        double lufs = LoudnessNormalizer.measureIntegratedLoudness(audio, 44100);
        assertThat(lufs).isGreaterThan(-70.0);
    }

    private static float[][] generateStereoSine(int sampleRate, double duration,
                                                 double freq, float amplitude) {
        int numSamples = (int) (sampleRate * duration);
        float[][] audio = new float[2][numSamples];
        for (int i = 0; i < numSamples; i++) {
            float value = (float) (amplitude * Math.sin(2.0 * Math.PI * freq * i / sampleRate));
            audio[0][i] = value;
            audio[1][i] = value;
        }
        return audio;
    }
}
