package com.benesquivelmusic.daw.sdk.telemetry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class RoomDimensionsTest {

    @Test
    void shouldCreateWithValidDimensions() {
        var room = new RoomDimensions(10.0, 8.0, 3.0);

        assertThat(room.width()).isEqualTo(10.0);
        assertThat(room.length()).isEqualTo(8.0);
        assertThat(room.height()).isEqualTo(3.0);
    }

    @Test
    void shouldComputeVolume() {
        var room = new RoomDimensions(10.0, 8.0, 3.0);

        assertThat(room.volume()).isCloseTo(240.0, offset(0.001));
    }

    @Test
    void shouldComputeSurfaceArea() {
        var room = new RoomDimensions(10.0, 8.0, 3.0);

        // 2*(10*8 + 10*3 + 8*3) = 2*(80+30+24) = 268
        assertThat(room.surfaceArea()).isCloseTo(268.0, offset(0.001));
    }

    @Test
    void shouldRejectZeroWidth() {
        assertThatThrownBy(() -> new RoomDimensions(0, 8.0, 3.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeLength() {
        assertThatThrownBy(() -> new RoomDimensions(10.0, -1.0, 3.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeHeight() {
        assertThatThrownBy(() -> new RoomDimensions(10.0, 8.0, -3.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
