package com.benesquivelmusic.daw.core.track;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrackTest {

    @Test
    void shouldCreateTrackWithDefaults() {
        Track track = new Track("Vocals", TrackType.AUDIO);

        assertThat(track.getName()).isEqualTo("Vocals");
        assertThat(track.getType()).isEqualTo(TrackType.AUDIO);
        assertThat(track.getId()).isNotBlank();
        assertThat(track.getVolume()).isEqualTo(1.0);
        assertThat(track.getPan()).isEqualTo(0.0);
        assertThat(track.isMuted()).isFalse();
        assertThat(track.isSolo()).isFalse();
        assertThat(track.isArmed()).isFalse();
    }

    @Test
    void shouldSetVolume() {
        Track track = new Track("Track", TrackType.AUDIO);
        track.setVolume(0.5);
        assertThat(track.getVolume()).isEqualTo(0.5);
    }

    @Test
    void shouldRejectInvalidVolume() {
        Track track = new Track("Track", TrackType.AUDIO);
        assertThatThrownBy(() -> track.setVolume(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> track.setVolume(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldSetPan() {
        Track track = new Track("Track", TrackType.AUDIO);
        track.setPan(-1.0);
        assertThat(track.getPan()).isEqualTo(-1.0);
        track.setPan(1.0);
        assertThat(track.getPan()).isEqualTo(1.0);
    }

    @Test
    void shouldRejectInvalidPan() {
        Track track = new Track("Track", TrackType.AUDIO);
        assertThatThrownBy(() -> track.setPan(-1.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> track.setPan(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldToggleMuteAndSolo() {
        Track track = new Track("Track", TrackType.MIDI);
        track.setMuted(true);
        assertThat(track.isMuted()).isTrue();
        track.setSolo(true);
        assertThat(track.isSolo()).isTrue();
    }

    @Test
    void shouldToggleArmed() {
        Track track = new Track("Track", TrackType.AUDIO);
        assertThat(track.isArmed()).isFalse();
        track.setArmed(true);
        assertThat(track.isArmed()).isTrue();
        track.setArmed(false);
        assertThat(track.isArmed()).isFalse();
    }

    @Test
    void shouldGenerateUniqueIds() {
        Track a = new Track("A", TrackType.AUDIO);
        Track b = new Track("B", TrackType.AUDIO);
        assertThat(a.getId()).isNotEqualTo(b.getId());
    }
}
