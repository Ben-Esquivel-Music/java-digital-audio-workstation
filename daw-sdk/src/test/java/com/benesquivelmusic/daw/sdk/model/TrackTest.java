package com.benesquivelmusic.daw.sdk.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrackTest {

    @Test
    void of_createsTrackWithDefaults() {
        Track t = Track.of("Vocals", TrackType.AUDIO);

        assertThat(t.id()).isNotNull();
        assertThat(t.name()).isEqualTo("Vocals");
        assertThat(t.type()).isEqualTo(TrackType.AUDIO);
        assertThat(t.volume()).isEqualTo(1.0);
        assertThat(t.pan()).isEqualTo(0.0);
        assertThat(t.muted()).isFalse();
        assertThat(t.solo()).isFalse();
        assertThat(t.armed()).isFalse();
        assertThat(t.phaseInverted()).isFalse();
        assertThat(t.clipIds()).isEmpty();
        assertThat(t.mixerChannelId()).isNull();
    }

    @Test
    void withX_returnsNewInstanceLeavingOriginalUntouched() {
        Track original = Track.of("Drums", TrackType.AUDIO);
        Track louder   = original.withVolume(0.75);
        Track muted    = louder.withMuted(true);
        Track armed    = muted.withArmed(true);

        assertThat(original.volume()).isEqualTo(1.0);     // original immutable
        assertThat(original.muted()).isFalse();
        assertThat(louder.volume()).isEqualTo(0.75);
        assertThat(louder.muted()).isFalse();
        assertThat(muted.muted()).isTrue();
        assertThat(armed.armed()).isTrue();
        assertThat(armed.id()).isEqualTo(original.id());  // identity preserved
    }

    @Test
    void structuralEquality_holdsAcrossDifferentInstances() {
        UUID id = UUID.randomUUID();
        Track a = new Track(id, "T", TrackType.MIDI, 0.5, 0.1,
                false, false, false, false, List.of(), null);
        Track b = new Track(id, "T", TrackType.MIDI, 0.5, 0.1,
                false, false, false, false, List.of(), null);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }

    @Test
    void clipIdsList_isImmutableInTheRecord() {
        Track t = Track.of("T", TrackType.AUDIO).withClipIds(List.of(UUID.randomUUID()));
        // The defensive copy in the constructor freezes the list.
        assertThatThrownBy(() -> t.clipIds().add(UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void invalidVolume_throws() {
        assertThatThrownBy(() -> Track.of("T", TrackType.AUDIO).withVolume(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Track.of("T", TrackType.AUDIO).withVolume(1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidPan_throws() {
        assertThatThrownBy(() -> Track.of("T", TrackType.AUDIO).withPan(-1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
