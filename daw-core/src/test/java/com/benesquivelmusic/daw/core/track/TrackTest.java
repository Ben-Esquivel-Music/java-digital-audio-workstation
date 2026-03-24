package com.benesquivelmusic.daw.core.track;

import com.benesquivelmusic.daw.core.audio.AudioClip;

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

    @Test
    void shouldStartWithEmptyClipsList() {
        Track track = new Track("Track", TrackType.AUDIO);
        assertThat(track.getClips()).isEmpty();
    }

    @Test
    void shouldAddClip() {
        Track track = new Track("Track", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Clip 1", 0.0, 4.0, null);

        track.addClip(clip);

        assertThat(track.getClips()).containsExactly(clip);
    }

    @Test
    void shouldRemoveClip() {
        Track track = new Track("Track", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Clip 1", 0.0, 4.0, null);
        track.addClip(clip);

        boolean removed = track.removeClip(clip);

        assertThat(removed).isTrue();
        assertThat(track.getClips()).isEmpty();
    }

    @Test
    void shouldReturnFalseWhenRemovingAbsentClip() {
        Track track = new Track("Track", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Clip 1", 0.0, 4.0, null);

        assertThat(track.removeClip(clip)).isFalse();
    }

    @Test
    void shouldReturnUnmodifiableClipsList() {
        Track track = new Track("Track", TrackType.AUDIO);
        track.addClip(new AudioClip("Clip 1", 0.0, 4.0, null));

        assertThatThrownBy(() -> track.getClips().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldRejectNullClip() {
        Track track = new Track("Track", TrackType.AUDIO);
        assertThatThrownBy(() -> track.addClip(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldDefaultToNoInputDevice() {
        Track track = new Track("Track", TrackType.AUDIO);
        assertThat(track.getInputDeviceIndex()).isEqualTo(Track.NO_INPUT_DEVICE);
    }

    @Test
    void shouldSetInputDeviceIndex() {
        Track track = new Track("Track", TrackType.AUDIO);
        track.setInputDeviceIndex(3);
        assertThat(track.getInputDeviceIndex()).isEqualTo(3);
    }

    @Test
    void shouldClearInputDeviceIndex() {
        Track track = new Track("Track", TrackType.AUDIO);
        track.setInputDeviceIndex(5);
        track.setInputDeviceIndex(Track.NO_INPUT_DEVICE);
        assertThat(track.getInputDeviceIndex()).isEqualTo(Track.NO_INPUT_DEVICE);
    }

    @Test
    void shouldRejectInvalidInputDeviceIndex() {
        Track track = new Track("Track", TrackType.AUDIO);
        assertThatThrownBy(() -> track.setInputDeviceIndex(-2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAcceptZeroInputDeviceIndex() {
        Track track = new Track("Track", TrackType.AUDIO);
        track.setInputDeviceIndex(0);
        assertThat(track.getInputDeviceIndex()).isEqualTo(0);
    }
}
