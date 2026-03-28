package com.benesquivelmusic.daw.core.reference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ReferenceTrackTest {

    @Test
    void shouldCreateWithDefaults() {
        ReferenceTrack ref = new ReferenceTrack("Commercial Mix", "/audio/ref.wav");

        assertThat(ref.getId()).isNotNull();
        assertThat(ref.getName()).isEqualTo("Commercial Mix");
        assertThat(ref.getSourceFilePath()).isEqualTo("/audio/ref.wav");
        assertThat(ref.getGainOffsetDb()).isEqualTo(0.0);
        assertThat(ref.isLoopEnabled()).isFalse();
        assertThat(ref.getLoopStartInBeats()).isEqualTo(0.0);
        assertThat(ref.getLoopEndInBeats()).isEqualTo(0.0);
        assertThat(ref.getIntegratedLufs()).isEqualTo(-120.0);
        assertThat(ref.getAudioData()).isNull();
    }

    @Test
    void shouldRejectNullName() {
        assertThatThrownBy(() -> new ReferenceTrack(null, "/audio/ref.wav"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullSourceFilePath() {
        assertThatThrownBy(() -> new ReferenceTrack("Ref", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldSetName() {
        ReferenceTrack ref = new ReferenceTrack("Ref", "/audio/ref.wav");
        ref.setName("Renamed");
        assertThat(ref.getName()).isEqualTo("Renamed");
    }

    @Test
    void shouldRejectNullSetName() {
        ReferenceTrack ref = new ReferenceTrack("Ref", "/audio/ref.wav");
        assertThatThrownBy(() -> ref.setName(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldSetSourceFilePath() {
        ReferenceTrack ref = new ReferenceTrack("Ref", "/audio/ref.wav");
        ref.setSourceFilePath("/audio/new.wav");
        assertThat(ref.getSourceFilePath()).isEqualTo("/audio/new.wav");
    }

    @Test
    void shouldRejectNullSetSourceFilePath() {
        ReferenceTrack ref = new ReferenceTrack("Ref", "/audio/ref.wav");
        assertThatThrownBy(() -> ref.setSourceFilePath(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldSetAudioData() {
        ReferenceTrack ref = new ReferenceTrack("Ref", "/audio/ref.wav");
        float[][] data = {{0.5f, -0.5f}, {0.3f, -0.3f}};
        ref.setAudioData(data);
        assertThat(ref.getAudioData()).isEqualTo(data);
    }

    @Test
    void shouldClearAudioData() {
        ReferenceTrack ref = new ReferenceTrack("Ref", "/audio/ref.wav");
        ref.setAudioData(new float[][]{{1.0f}});
        ref.setAudioData(null);
        assertThat(ref.getAudioData()).isNull();
    }

    @Test
    void shouldSetGainOffsetDb() {
        ReferenceTrack ref = new ReferenceTrack("Ref", "/audio/ref.wav");
        ref.setGainOffsetDb(-3.5);
        assertThat(ref.getGainOffsetDb()).isEqualTo(-3.5);
    }

    @Test
    void shouldCalculateGainMultiplier() {
        ReferenceTrack ref = new ReferenceTrack("Ref", "/audio/ref.wav");
        // 0 dB = gain of 1.0
        assertThat(ref.getGainMultiplier()).isCloseTo(1.0, within(0.001));

        // -6 dB ≈ 0.501
        ref.setGainOffsetDb(-6.0);
        assertThat(ref.getGainMultiplier()).isCloseTo(0.501, within(0.01));

        // +6 dB ≈ 1.995
        ref.setGainOffsetDb(6.0);
        assertThat(ref.getGainMultiplier()).isCloseTo(1.995, within(0.01));
    }

    @Test
    void shouldSetLoopEnabled() {
        ReferenceTrack ref = new ReferenceTrack("Ref", "/audio/ref.wav");
        ref.setLoopEnabled(true);
        assertThat(ref.isLoopEnabled()).isTrue();
        ref.setLoopEnabled(false);
        assertThat(ref.isLoopEnabled()).isFalse();
    }

    @Test
    void shouldSetLoopRegion() {
        ReferenceTrack ref = new ReferenceTrack("Ref", "/audio/ref.wav");
        ref.setLoopRegion(4.0, 20.0);
        assertThat(ref.getLoopStartInBeats()).isEqualTo(4.0);
        assertThat(ref.getLoopEndInBeats()).isEqualTo(20.0);
    }

    @Test
    void shouldRejectNegativeLoopStart() {
        ReferenceTrack ref = new ReferenceTrack("Ref", "/audio/ref.wav");
        assertThatThrownBy(() -> ref.setLoopRegion(-1.0, 16.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectLoopEndNotGreaterThanStart() {
        ReferenceTrack ref = new ReferenceTrack("Ref", "/audio/ref.wav");
        assertThatThrownBy(() -> ref.setLoopRegion(8.0, 8.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ref.setLoopRegion(8.0, 4.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldSetIntegratedLufs() {
        ReferenceTrack ref = new ReferenceTrack("Ref", "/audio/ref.wav");
        ref.setIntegratedLufs(-14.0);
        assertThat(ref.getIntegratedLufs()).isEqualTo(-14.0);
    }

    @Test
    void shouldHaveUniqueIds() {
        ReferenceTrack ref1 = new ReferenceTrack("Ref1", "/audio/ref1.wav");
        ReferenceTrack ref2 = new ReferenceTrack("Ref2", "/audio/ref2.wav");
        assertThat(ref1.getId()).isNotEqualTo(ref2.getId());
    }
}
