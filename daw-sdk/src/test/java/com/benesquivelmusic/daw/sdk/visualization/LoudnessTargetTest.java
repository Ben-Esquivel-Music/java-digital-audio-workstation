package com.benesquivelmusic.daw.sdk.visualization;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoudnessTargetTest {

    @Test
    void shouldHaveSpotifyPreset() {
        assertThat(LoudnessTarget.SPOTIFY.targetIntegratedLufs()).isEqualTo(-14.0);
        assertThat(LoudnessTarget.SPOTIFY.maxTruePeakDbtp()).isEqualTo(-1.0);
        assertThat(LoudnessTarget.SPOTIFY.displayName()).isEqualTo("Spotify");
    }

    @Test
    void shouldHaveAppleMusicPreset() {
        assertThat(LoudnessTarget.APPLE_MUSIC.targetIntegratedLufs()).isEqualTo(-16.0);
        assertThat(LoudnessTarget.APPLE_MUSIC.maxTruePeakDbtp()).isEqualTo(-1.0);
        assertThat(LoudnessTarget.APPLE_MUSIC.displayName()).isEqualTo("Apple Music");
    }

    @Test
    void shouldHaveYouTubePreset() {
        assertThat(LoudnessTarget.YOUTUBE.targetIntegratedLufs()).isEqualTo(-14.0);
        assertThat(LoudnessTarget.YOUTUBE.displayName()).isEqualTo("YouTube");
    }

    @Test
    void shouldHaveAmazonMusicPreset() {
        assertThat(LoudnessTarget.AMAZON_MUSIC.targetIntegratedLufs()).isEqualTo(-14.0);
        assertThat(LoudnessTarget.AMAZON_MUSIC.maxTruePeakDbtp()).isEqualTo(-2.0);
        assertThat(LoudnessTarget.AMAZON_MUSIC.displayName()).isEqualTo("Amazon Music");
    }

    @Test
    void shouldHaveTidalPreset() {
        assertThat(LoudnessTarget.TIDAL.targetIntegratedLufs()).isEqualTo(-14.0);
        assertThat(LoudnessTarget.TIDAL.displayName()).isEqualTo("Tidal");
    }

    @Test
    void shouldHaveCdPreset() {
        assertThat(LoudnessTarget.CD.targetIntegratedLufs()).isEqualTo(-9.0);
        assertThat(LoudnessTarget.CD.maxTruePeakDbtp()).isEqualTo(-0.3);
        assertThat(LoudnessTarget.CD.displayName()).isEqualTo("CD");
    }

    @Test
    void shouldHaveGenrePopEdmPreset() {
        assertThat(LoudnessTarget.GENRE_POP_EDM.targetIntegratedLufs()).isEqualTo(-9.0);
        assertThat(LoudnessTarget.GENRE_POP_EDM.displayName()).isEqualTo("Pop/EDM");
    }

    @Test
    void shouldHaveGenreRockPreset() {
        assertThat(LoudnessTarget.GENRE_ROCK.targetIntegratedLufs()).isEqualTo(-11.0);
        assertThat(LoudnessTarget.GENRE_ROCK.displayName()).isEqualTo("Rock");
    }

    @Test
    void shouldHaveGenreJazzClassicalPreset() {
        assertThat(LoudnessTarget.GENRE_JAZZ_CLASSICAL.targetIntegratedLufs()).isEqualTo(-18.0);
        assertThat(LoudnessTarget.GENRE_JAZZ_CLASSICAL.displayName()).isEqualTo("Jazz/Classical");
    }

    @Test
    void shouldHaveGenreHipHopRnbPreset() {
        assertThat(LoudnessTarget.GENRE_HIPHOP_RNB.targetIntegratedLufs()).isEqualTo(-10.0);
        assertThat(LoudnessTarget.GENRE_HIPHOP_RNB.displayName()).isEqualTo("Hip-Hop/R&B");
    }

    @Test
    void shouldContainAllExpectedPlatformAndGenrePresets() {
        assertThat(LoudnessTarget.values()).hasSize(10);
    }
}
