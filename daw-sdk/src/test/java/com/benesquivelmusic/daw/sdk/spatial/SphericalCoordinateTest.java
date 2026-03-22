package com.benesquivelmusic.daw.sdk.spatial;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class SphericalCoordinateTest {

    @Test
    void shouldCreateValidCoordinate() {
        var coord = new SphericalCoordinate(90.0, 45.0, 1.2);
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
        var coord = new SphericalCoordinate(0, 0, 0.0);
        assertThat(coord.distanceMeters()).isZero();
    }

    @Test
    void shouldComputeZeroAngularDistanceForSameDirection() {
        var a = new SphericalCoordinate(45.0, 30.0, 1.0);
        var b = new SphericalCoordinate(45.0, 30.0, 2.0);
        assertThat(a.angularDistanceTo(b)).isCloseTo(0.0, within(1e-10));
    }

    @Test
    void shouldComputeAngularDistanceFrontToBack() {
        var front = new SphericalCoordinate(0, 0, 1.0);
        var back = new SphericalCoordinate(180, 0, 1.0);
        assertThat(front.angularDistanceTo(back)).isCloseTo(180.0, within(1e-10));
    }

    @Test
    void shouldComputeAngularDistanceFrontToLeft() {
        var front = new SphericalCoordinate(0, 0, 1.0);
        var left = new SphericalCoordinate(90, 0, 1.0);
        assertThat(front.angularDistanceTo(left)).isCloseTo(90.0, within(1e-10));
    }

    @Test
    void shouldComputeAngularDistanceFrontToAbove() {
        var front = new SphericalCoordinate(0, 0, 1.0);
        var above = new SphericalCoordinate(0, 90, 1.0);
        assertThat(front.angularDistanceTo(above)).isCloseTo(90.0, within(1e-10));
    }

    @Test
    void shouldNormalizeNegativeAzimuth() {
        var coord = new SphericalCoordinate(-90, 0, 1.0);
        var normalized = coord.normalize();
        assertThat(normalized.azimuthDegrees()).isCloseTo(270.0, within(1e-10));
    }

    @Test
    void shouldNormalizeAzimuthAbove360() {
        var coord = new SphericalCoordinate(450, 0, 1.0);
        var normalized = coord.normalize();
        assertThat(normalized.azimuthDegrees()).isCloseTo(90.0, within(1e-10));
    }

    @Test
    void shouldNormalizeAlreadyValidAzimuth() {
        var coord = new SphericalCoordinate(180, 0, 1.0);
        var normalized = coord.normalize();
        assertThat(normalized.azimuthDegrees()).isCloseTo(180.0, within(1e-10));
    }

    @Test
    void shouldBeSymmetricAngularDistance() {
        var a = new SphericalCoordinate(30, 20, 1.0);
        var b = new SphericalCoordinate(120, -10, 1.0);
        assertThat(a.angularDistanceTo(b)).isCloseTo(b.angularDistanceTo(a), within(1e-10));
    }
}
