package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SampleRateTest {

    @Test
    void shouldReturnCorrectHzValues() {
        assertThat(SampleRate.HZ_44100.getHz()).isEqualTo(44_100);
        assertThat(SampleRate.HZ_48000.getHz()).isEqualTo(48_000);
        assertThat(SampleRate.HZ_88200.getHz()).isEqualTo(88_200);
        assertThat(SampleRate.HZ_96000.getHz()).isEqualTo(96_000);
        assertThat(SampleRate.HZ_176400.getHz()).isEqualTo(176_400);
        assertThat(SampleRate.HZ_192000.getHz()).isEqualTo(192_000);
    }

    @Test
    void shouldLookUpFromHz() {
        assertThat(SampleRate.fromHz(44_100)).isEqualTo(SampleRate.HZ_44100);
        assertThat(SampleRate.fromHz(96_000)).isEqualTo(SampleRate.HZ_96000);
        assertThat(SampleRate.fromHz(192_000)).isEqualTo(SampleRate.HZ_192000);
    }

    @Test
    void shouldRejectUnsupportedHz() {
        assertThatThrownBy(() -> SampleRate.fromHz(22_050))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("22050");
    }

    @Test
    void shouldHaveSixStandardRates() {
        assertThat(SampleRate.values()).hasSize(6);
    }
}
