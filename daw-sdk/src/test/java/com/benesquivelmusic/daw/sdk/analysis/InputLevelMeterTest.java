package com.benesquivelmusic.daw.sdk.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InputLevelMeterTest {

    @Test
    void silenceConstantMatchesDbFloor() {
        assertThat(InputLevelMeter.SILENCE.peakDbfs()).isEqualTo(InputLevelMeter.DB_FLOOR);
        assertThat(InputLevelMeter.SILENCE.rmsDbfs()).isEqualTo(InputLevelMeter.DB_FLOOR);
        assertThat(InputLevelMeter.SILENCE.clippedSinceReset()).isFalse();
        assertThat(InputLevelMeter.SILENCE.lastClipFrameIndex()).isEqualTo(-1L);
    }

    @Test
    void recordShouldBeImmutableValueCarrier() {
        InputLevelMeter a = new InputLevelMeter(-6.0, -12.0, true, 1234L);
        InputLevelMeter b = new InputLevelMeter(-6.0, -12.0, true, 1234L);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void accessorsShouldReturnConstructorValues() {
        InputLevelMeter m = new InputLevelMeter(-0.5, -18.0, true, 42L);
        assertThat(m.peakDbfs()).isEqualTo(-0.5);
        assertThat(m.rmsDbfs()).isEqualTo(-18.0);
        assertThat(m.clippedSinceReset()).isTrue();
        assertThat(m.lastClipFrameIndex()).isEqualTo(42L);
    }
}
