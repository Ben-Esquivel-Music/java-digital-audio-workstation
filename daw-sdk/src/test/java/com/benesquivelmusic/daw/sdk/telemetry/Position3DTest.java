package com.benesquivelmusic.daw.sdk.telemetry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class Position3DTest {

    @Test
    void shouldStoreCoordinates() {
        Position3D pos = new Position3D(1.5, 2.5, 3.5);

        assertThat(pos.x()).isEqualTo(1.5);
        assertThat(pos.y()).isEqualTo(2.5);
        assertThat(pos.z()).isEqualTo(3.5);
    }

    @Test
    void shouldComputeDistanceToSamePoint() {
        Position3D pos = new Position3D(1.0, 2.0, 3.0);

        assertThat(pos.distanceTo(pos)).isCloseTo(0.0, offset(1e-10));
    }

    @Test
    void shouldComputeDistanceAlongAxis() {
        Position3D a = new Position3D(0, 0, 0);
        Position3D b = new Position3D(3.0, 4.0, 0);

        assertThat(a.distanceTo(b)).isCloseTo(5.0, offset(1e-10));
    }

    @Test
    void shouldComputeDistance3D() {
        Position3D a = new Position3D(1.0, 2.0, 3.0);
        Position3D b = new Position3D(4.0, 6.0, 3.0);

        assertThat(a.distanceTo(b)).isCloseTo(5.0, offset(1e-10));
    }

    @Test
    void shouldBeSymmetric() {
        Position3D a = new Position3D(1.0, 2.0, 3.0);
        Position3D b = new Position3D(4.0, 6.0, 8.0);

        assertThat(a.distanceTo(b)).isCloseTo(b.distanceTo(a), offset(1e-10));
    }
}
