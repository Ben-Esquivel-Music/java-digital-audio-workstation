package com.benesquivelmusic.daw.sdk.telemetry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class Position3DTest {

    @Test
    void shouldStoreCoordinates() {
        var pos = new Position3D(1.5, 2.5, 3.5);

        assertThat(pos.x()).isEqualTo(1.5);
        assertThat(pos.y()).isEqualTo(2.5);
        assertThat(pos.z()).isEqualTo(3.5);
    }

    @Test
    void shouldComputeDistanceToSamePoint() {
        var pos = new Position3D(1.0, 2.0, 3.0);

        assertThat(pos.distanceTo(pos)).isCloseTo(0.0, offset(1e-10));
    }

    @Test
    void shouldComputeDistanceAlongAxis() {
        var a = new Position3D(0, 0, 0);
        var b = new Position3D(3.0, 4.0, 0);

        assertThat(a.distanceTo(b)).isCloseTo(5.0, offset(1e-10));
    }

    @Test
    void shouldComputeDistance3D() {
        var a = new Position3D(1.0, 2.0, 3.0);
        var b = new Position3D(4.0, 6.0, 3.0);

        assertThat(a.distanceTo(b)).isCloseTo(5.0, offset(1e-10));
    }

    @Test
    void shouldBeSymmetric() {
        var a = new Position3D(1.0, 2.0, 3.0);
        var b = new Position3D(4.0, 6.0, 8.0);

        assertThat(a.distanceTo(b)).isCloseTo(b.distanceTo(a), offset(1e-10));
    }
}
