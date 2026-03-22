package com.benesquivelmusic.daw.sdk.session;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionDataTest {

    @Test
    void shouldCreateSessionData() {
        var clip = new SessionData.SessionClip("Clip 1", 0.0, 4.0, 0.0, "audio/kick.wav", -3.0);
        var track = new SessionData.SessionTrack("Drums", "AUDIO", 0.8, -0.5, false, true, List.of(clip));
        var session = new SessionData("My Song", 128.0, 4, 4, 48000.0, List.of(track));

        assertThat(session.projectName()).isEqualTo("My Song");
        assertThat(session.tempo()).isEqualTo(128.0);
        assertThat(session.timeSignatureNumerator()).isEqualTo(4);
        assertThat(session.timeSignatureDenominator()).isEqualTo(4);
        assertThat(session.sampleRate()).isEqualTo(48000.0);
        assertThat(session.tracks()).hasSize(1);
    }

    @Test
    void shouldReturnDefensiveCopyOfTracks() {
        var session = new SessionData("Test", 120.0, 4, 4, 44100.0, List.of());

        assertThatThrownBy(() -> session.tracks().add(
                new SessionData.SessionTrack("X", "AUDIO", 1.0, 0.0, false, false, List.of())))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldRejectNullProjectName() {
        assertThatThrownBy(() -> new SessionData(null, 120.0, 4, 4, 44100.0, List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullTracks() {
        assertThatThrownBy(() -> new SessionData("Test", 120.0, 4, 4, 44100.0, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldCreateSessionTrack() {
        var track = new SessionData.SessionTrack("Bass", "AUDIO", 0.7, 0.3, true, false, List.of());

        assertThat(track.name()).isEqualTo("Bass");
        assertThat(track.type()).isEqualTo("AUDIO");
        assertThat(track.volume()).isEqualTo(0.7);
        assertThat(track.pan()).isEqualTo(0.3);
        assertThat(track.muted()).isTrue();
        assertThat(track.solo()).isFalse();
        assertThat(track.clips()).isEmpty();
    }

    @Test
    void shouldCreateSessionClip() {
        var clip = new SessionData.SessionClip("Vocal Take", 4.0, 8.0, 1.0, "audio/vocal.wav", -6.0);

        assertThat(clip.name()).isEqualTo("Vocal Take");
        assertThat(clip.startBeat()).isEqualTo(4.0);
        assertThat(clip.durationBeats()).isEqualTo(8.0);
        assertThat(clip.sourceOffsetBeats()).isEqualTo(1.0);
        assertThat(clip.sourceFilePath()).isEqualTo("audio/vocal.wav");
        assertThat(clip.gainDb()).isEqualTo(-6.0);
    }

    @Test
    void shouldAllowNullSourceFilePath() {
        var clip = new SessionData.SessionClip("Empty", 0.0, 1.0, 0.0, null, 0.0);
        assertThat(clip.sourceFilePath()).isNull();
    }

    @Test
    void shouldRejectNullClipName() {
        assertThatThrownBy(() -> new SessionData.SessionClip(null, 0.0, 1.0, 0.0, null, 0.0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullTrackName() {
        assertThatThrownBy(() -> new SessionData.SessionTrack(null, "AUDIO", 1.0, 0.0, false, false, List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullTrackType() {
        assertThatThrownBy(() -> new SessionData.SessionTrack("X", null, 1.0, 0.0, false, false, List.of()))
                .isInstanceOf(NullPointerException.class);
    }
}
