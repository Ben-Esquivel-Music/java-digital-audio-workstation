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

    // ── Reversed flag tests ─────────────────────────────────────────────────

    @Test
    void shouldDefaultToNotReversed() {
        AudioClip clip = new AudioClip("Test", 0.0, 4.0, null);

        assertThat(clip.isReversed()).isFalse();
    }

    @Test
    void shouldToggleReversed() {
        AudioClip clip = new AudioClip("Test", 0.0, 4.0, null);

        clip.setReversed(true);
        assertThat(clip.isReversed()).isTrue();

        clip.setReversed(false);
        assertThat(clip.isReversed()).isFalse();
    }

    // ── Fade tests ──────────────────────────────────────────────────────────

    @Test
    void shouldDefaultToZeroFades() {
        AudioClip clip = new AudioClip("Test", 0.0, 4.0, null);

        assertThat(clip.getFadeInBeats()).isEqualTo(0.0);
        assertThat(clip.getFadeOutBeats()).isEqualTo(0.0);
    }

    @Test
    void shouldSetFadeIn() {
        AudioClip clip = new AudioClip("Test", 0.0, 8.0, null);

        clip.setFadeInBeats(2.0);
        assertThat(clip.getFadeInBeats()).isEqualTo(2.0);
    }

    @Test
    void shouldSetFadeOut() {
        AudioClip clip = new AudioClip("Test", 0.0, 8.0, null);

        clip.setFadeOutBeats(3.0);
        assertThat(clip.getFadeOutBeats()).isEqualTo(3.0);
    }

    @Test
    void shouldRejectNegativeFadeIn() {
        AudioClip clip = new AudioClip("Test", 0.0, 4.0, null);

        assertThatThrownBy(() -> clip.setFadeInBeats(-1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeFadeOut() {
        AudioClip clip = new AudioClip("Test", 0.0, 4.0, null);

        assertThatThrownBy(() -> clip.setFadeOutBeats(-1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Duplicate tests ─────────────────────────────────────────────────────

    @Test
    void shouldDuplicateClip() {
        AudioClip original = new AudioClip("Lead", 4.0, 8.0, "/audio/lead.wav");
        original.setSourceOffsetBeats(1.0);
        original.setGainDb(-3.0);
        original.setReversed(true);
        original.setFadeInBeats(0.5);
        original.setFadeOutBeats(1.5);

        AudioClip copy = original.duplicate();

        assertThat(copy.getId()).isNotEqualTo(original.getId());
        assertThat(copy.getName()).isEqualTo("Lead");
        assertThat(copy.getStartBeat()).isEqualTo(4.0);
        assertThat(copy.getDurationBeats()).isEqualTo(8.0);
        assertThat(copy.getSourceFilePath()).isEqualTo("/audio/lead.wav");
        assertThat(copy.getSourceOffsetBeats()).isEqualTo(1.0);
        assertThat(copy.getGainDb()).isEqualTo(-3.0);
        assertThat(copy.isReversed()).isTrue();
        assertThat(copy.getFadeInBeats()).isEqualTo(0.5);
        assertThat(copy.getFadeOutBeats()).isEqualTo(1.5);
    }

    // ── Split tests ─────────────────────────────────────────────────────────

    @Test
    void shouldSplitClipAtPlayhead() {
        AudioClip clip = new AudioClip("Vocal", 4.0, 8.0, "/audio/vocal.wav");
        clip.setGainDb(-2.0);

        AudioClip second = clip.splitAt(8.0);

        // First half: beats 4..8
        assertThat(clip.getStartBeat()).isEqualTo(4.0);
        assertThat(clip.getDurationBeats()).isEqualTo(4.0);
        assertThat(clip.getEndBeat()).isEqualTo(8.0);

        // Second half: beats 8..12
        assertThat(second.getStartBeat()).isEqualTo(8.0);
        assertThat(second.getDurationBeats()).isEqualTo(4.0);
        assertThat(second.getEndBeat()).isEqualTo(12.0);
        assertThat(second.getName()).isEqualTo("Vocal (split)");
        assertThat(second.getSourceFilePath()).isEqualTo("/audio/vocal.wav");
        assertThat(second.getSourceOffsetBeats()).isEqualTo(4.0);
        assertThat(second.getGainDb()).isEqualTo(-2.0);
    }

    @Test
    void shouldPreserveFadesOnSplit() {
        AudioClip clip = new AudioClip("Vocal", 0.0, 16.0, null);
        clip.setFadeInBeats(2.0);
        clip.setFadeOutBeats(3.0);

        AudioClip second = clip.splitAt(8.0);

        // First half keeps fade-in, loses fade-out
        assertThat(clip.getFadeInBeats()).isEqualTo(2.0);
        assertThat(clip.getFadeOutBeats()).isEqualTo(0.0);

        // Second half loses fade-in, keeps fade-out
        assertThat(second.getFadeInBeats()).isEqualTo(0.0);
        assertThat(second.getFadeOutBeats()).isEqualTo(3.0);
    }

    @Test
    void shouldRejectSplitAtStart() {
        AudioClip clip = new AudioClip("Test", 4.0, 8.0, null);

        assertThatThrownBy(() -> clip.splitAt(4.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectSplitAtEnd() {
        AudioClip clip = new AudioClip("Test", 4.0, 8.0, null);

        assertThatThrownBy(() -> clip.splitAt(12.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectSplitOutsideBounds() {
        AudioClip clip = new AudioClip("Test", 4.0, 8.0, null);

        assertThatThrownBy(() -> clip.splitAt(20.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
