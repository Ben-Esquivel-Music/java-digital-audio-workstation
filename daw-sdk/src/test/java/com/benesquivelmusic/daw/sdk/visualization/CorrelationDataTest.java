package com.benesquivelmusic.daw.sdk.visualization;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrelationDataTest {

    @Test
    void shouldCreateWithValidParameters() {
        var data = new CorrelationData(0.95, -6.0, -20.0, 0.1);

        assertThat(data.correlation()).isEqualTo(0.95);
        assertThat(data.midLevel()).isEqualTo(-6.0);
        assertThat(data.sideLevel()).isEqualTo(-20.0);
        assertThat(data.stereoBalance()).isEqualTo(0.1);
    }

    @Test
    void shouldProvideSilenceConstant() {
        assertThat(CorrelationData.SILENCE.correlation()).isEqualTo(1.0);
        assertThat(CorrelationData.SILENCE.stereoBalance()).isEqualTo(0.0);
    }

    @Test
    void shouldRejectCorrelationOutOfRange() {
        assertThatThrownBy(() -> new CorrelationData(1.1, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CorrelationData(-1.1, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectBalanceOutOfRange() {
        assertThatThrownBy(() -> new CorrelationData(0.5, 0, 0, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAcceptBoundaryValues() {
        var data = new CorrelationData(-1.0, -120.0, -120.0, -1.0);
        assertThat(data.correlation()).isEqualTo(-1.0);
        assertThat(data.stereoBalance()).isEqualTo(-1.0);
    }
}
