package com.benesquivelmusic.daw.sdk.mastering;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TruePeakCeilingPresetTest {

    @Test
    void spotifyPresetShouldBeMinusOne() {
        assertThat(TruePeakCeilingPreset.SPOTIFY.getCeilingDbtp()).isEqualTo(-1.0);
    }

    @Test
    void appleMusicPresetShouldBeMinusOne() {
        assertThat(TruePeakCeilingPreset.APPLE_MUSIC.getCeilingDbtp()).isEqualTo(-1.0);
    }

    @Test
    void youtubePresetShouldBeMinusOne() {
        assertThat(TruePeakCeilingPreset.YOUTUBE.getCeilingDbtp()).isEqualTo(-1.0);
    }

    @Test
    void broadcastEbuPresetShouldBeMinusOne() {
        assertThat(TruePeakCeilingPreset.BROADCAST_EBU.getCeilingDbtp()).isEqualTo(-1.0);
    }

    @Test
    void broadcastAtscPresetShouldBeMinusTwo() {
        assertThat(TruePeakCeilingPreset.BROADCAST_ATSC.getCeilingDbtp()).isEqualTo(-2.0);
    }

    @Test
    void broadcastStrictPresetShouldBeMinusHalf() {
        assertThat(TruePeakCeilingPreset.BROADCAST_STRICT.getCeilingDbtp()).isEqualTo(-0.5);
    }

    @Test
    void masteringPresetShouldBeMinusPointThree() {
        assertThat(TruePeakCeilingPreset.MASTERING.getCeilingDbtp()).isEqualTo(-0.3);
    }

    @Test
    void allPresetsShouldHaveNonPositiveCeiling() {
        for (TruePeakCeilingPreset preset : TruePeakCeilingPreset.values()) {
            assertThat(preset.getCeilingDbtp())
                    .as("Preset %s ceiling should be <= 0", preset.name())
                    .isLessThanOrEqualTo(0.0);
        }
    }
}
