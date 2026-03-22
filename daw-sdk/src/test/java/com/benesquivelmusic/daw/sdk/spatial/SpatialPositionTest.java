package com.benesquivelmusic.daw.sdk.spatial;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class SpatialPositionTest {

    // ---- Construction & Validation ----

    @Test
    void shouldCreateValidPosition() {
        var pos = new SpatialPosition(90.0, 45.0, 2.0);
        assertThat(pos.azimuthDegrees()).isEqualTo(90.0);
        assertThat(pos.elevationDegrees()).isEqualTo(45.0);
        assertThat(pos.distanceMeters()).isEqualTo(2.0);
    }

    @Test
    void shouldRejectNegativeDistance() {
        assertThatThrownBy(() -> new SpatialPosition(0, 0, -1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("distanceMeters");
    }

    @Test
    void shouldRejectElevationBelowMinus90() {
        assertThatThrownBy(() -> new SpatialPosition(0, -91, 1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("elevationDegrees");
    }

    @Test
    void shouldRejectElevationAbove90() {
        assertThatThrownBy(() -> new SpatialPosition(0, 91, 1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("elevationDegrees");
    }

    @Test
    void shouldAllowBoundaryElevations() {
        var below = new SpatialPosition(0, -90, 1.0);
        assertThat(below.elevationDegrees()).isEqualTo(-90.0);

        var above = new SpatialPosition(0, 90, 1.0);
        assertThat(above.elevationDegrees()).isEqualTo(90.0);
    }

    // ---- Spherical to Cartesian Conversion ----

    @Test
    void shouldConvertFrontToCartesian() {
        // azimuth=0 (front), elevation=0 → X=0, Y=distance, Z=0
        var front = new SpatialPosition(0, 0, 1.0);
        assertThat(front.x()).isCloseTo(0.0, within(1e-10));
        assertThat(front.y()).isCloseTo(1.0, within(1e-10));
        assertThat(front.z()).isCloseTo(0.0, within(1e-10));
    }

    @Test
    void shouldConvertLeftToCartesian() {
        // azimuth=90 (left), elevation=0 → X=-distance, Y=0, Z=0
        var left = new SpatialPosition(90, 0, 1.0);
        assertThat(left.x()).isCloseTo(-1.0, within(1e-10));
        assertThat(left.y()).isCloseTo(0.0, within(1e-10));
        assertThat(left.z()).isCloseTo(0.0, within(1e-10));
    }

    @Test
    void shouldConvertRightToCartesian() {
        // azimuth=270 (right), elevation=0 → X=distance, Y=0, Z=0
        var right = new SpatialPosition(270, 0, 1.0);
        assertThat(right.x()).isCloseTo(1.0, within(1e-10));
        assertThat(right.y()).isCloseTo(0.0, within(1e-10));
        assertThat(right.z()).isCloseTo(0.0, within(1e-10));
    }

    @Test
    void shouldConvertAboveToCartesian() {
        // azimuth=0, elevation=90 → X=0, Y=0, Z=distance
        var above = new SpatialPosition(0, 90, 1.0);
        assertThat(above.x()).isCloseTo(0.0, within(1e-10));
        assertThat(above.y()).isCloseTo(0.0, within(1e-10));
        assertThat(above.z()).isCloseTo(1.0, within(1e-10));
    }

    @Test
    void shouldScaleCartesianByDistance() {
        var pos = new SpatialPosition(0, 0, 3.0);
        assertThat(pos.y()).isCloseTo(3.0, within(1e-10));
    }

    // ---- Cartesian to Spherical Conversion ----

    @Test
    void shouldConvertCartesianFrontToSpherical() {
        var pos = SpatialPosition.fromCartesian(0, 1, 0);
        assertThat(pos.azimuthDegrees()).isCloseTo(0.0, within(1e-10));
        assertThat(pos.elevationDegrees()).isCloseTo(0.0, within(1e-10));
        assertThat(pos.distanceMeters()).isCloseTo(1.0, within(1e-10));
    }

    @Test
    void shouldConvertCartesianLeftToSpherical() {
        var pos = SpatialPosition.fromCartesian(-1, 0, 0);
        assertThat(pos.azimuthDegrees()).isCloseTo(90.0, within(1e-10));
        assertThat(pos.elevationDegrees()).isCloseTo(0.0, within(1e-10));
        assertThat(pos.distanceMeters()).isCloseTo(1.0, within(1e-10));
    }

    @Test
    void shouldConvertCartesianRightToSpherical() {
        var pos = SpatialPosition.fromCartesian(1, 0, 0);
        assertThat(pos.azimuthDegrees()).isCloseTo(270.0, within(1e-10));
        assertThat(pos.elevationDegrees()).isCloseTo(0.0, within(1e-10));
        assertThat(pos.distanceMeters()).isCloseTo(1.0, within(1e-10));
    }

    @Test
    void shouldConvertCartesianAboveToSpherical() {
        var pos = SpatialPosition.fromCartesian(0, 0, 1);
        assertThat(pos.azimuthDegrees()).isCloseTo(0.0, within(1e-10));
        assertThat(pos.elevationDegrees()).isCloseTo(90.0, within(1e-10));
        assertThat(pos.distanceMeters()).isCloseTo(1.0, within(1e-10));
    }

    @Test
    void shouldHandleOriginCartesian() {
        var pos = SpatialPosition.fromCartesian(0, 0, 0);
        assertThat(pos.distanceMeters()).isEqualTo(0.0);
    }

    // ---- Round-trip Conversion ----

    @Test
    void shouldRoundTripSphericalToCartesianToSpherical() {
        var original = new SpatialPosition(45, 30, 2.5);
        var roundTripped = SpatialPosition.fromCartesian(original.x(), original.y(), original.z());
        assertThat(roundTripped.azimuthDegrees()).isCloseTo(original.azimuthDegrees(), within(1e-9));
        assertThat(roundTripped.elevationDegrees()).isCloseTo(original.elevationDegrees(), within(1e-9));
        assertThat(roundTripped.distanceMeters()).isCloseTo(original.distanceMeters(), within(1e-9));
    }

    @Test
    void shouldRoundTripBackPosition() {
        var original = new SpatialPosition(180, 0, 1.0);
        var roundTripped = SpatialPosition.fromCartesian(original.x(), original.y(), original.z());
        assertThat(roundTripped.azimuthDegrees()).isCloseTo(180.0, within(1e-9));
        assertThat(roundTripped.distanceMeters()).isCloseTo(1.0, within(1e-9));
    }

    // ---- Angular Distance ----

    @Test
    void shouldComputeZeroAngularDistanceForSameDirection() {
        var a = new SpatialPosition(45, 30, 1.0);
        var b = new SpatialPosition(45, 30, 5.0);
        assertThat(a.angularDistanceTo(b)).isCloseTo(0.0, within(1e-10));
    }

    @Test
    void shouldComputeAngularDistanceFrontToBack() {
        var front = new SpatialPosition(0, 0, 1.0);
        var back = new SpatialPosition(180, 0, 1.0);
        assertThat(front.angularDistanceTo(back)).isCloseTo(180.0, within(1e-10));
    }

    @Test
    void shouldComputeAngularDistanceLeftToRight() {
        var left = new SpatialPosition(90, 0, 1.0);
        var right = new SpatialPosition(270, 0, 1.0);
        assertThat(left.angularDistanceTo(right)).isCloseTo(180.0, within(1e-10));
    }

    // ---- Normalize ----

    @Test
    void shouldNormalizeNegativeAzimuth() {
        var pos = new SpatialPosition(-90, 0, 1.0);
        assertThat(pos.normalize().azimuthDegrees()).isCloseTo(270.0, within(1e-10));
    }

    // ---- SphericalCoordinate Conversion ----

    @Test
    void shouldConvertToSphericalCoordinate() {
        var pos = new SpatialPosition(90, 45, 2.0);
        var sc = pos.toSphericalCoordinate();
        assertThat(sc.azimuthDegrees()).isEqualTo(90.0);
        assertThat(sc.elevationDegrees()).isEqualTo(45.0);
        assertThat(sc.distanceMeters()).isEqualTo(2.0);
    }
}
