package com.benesquivelmusic.daw.sdk.visualization;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LevelDataTest {

    @Test
    void shouldCreateWithValidParameters() {
        var data = new LevelData(0.8, 0.5, -1.94, -6.02, false);

        assertThat(data.peakLinear()).isEqualTo(0.8);
        assertThat(data.rmsLinear()).isEqualTo(0.5);
        assertThat(data.peakDb()).isEqualTo(-1.94);
        assertThat(data.rmsDb()).isEqualTo(-6.02);
        assertThat(data.clipping()).isFalse();
    }

    @Test
    void shouldProvideSilenceConstant() {
        assertThat(LevelData.SILENCE.peakLinear()).isEqualTo(0.0);
        assertThat(LevelData.SILENCE.rmsLinear()).isEqualTo(0.0);
        assertThat(LevelData.SILENCE.peakDb()).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(LevelData.SILENCE.clipping()).isFalse();
    }

    @Test
    void shouldRejectNegativePeak() {
        assertThatThrownBy(() -> new LevelData(-0.1, 0.0, 0, 0, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeRms() {
        assertThatThrownBy(() -> new LevelData(0.0, -0.1, 0, 0, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
