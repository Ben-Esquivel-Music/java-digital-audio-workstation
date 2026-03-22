package com.benesquivelmusic.daw.sdk.spatial;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AmbisonicBusTest {

    @Test
    void shouldCreateBusWithFoaFormat() {
        var bus = new AmbisonicBus("Main", AmbisonicFormat.FOA);
        assertThat(bus.name()).isEqualTo("Main");
        assertThat(bus.channelCount()).isEqualTo(4);
        assertThat(bus.order()).isEqualTo(AmbisonicOrder.FIRST);
    }

    @Test
    void shouldCreateBusWithThirdOrderFormat() {
        var bus = new AmbisonicBus("HOA Bus", AmbisonicFormat.THIRD_ORDER);
        assertThat(bus.channelCount()).isEqualTo(16);
        assertThat(bus.order()).isEqualTo(AmbisonicOrder.THIRD);
    }

    @Test
    void shouldAllocateBuffer() {
        var bus = new AmbisonicBus("Test", AmbisonicFormat.FOA);
        float[][] buffer = bus.allocateBuffer(256);
        assertThat(buffer).hasNumberOfRows(4);
        assertThat(buffer[0]).hasSize(256);
    }

    @Test
    void shouldAllocateZeroFrameBuffer() {
        var bus = new AmbisonicBus("Test", AmbisonicFormat.FOA);
        float[][] buffer = bus.allocateBuffer(0);
        assertThat(buffer).hasNumberOfRows(4);
        assertThat(buffer[0]).hasSize(0);
    }

    @Test
    void shouldRejectNullName() {
        assertThatThrownBy(() -> new AmbisonicBus(null, AmbisonicFormat.FOA))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectBlankName() {
        assertThatThrownBy(() -> new AmbisonicBus("  ", AmbisonicFormat.FOA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void shouldRejectNullFormat() {
        assertThatThrownBy(() -> new AmbisonicBus("Main", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNegativeFrameCount() {
        var bus = new AmbisonicBus("Test", AmbisonicFormat.FOA);
        assertThatThrownBy(() -> bus.allocateBuffer(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
