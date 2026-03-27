package com.benesquivelmusic.daw.core.recording;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

class MetronomeTest {

    @Test
    void shouldCreateMetronomeWithValidParams() {
        Metronome metronome = new Metronome(44100.0, 2);

        assertThat(metronome.getSampleRate()).isEqualTo(44100.0);
        assertThat(metronome.getChannels()).isEqualTo(2);
    }

    @Test
    void shouldRejectNonPositiveSampleRate() {
        assertThatThrownBy(() -> new Metronome(0.0, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampleRate");
    }

    @Test
    void shouldRejectNonPositiveChannels() {
        assertThatThrownBy(() -> new Metronome(44100.0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channels");
    }

    @Test
    void shouldReturnEmptyBufferForCountInOff() {
        Metronome metronome = new Metronome(44100.0, 2);

        float[][] audio = metronome.generateCountIn(CountInMode.OFF, 120.0, 4);

        assertThat(audio).hasNumberOfRows(2);
        assertThat(audio[0]).isEmpty();
        assertThat(audio[1]).isEmpty();
    }

    @Test
    void shouldGenerateOneBarCountIn() {
        Metronome metronome = new Metronome(44100.0, 2);

        float[][] audio = metronome.generateCountIn(CountInMode.ONE_BAR, 120.0, 4);

        // 4 beats at 120 BPM = 2 seconds = 88200 samples
        assertThat(audio).hasNumberOfRows(2);
        assertThat(audio[0].length).isEqualTo(88200);
        assertThat(audio[1].length).isEqualTo(88200);
    }

    @Test
    void shouldGenerateTwoBarCountIn() {
        Metronome metronome = new Metronome(44100.0, 2);

        float[][] audio = metronome.generateCountIn(CountInMode.TWO_BARS, 120.0, 4);

        // 8 beats at 120 BPM = 4 seconds = 176400 samples
        assertThat(audio[0].length).isEqualTo(176400);
    }

    @Test
    void shouldGenerateFourBarCountIn() {
        Metronome metronome = new Metronome(44100.0, 2);

        float[][] audio = metronome.generateCountIn(CountInMode.FOUR_BARS, 120.0, 4);

        // 16 beats at 120 BPM = 8 seconds = 352800 samples
        assertThat(audio[0].length).isEqualTo(352800);
    }

    @Test
    void shouldContainNonZeroSamplesForClicks() {
        Metronome metronome = new Metronome(44100.0, 1);

        float[][] audio = metronome.generateCountIn(CountInMode.ONE_BAR, 120.0, 4);

        // The first few samples should contain the accented click (non-zero)
        boolean hasNonZero = false;
        for (int i = 0; i < 100; i++) {
            if (audio[0][i] != 0.0f) {
                hasNonZero = true;
                break;
            }
        }
        assertThat(hasNonZero).isTrue();
    }

    @Test
    void shouldHaveSilenceBetweenClicks() {
        Metronome metronome = new Metronome(44100.0, 1);

        float[][] audio = metronome.generateCountIn(CountInMode.ONE_BAR, 120.0, 4);

        // Click is 0.02 seconds = 882 samples at 44100 Hz.
        // Beat is 0.5 seconds = 22050 samples at 120 BPM.
        // Samples at index ~5000 should be in the silence area after the first click.
        assertThat(audio[0][5000]).isCloseTo(0.0f, offset(0.001f));
    }

    @Test
    void shouldGenerateClickForAccent() {
        Metronome metronome = new Metronome(44100.0, 2);

        float[][] click = metronome.generateClick(true);

        assertThat(click).hasNumberOfRows(2);
        assertThat(click[0].length).isGreaterThan(0);
        // Check that the click contains non-zero samples (first sample is 0 since sin(0)=0)
        boolean hasNonZero = false;
        for (float sample : click[0]) {
            if (sample != 0.0f) {
                hasNonZero = true;
                break;
            }
        }
        assertThat(hasNonZero).isTrue();
    }

    @Test
    void shouldGenerateClickForNonAccent() {
        Metronome metronome = new Metronome(44100.0, 2);

        float[][] click = metronome.generateClick(false);

        assertThat(click).hasNumberOfRows(2);
        assertThat(click[0].length).isGreaterThan(0);
        boolean hasNonZero = false;
        for (float sample : click[0]) {
            if (sample != 0.0f) {
                hasNonZero = true;
                break;
            }
        }
        assertThat(hasNonZero).isTrue();
    }

    @Test
    void accentClickShouldBeLouderThanNormalClick() {
        Metronome metronome = new Metronome(44100.0, 1);

        float[][] accent = metronome.generateClick(true);
        float[][] normal = metronome.generateClick(false);

        // Compare the RMS energy of both clicks
        double accentRms = rms(accent[0]);
        double normalRms = rms(normal[0]);

        assertThat(accentRms).isGreaterThan(normalRms);
    }

    @Test
    void shouldRejectNullCountInMode() {
        Metronome metronome = new Metronome(44100.0, 2);

        assertThatThrownBy(() -> metronome.generateCountIn(null, 120.0, 4))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNonPositiveTempo() {
        Metronome metronome = new Metronome(44100.0, 2);

        assertThatThrownBy(() -> metronome.generateCountIn(CountInMode.ONE_BAR, 0.0, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tempo");
    }

    @Test
    void shouldRejectNonPositiveBeatsPerBar() {
        Metronome metronome = new Metronome(44100.0, 2);

        assertThatThrownBy(() -> metronome.generateCountIn(CountInMode.ONE_BAR, 120.0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("beatsPerBar");
    }

    @Test
    void allChannelsShouldHaveIdenticalContent() {
        Metronome metronome = new Metronome(44100.0, 2);

        float[][] audio = metronome.generateCountIn(CountInMode.ONE_BAR, 120.0, 4);

        for (int i = 0; i < audio[0].length; i++) {
            assertThat(audio[0][i]).isEqualTo(audio[1][i]);
        }
    }

    @Test
    void allSamplesShouldBeWithinValidRange() {
        Metronome metronome = new Metronome(44100.0, 1);

        float[][] audio = metronome.generateCountIn(CountInMode.TWO_BARS, 120.0, 4);

        for (float sample : audio[0]) {
            assertThat(sample).isBetween(-1.0f, 1.0f);
        }
    }

    private static double rms(float[] samples) {
        double sum = 0.0;
        for (float sample : samples) {
            sum += (double) sample * sample;
        }
        return Math.sqrt(sum / samples.length);
    }
}
