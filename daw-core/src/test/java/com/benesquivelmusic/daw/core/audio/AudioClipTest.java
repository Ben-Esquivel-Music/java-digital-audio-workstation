package com.benesquivelmusic.daw.core.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudioClipTest {

    @Test
    void shouldCreateClip() {
        AudioClip clip = new AudioClip("Vocal Take 1", 0.0, 16.0, "/audio/take1.wav");

        assertThat(clip.getId()).isNotNull();
        assertThat(clip.getName()).isEqualTo("Vocal Take 1");
        assertThat(clip.getStartBeat()).isEqualTo(0.0);
        assertThat(clip.getDurationBeats()).isEqualTo(16.0);
        assertThat(clip.getSourceOffsetBeats()).isEqualTo(0.0);
        assertThat(clip.getSourceFilePath()).isEqualTo("/audio/take1.wav");
        assertThat(clip.getGainDb()).isEqualTo(0.0);
    }

    @Test
    void shouldComputeEndBeat() {
        AudioClip clip = new AudioClip("Test", 4.0, 8.0, null);

        assertThat(clip.getEndBeat()).isEqualTo(12.0);
    }

    @Test
    void shouldUpdatePosition() {
        AudioClip clip = new AudioClip("Test", 0.0, 4.0, null);

        clip.setStartBeat(8.0);
        assertThat(clip.getStartBeat()).isEqualTo(8.0);
        assertThat(clip.getEndBeat()).isEqualTo(12.0);
    }

    @Test
    void shouldUpdateDuration() {
        AudioClip clip = new AudioClip("Test", 0.0, 4.0, null);

        clip.setDurationBeats(16.0);
        assertThat(clip.getDurationBeats()).isEqualTo(16.0);
    }

    @Test
    void shouldUpdateName() {
        AudioClip clip = new AudioClip("Original", 0.0, 4.0, null);

        clip.setName("Renamed");
        assertThat(clip.getName()).isEqualTo("Renamed");
    }

    @Test
    void shouldUpdateSourceOffset() {
        AudioClip clip = new AudioClip("Test", 0.0, 4.0, null);

        clip.setSourceOffsetBeats(2.0);
        assertThat(clip.getSourceOffsetBeats()).isEqualTo(2.0);
    }

    @Test
    void shouldUpdateGain() {
        AudioClip clip = new AudioClip("Test", 0.0, 4.0, null);

        clip.setGainDb(-6.0);
        assertThat(clip.getGainDb()).isEqualTo(-6.0);
    }

    @Test
    void shouldRejectNegativeStartBeat() {
        assertThatThrownBy(() -> new AudioClip("Test", -1.0, 4.0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNonPositiveDuration() {
        assertThatThrownBy(() -> new AudioClip("Test", 0.0, 0.0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullName() {
        assertThatThrownBy(() -> new AudioClip(null, 0.0, 4.0, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectSetNegativeStartBeat() {
        AudioClip clip = new AudioClip("Test", 0.0, 4.0, null);

        assertThatThrownBy(() -> clip.setStartBeat(-1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectSetNonPositiveDuration() {
        AudioClip clip = new AudioClip("Test", 0.0, 4.0, null);

        assertThatThrownBy(() -> clip.setDurationBeats(0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAllowNullSourceFilePath() {
        AudioClip clip = new AudioClip("Test", 0.0, 4.0, null);

        assertThat(clip.getSourceFilePath()).isNull();
    }

    @Test
    void shouldHaveUniqueIds() {
        AudioClip clip1 = new AudioClip("A", 0.0, 1.0, null);
        AudioClip clip2 = new AudioClip("B", 0.0, 1.0, null);

        assertThat(clip1.getId()).isNotEqualTo(clip2.getId());
    }
}
