package com.benesquivelmusic.daw.sdk.spatial;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AmbisonicFormatTest {

    @Test
    void shouldCreateFoaFormat() {
        var format = AmbisonicFormat.FOA;
        assertThat(format.order()).isEqualTo(AmbisonicOrder.FIRST);
        assertThat(format.channelCount()).isEqualTo(4);
    }

    @Test
    void shouldCreateSecondOrderFormat() {
        var format = AmbisonicFormat.SECOND_ORDER;
        assertThat(format.order()).isEqualTo(AmbisonicOrder.SECOND);
        assertThat(format.channelCount()).isEqualTo(9);
    }

    @Test
    void shouldCreateThirdOrderFormat() {
        var format = AmbisonicFormat.THIRD_ORDER;
        assertThat(format.order()).isEqualTo(AmbisonicOrder.THIRD);
        assertThat(format.channelCount()).isEqualTo(16);
    }

    @Test
    void shouldCreateFromOrder() {
        var format = new AmbisonicFormat(AmbisonicOrder.FIRST);
        assertThat(format.channelCount()).isEqualTo(4);
    }

    @Test
    void shouldRejectNullOrder() {
        assertThatThrownBy(() -> new AmbisonicFormat(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectMismatchedChannelCount() {
        assertThatThrownBy(() -> new AmbisonicFormat(AmbisonicOrder.FIRST, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channelCount must be (order+1)²");
    }
}
