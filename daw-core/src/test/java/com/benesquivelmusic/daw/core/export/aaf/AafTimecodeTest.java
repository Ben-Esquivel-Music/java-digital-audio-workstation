package com.benesquivelmusic.daw.core.export.aaf;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AafTimecodeTest {

    @Test
    void zeroTimecodeFormatsCorrectly() {
        AafTimecode tc = AafTimecode.zero(AafFrameRate.FPS_24);
        assertThat(tc.toString()).isEqualTo("00:00:00:00");
    }

    @Test
    void hourStartTimecodeRoundTripsAtAllSupportedRates() {
        for (AafFrameRate fr : AafFrameRate.values()) {
            AafTimecode tc = new AafTimecode(1, 0, 0, 0, fr);
            long samples = tc.toSampleOffset(48000);
            AafTimecode back = AafTimecode.fromSamples(samples, 48000, fr);
            assertThat(back).isEqualTo(tc);
        }
    }

    @Test
    void parseAcceptsBothColonAndSemicolonSeparators() {
        AafTimecode a = AafTimecode.parse("01:02:03:04", AafFrameRate.FPS_24);
        AafTimecode b = AafTimecode.parse("01:02:03;04", AafFrameRate.FPS_29_97);
        assertThat(a.hours()).isEqualTo(1);
        assertThat(b.frames()).isEqualTo(4);
    }

    @Test
    void dropFrameTimecodeFormatsWithSemicolon() {
        AafTimecode tc = new AafTimecode(0, 0, 1, 5, AafFrameRate.FPS_29_97);
        assertThat(tc.toString()).isEqualTo("00:00:01;05");
    }

    @Test
    void invalidTimecodeFieldsAreRejected() {
        assertThatThrownBy(() -> new AafTimecode(0, 60, 0, 0, AafFrameRate.FPS_24))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AafTimecode(0, 0, 0, 24, AafFrameRate.FPS_24))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AafTimecode.parse("not a tc", AafFrameRate.FPS_24))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sampleOffsetAtOneHourMatchesSampleRate() {
        AafTimecode tc = new AafTimecode(1, 0, 0, 0, AafFrameRate.FPS_24);
        assertThat(tc.toSampleOffset(48000)).isEqualTo(48000L * 3600L);
    }
}
