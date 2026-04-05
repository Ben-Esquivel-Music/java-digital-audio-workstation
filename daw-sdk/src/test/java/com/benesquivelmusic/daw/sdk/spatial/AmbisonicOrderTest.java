package com.benesquivelmusic.daw.sdk.spatial;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AmbisonicOrderTest {

    @Test
    void shouldReturnCorrectOrder() {
        assertThat(AmbisonicOrder.FIRST.order()).isEqualTo(1);
        assertThat(AmbisonicOrder.SECOND.order()).isEqualTo(2);
        assertThat(AmbisonicOrder.THIRD.order()).isEqualTo(3);
    }

    @Test
    void shouldReturnCorrectChannelCount() {
        assertThat(AmbisonicOrder.FIRST.channelCount()).isEqualTo(4);
        assertThat(AmbisonicOrder.SECOND.channelCount()).isEqualTo(9);
        assertThat(AmbisonicOrder.THIRD.channelCount()).isEqualTo(16);
    }
}
