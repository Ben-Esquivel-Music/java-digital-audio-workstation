package com.benesquivelmusic.daw.core.recording;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

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

    // ── Enabled/Disabled toggle tests ───────────────────────────────────

    @Test
    void shouldBeEnabledByDefault() {
        Metronome metronome = new Metronome(44100.0, 2);

        assertThat(metronome.isEnabled()).isTrue();
    }

    @Test
    void shouldToggleEnabled() {
        Metronome metronome = new Metronome(44100.0, 2);

        metronome.setEnabled(false);
        assertThat(metronome.isEnabled()).isFalse();

        metronome.setEnabled(true);
        assertThat(metronome.isEnabled()).isTrue();
    }

    @Test
    void shouldReturnEmptyBufferWhenDisabled() {
        Metronome metronome = new Metronome(44100.0, 2);
        metronome.setEnabled(false);

        float[][] audio = metronome.generateCountIn(CountInMode.ONE_BAR, 120.0, 4);

        assertThat(audio).hasNumberOfRows(2);
        assertThat(audio[0]).isEmpty();
        assertThat(audio[1]).isEmpty();
    }

    // ── Volume tests ────────────────────────────────────────────────────

    @Test
    void shouldHaveFullVolumeByDefault() {
        Metronome metronome = new Metronome(44100.0, 1);

        assertThat(metronome.getVolume()).isEqualTo(1.0f);
    }

    @Test
    void shouldSetVolume() {
        Metronome metronome = new Metronome(44100.0, 1);

        metronome.setVolume(0.5f);

        assertThat(metronome.getVolume()).isEqualTo(0.5f);
    }

    @Test
    void shouldRejectVolumeAboveOne() {
        Metronome metronome = new Metronome(44100.0, 1);

        assertThatThrownBy(() -> metronome.setVolume(1.1f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("volume");
    }

    @Test
    void shouldRejectNegativeVolume() {
        Metronome metronome = new Metronome(44100.0, 1);

        assertThatThrownBy(() -> metronome.setVolume(-0.1f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("volume");
    }

    @Test
    void shouldAcceptZeroVolume() {
        Metronome metronome = new Metronome(44100.0, 1);

        metronome.setVolume(0.0f);

        assertThat(metronome.getVolume()).isEqualTo(0.0f);
    }

    @Test
    void lowerVolumeShouldProduceQuieterClicks() {
        Metronome fullVolume = new Metronome(44100.0, 1);

        Metronome halfVolume = new Metronome(44100.0, 1);
        halfVolume.setVolume(0.5f);

        float[][] fullClick = fullVolume.generateClick(true);
        float[][] halfClick = halfVolume.generateClick(true);

        assertThat(rms(fullClick[0])).isGreaterThan(rms(halfClick[0]));
    }

    @Test
    void zeroVolumeShouldProduceSilence() {
        Metronome metronome = new Metronome(44100.0, 1);
        metronome.setVolume(0.0f);

        float[][] click = metronome.generateClick(true);

        for (float sample : click[0]) {
            assertThat(sample).isEqualTo(0.0f);
        }
    }

    // ── Click sound tests ───────────────────────────────────────────────

    @Test
    void shouldDefaultToWoodblock() {
        Metronome metronome = new Metronome(44100.0, 1);

        assertThat(metronome.getClickSound()).isEqualTo(ClickSound.WOODBLOCK);
    }

    @Test
    void shouldSetClickSound() {
        Metronome metronome = new Metronome(44100.0, 1);

        metronome.setClickSound(ClickSound.COWBELL);

        assertThat(metronome.getClickSound()).isEqualTo(ClickSound.COWBELL);
    }

    @Test
    void shouldRejectNullClickSound() {
        Metronome metronome = new Metronome(44100.0, 1);

        assertThatThrownBy(() -> metronome.setClickSound(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void differentClickSoundsShouldProduceDifferentAudio() {
        Metronome woodblock = new Metronome(44100.0, 1);
        woodblock.setClickSound(ClickSound.WOODBLOCK);

        Metronome electronic = new Metronome(44100.0, 1);
        electronic.setClickSound(ClickSound.ELECTRONIC);

        float[][] wbClick = woodblock.generateClick(true);
        float[][] elClick = electronic.generateClick(true);

        // The clicks have different frequencies, so at least one sample should differ
        boolean differs = false;
        int length = Math.min(wbClick[0].length, elClick[0].length);
        for (int i = 0; i < length; i++) {
            if (wbClick[0][i] != elClick[0][i]) {
                differs = true;
                break;
            }
        }
        assertThat(differs).isTrue();
    }

    // ── Subdivision tests ───────────────────────────────────────────────

    @Test
    void shouldDefaultToQuarterSubdivision() {
        Metronome metronome = new Metronome(44100.0, 1);

        assertThat(metronome.getSubdivision()).isEqualTo(Subdivision.QUARTER);
    }

    @Test
    void shouldSetSubdivision() {
        Metronome metronome = new Metronome(44100.0, 1);

        metronome.setSubdivision(Subdivision.EIGHTH);

        assertThat(metronome.getSubdivision()).isEqualTo(Subdivision.EIGHTH);
    }

    @Test
    void shouldRejectNullSubdivision() {
        Metronome metronome = new Metronome(44100.0, 1);

        assertThatThrownBy(() -> metronome.setSubdivision(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void eighthSubdivisionShouldHaveMoreClicksThanQuarter() {
        Metronome quarter = new Metronome(44100.0, 1);
        quarter.setSubdivision(Subdivision.QUARTER);

        Metronome eighth = new Metronome(44100.0, 1);
        eighth.setSubdivision(Subdivision.EIGHTH);

        float[][] quarterAudio = quarter.generateCountIn(CountInMode.ONE_BAR, 120.0, 4);
        float[][] eighthAudio = eighth.generateCountIn(CountInMode.ONE_BAR, 120.0, 4);

        // Same buffer length but more non-zero regions
        assertThat(quarterAudio[0].length).isEqualTo(eighthAudio[0].length);
        assertThat(countNonZeroRegions(eighthAudio[0]))
                .isGreaterThan(countNonZeroRegions(quarterAudio[0]));
    }

    @Test
    void sixteenthSubdivisionShouldHaveMoreClicksThanEighth() {
        Metronome eighth = new Metronome(44100.0, 1);
        eighth.setSubdivision(Subdivision.EIGHTH);

        Metronome sixteenth = new Metronome(44100.0, 1);
        sixteenth.setSubdivision(Subdivision.SIXTEENTH);

        float[][] eighthAudio = eighth.generateCountIn(CountInMode.ONE_BAR, 120.0, 4);
        float[][] sixteenthAudio = sixteenth.generateCountIn(CountInMode.ONE_BAR, 120.0, 4);

        assertThat(countNonZeroRegions(sixteenthAudio[0]))
                .isGreaterThan(countNonZeroRegions(eighthAudio[0]));
    }

    @Test
    void subdivisionClicksShouldBeQuieterThanMainBeats() {
        Metronome metronome = new Metronome(44100.0, 1);
        metronome.setSubdivision(Subdivision.EIGHTH);

        float[][] audio = metronome.generateCountIn(CountInMode.ONE_BAR, 120.0, 4);

        // At 120 BPM, beat interval = 0.5 sec = 22050 samples
        // First beat click starts at sample 0 (accent)
        // Subdivision click between beat 0 and beat 1 starts at ~11025 samples
        double mainBeatRms = rmsRegion(audio[0], 0, 882);
        double subClickRms = rmsRegion(audio[0], 11025, 11025 + 882);

        assertThat(mainBeatRms).isGreaterThan(subClickRms);
    }

    @Test
    void allSamplesShouldBeWithinValidRangeWithSubdivisions() {
        Metronome metronome = new Metronome(44100.0, 1);
        metronome.setSubdivision(Subdivision.SIXTEENTH);

        float[][] audio = metronome.generateCountIn(CountInMode.TWO_BARS, 120.0, 4);

        for (float sample : audio[0]) {
            assertThat(sample).isBetween(-1.0f, 1.0f);
        }
    }

    // ── Combined configuration tests ────────────────────────────────────

    @Test
    void shouldCombineVolumeAndClickSound() {
        Metronome metronome = new Metronome(44100.0, 1);
        metronome.setVolume(0.3f);
        metronome.setClickSound(ClickSound.ELECTRONIC);

        float[][] click = metronome.generateClick(true);

        // Should produce non-zero audio
        assertThat(rms(click[0])).isGreaterThan(0.0);

        // Should be quieter than full volume
        Metronome fullVolume = new Metronome(44100.0, 1);
        fullVolume.setClickSound(ClickSound.ELECTRONIC);
        float[][] fullClick = fullVolume.generateClick(true);

        assertThat(rms(fullClick[0])).isGreaterThan(rms(click[0]));
    }

    @Test
    void shouldCombineSubdivisionAndClickSound() {
        Metronome metronome = new Metronome(44100.0, 1);
        metronome.setSubdivision(Subdivision.SIXTEENTH);
        metronome.setClickSound(ClickSound.COWBELL);

        float[][] audio = metronome.generateCountIn(CountInMode.ONE_BAR, 120.0, 4);

        assertThat(audio[0].length).isEqualTo(88200);
        // 4 beats * 4 subdivisions = 16 click regions
        assertThat(countNonZeroRegions(audio[0])).isEqualTo(16);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static double rms(float[] samples) {
        double sum = 0.0;
        for (float sample : samples) {
            sum += (double) sample * sample;
        }
        return Math.sqrt(sum / samples.length);
    }

    private static double rmsRegion(float[] samples, int start, int end) {
        end = Math.min(end, samples.length);
        double sum = 0.0;
        int count = end - start;
        for (int i = start; i < end; i++) {
            sum += (double) samples[i] * samples[i];
        }
        return Math.sqrt(sum / count);
    }

    /**
     * Counts distinct non-zero regions in the sample buffer.
     * A region is a contiguous run of non-zero samples.
     */
    private static int countNonZeroRegions(float[] samples) {
        int regions = 0;
        boolean inRegion = false;
        for (float sample : samples) {
            if (sample != 0.0f) {
                if (!inRegion) {
                    regions++;
                    inRegion = true;
                }
            } else {
                inRegion = false;
            }
        }
        return regions;
    }
}
