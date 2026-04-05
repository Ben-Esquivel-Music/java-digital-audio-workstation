package com.benesquivelmusic.daw.sdk.spatial;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class SphericalCoordinateTest {

    @Test
    void shouldCreateValidCoordinate() {
        SphericalCoordinate coord = new SphericalCoordinate(90.0, 45.0, 1.2);
        assertThat(coord.azimuthDegrees()).isEqualTo(90.0);
        assertThat(coord.elevationDegrees()).isEqualTo(45.0);
        assertThat(coord.distanceMeters()).isEqualTo(1.2);
    }

    @Test
    void shouldRejectNegativeDistance() {
        assertThatThrownBy(() -> new SphericalCoordinate(0, 0, -1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("distanceMeters");
    }

    @Test
    void shouldAllowZeroDistance() {
        SphericalCoordinate coord = new SphericalCoordinate(0, 0, 0.0);
        assertThat(coord.distanceMeters()).isZero();
    }

    @Test
    void shouldComputeZeroAngularDistanceForSameDirection() {
        SphericalCoordinate a = new SphericalCoordinate(45.0, 30.0, 1.0);
        SphericalCoordinate b = new SphericalCoordinate(45.0, 30.0, 2.0);
        assertThat(a.angularDistanceTo(b)).isCloseTo(0.0, within(1e-10));
    }

    @Test
    void shouldComputeAngularDistanceFrontToBack() {
        SphericalCoordinate front = new SphericalCoordinate(0, 0, 1.0);
        SphericalCoordinate back = new SphericalCoordinate(180, 0, 1.0);
        assertThat(front.angularDistanceTo(back)).isCloseTo(180.0, within(1e-10));
    }

    @Test
    void shouldComputeAngularDistanceFrontToLeft() {
        SphericalCoordinate front = new SphericalCoordinate(0, 0, 1.0);
        SphericalCoordinate left = new SphericalCoordinate(90, 0, 1.0);
        assertThat(front.angularDistanceTo(left)).isCloseTo(90.0, within(1e-10));
    }

    @Test
    void shouldComputeAngularDistanceFrontToAbove() {
        SphericalCoordinate front = new SphericalCoordinate(0, 0, 1.0);
        SphericalCoordinate above = new SphericalCoordinate(0, 90, 1.0);
        assertThat(front.angularDistanceTo(above)).isCloseTo(90.0, within(1e-10));
    }

    @Test
    void shouldNormalizeNegativeAzimuth() {
        SphericalCoordinate coord = new SphericalCoordinate(-90, 0, 1.0);
        SphericalCoordinate normalized = coord.normalize();
        assertThat(normalized.azimuthDegrees()).isCloseTo(270.0, within(1e-10));
    }

    @Test
    void shouldNormalizeAzimuthAbove360() {
        SphericalCoordinate coord = new SphericalCoordinate(450, 0, 1.0);
        SphericalCoordinate normalized = coord.normalize();
        assertThat(normalized.azimuthDegrees()).isCloseTo(90.0, within(1e-10));
    }

    @Test
    void shouldNormalizeAlreadyValidAzimuth() {
        SphericalCoordinate coord = new SphericalCoordinate(180, 0, 1.0);
        SphericalCoordinate normalized = coord.normalize();
        assertThat(normalized.azimuthDegrees()).isCloseTo(180.0, within(1e-10));
    }

    @Test
    void shouldBeSymmetricAngularDistance() {
        SphericalCoordinate a = new SphericalCoordinate(30, 20, 1.0);
        SphericalCoordinate b = new SphericalCoordinate(120, -10, 1.0);
        assertThat(a.angularDistanceTo(b)).isCloseTo(b.angularDistanceTo(a), within(1e-10));
    }
}
