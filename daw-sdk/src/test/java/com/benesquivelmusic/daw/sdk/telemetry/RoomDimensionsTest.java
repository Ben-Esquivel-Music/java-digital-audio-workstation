package com.benesquivelmusic.daw.sdk.telemetry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class RoomDimensionsTest {

    @Test
    void shouldCreateWithValidDimensions() {
        RoomDimensions room = new RoomDimensions(10.0, 8.0, 3.0);

        assertThat(room.width()).isEqualTo(10.0);
        assertThat(room.length()).isEqualTo(8.0);
        assertThat(room.height()).isEqualTo(3.0);
    }

    @Test
    void shouldComputeVolume() {
        RoomDimensions room = new RoomDimensions(10.0, 8.0, 3.0);

        assertThat(room.volume()).isCloseTo(240.0, offset(0.001));
    }

    @Test
    void shouldComputeSurfaceArea() {
        RoomDimensions room = new RoomDimensions(10.0, 8.0, 3.0);

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

    @Test
    void legacyConstructorShouldProduceFlatCeiling() {
        RoomDimensions room = new RoomDimensions(10.0, 8.0, 3.0);

        assertThat(room.ceiling()).isInstanceOf(CeilingShape.Flat.class);
        assertThat(((CeilingShape.Flat) room.ceiling()).height()).isEqualTo(3.0);
    }

    @Test
    void heightShouldReturnMaxHeightForCurvedCeiling() {
        CeilingShape.Domed dome = new CeilingShape.Domed(3.0, 8.0);
        RoomDimensions room = new RoomDimensions(10.0, 10.0, dome);

        assertThat(room.height()).isEqualTo(8.0);
    }

    @Test
    void volumeShouldIntegrateDomedCeiling() {
        CeilingShape.Domed dome = new CeilingShape.Domed(3.0, 8.0);
        RoomDimensions room = new RoomDimensions(10.0, 10.0, dome);

        double expected = 10 * 10 * 3.0 + 4.0 * 10 * 10 * 5.0 / (Math.PI * Math.PI);
        assertThat(room.volume()).isCloseTo(expected, offset(1e-9));
    }

    @Test
    void volumeShouldIntegrateCathedralCeiling() {
        CeilingShape.Cathedral c = new CeilingShape.Cathedral(3.0, 7.0, CeilingShape.Axis.X);
        RoomDimensions room = new RoomDimensions(10.0, 8.0, c);

        assertThat(room.volume()).isCloseTo(10 * 8 * 5.0, offset(1e-9));
    }

    @Test
    void surfaceAreaShouldIncludeFloorCeilingAndWallsForFlat() {
        RoomDimensions room = new RoomDimensions(10.0, 8.0, 3.0);
        // 2*(w*l) + 2*h*(w+l) = 2*80 + 6*18 = 160 + 108 = 268
        assertThat(room.surfaceArea()).isCloseTo(268.0, offset(1e-9));
    }

    @Test
    void surfaceAreaShouldChangeWithCeilingShape() {
        RoomDimensions flat = new RoomDimensions(10.0, 8.0, 3.0);
        RoomDimensions angled = new RoomDimensions(10.0, 8.0,
                new CeilingShape.Angled(3.0, 6.0, CeilingShape.Axis.X));
        // An angled ceiling always has strictly more surface area than an
        // equivalent-volume flat ceiling because the ceiling face itself is
        // tilted.
        assertThat(angled.surfaceArea()).isGreaterThan(flat.surfaceArea());
    }

    @Test
    void shouldRejectNullCeiling() {
        assertThatThrownBy(() -> new RoomDimensions(10.0, 8.0, (CeilingShape) null))
                .isInstanceOf(NullPointerException.class);
    }
}
