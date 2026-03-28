package com.benesquivelmusic.daw.sdk.export;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExportRangeTest {

    @Test
    void fullRangeShouldBeFullProject() {
        ExportRange range = ExportRange.FULL;
        assertThat(range.isFullProject()).isTrue();
        assertThat(range.startSeconds()).isEqualTo(0.0);
        assertThat(range.endSeconds()).isEqualTo(Double.MAX_VALUE);
    }

    @Test
    void customRangeShouldNotBeFullProject() {
        ExportRange range = new ExportRange(1.0, 5.0);
        assertThat(range.isFullProject()).isFalse();
        assertThat(range.startSeconds()).isEqualTo(1.0);
        assertThat(range.endSeconds()).isEqualTo(5.0);
    }

    @Test
    void shouldRejectNegativeStartSeconds() {
        assertThatThrownBy(() -> new ExportRange(-1.0, 5.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startSeconds");
    }

    @Test
    void shouldRejectEndNotGreaterThanStart() {
        assertThatThrownBy(() -> new ExportRange(5.0, 5.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endSeconds");

        assertThatThrownBy(() -> new ExportRange(5.0, 3.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endSeconds");
    }

    @Test
    void extractRangeShouldReturnFullDataForFullRange() {
        float[][] audio = new float[2][44100];
        for (int i = 0; i < 44100; i++) {
            audio[0][i] = (float) i / 44100;
            audio[1][i] = (float) i / 44100;
        }

        float[][] result = ExportRange.FULL.extractRange(audio, 44100);
        assertThat(result).isSameAs(audio);
    }

    @Test
    void extractRangeShouldTrimToSpecifiedRange() {
        int sampleRate = 44100;
        float[][] audio = new float[2][sampleRate * 10]; // 10 seconds
        for (int i = 0; i < audio[0].length; i++) {
            audio[0][i] = (float) i;
            audio[1][i] = (float) -i;
        }

        ExportRange range = new ExportRange(1.0, 3.0);
        float[][] result = range.extractRange(audio, sampleRate);

        int expectedStart = sampleRate; // 1 second
        int expectedLength = sampleRate * 2; // 2 seconds
        assertThat(result.length).isEqualTo(2);
        assertThat(result[0].length).isEqualTo(expectedLength);
        assertThat(result[0][0]).isEqualTo((float) expectedStart);
        assertThat(result[1][0]).isEqualTo((float) -expectedStart);
    }

    @Test
    void extractRangeShouldHandleEndBeyondAudio() {
        int sampleRate = 44100;
        float[][] audio = new float[1][sampleRate * 2]; // 2 seconds
        for (int i = 0; i < audio[0].length; i++) {
            audio[0][i] = (float) i;
        }

        ExportRange range = new ExportRange(1.0, 5.0);
        float[][] result = range.extractRange(audio, sampleRate);

        // Should only get 1 second of data (from 1s to 2s)
        assertThat(result[0].length).isEqualTo(sampleRate);
        assertThat(result[0][0]).isEqualTo((float) sampleRate);
    }

    @Test
    void extractRangeShouldHandleEmptyAudio() {
        float[][] audio = new float[1][0];
        ExportRange range = new ExportRange(0.0, 1.0);
        float[][] result = range.extractRange(audio, 44100);
        assertThat(result[0].length).isEqualTo(0);
    }
}
