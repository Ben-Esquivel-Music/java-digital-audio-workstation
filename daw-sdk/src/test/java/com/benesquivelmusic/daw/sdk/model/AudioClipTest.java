package com.benesquivelmusic.daw.sdk.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudioClipTest {

    @Test
    void of_createsClipWithSensibleDefaults() {
        AudioClip c = AudioClip.of("vocal.wav", 4.0, 8.0, "/path/vocal.wav");

        assertThat(c.id()).isNotNull();
        assertThat(c.name()).isEqualTo("vocal.wav");
        assertThat(c.startBeat()).isEqualTo(4.0);
        assertThat(c.durationBeats()).isEqualTo(8.0);
        assertThat(c.endBeat()).isEqualTo(12.0);
        assertThat(c.gainDb()).isEqualTo(0.0);
        assertThat(c.reversed()).isFalse();
        assertThat(c.locked()).isFalse();
        assertThat(c.fadeInBeats()).isZero();
        assertThat(c.fadeOutBeats()).isZero();
    }

    @Test
    void withX_doesNotMutateOriginal() {
        AudioClip a = AudioClip.of("x", 0.0, 1.0, null);
        AudioClip b = a.withGainDb(-6.0).withReversed(true).withLocked(true);

        assertThat(a.gainDb()).isEqualTo(0.0);
        assertThat(a.reversed()).isFalse();
        assertThat(a.locked()).isFalse();

        assertThat(b.gainDb()).isEqualTo(-6.0);
        assertThat(b.reversed()).isTrue();
        assertThat(b.locked()).isTrue();
        assertThat(b.id()).isEqualTo(a.id());
    }

    @Test
    void structuralEquality_isFieldByField() {
        AudioClip a = AudioClip.of("x", 0.0, 1.0, "f").withGainDb(-3.0);
        AudioClip b = a.withId(a.id()); // same fields → same value

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);

        AudioClip c = a.withGainDb(0.0);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void invalidStartBeat_throws() {
        assertThatThrownBy(() -> AudioClip.of("x", -0.001, 1.0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidDuration_throws() {
        assertThatThrownBy(() -> AudioClip.of("x", 0.0, 0.0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidFade_throws() {
        AudioClip c = AudioClip.of("x", 0.0, 1.0, null);
        assertThatThrownBy(() -> c.withFadeInBeats(-0.01))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.withFadeOutBeats(-0.01))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
