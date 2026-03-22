package com.benesquivelmusic.daw.sdk.spatial;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PanAutomationPointTest {

    @Test
    void shouldCreateValidPoint() {
        var point = new PanAutomationPoint(4.0, 90.0, 30.0, 1.5);
        assertThat(point.timeBeat()).isEqualTo(4.0);
        assertThat(point.azimuthDegrees()).isEqualTo(90.0);
        assertThat(point.elevationDegrees()).isEqualTo(30.0);
        assertThat(point.distanceMeters()).isEqualTo(1.5);
    }

    @Test
    void shouldRejectNegativeTimeBeat() {
        assertThatThrownBy(() -> new PanAutomationPoint(-1.0, 0, 0, 1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeBeat");
    }

    @Test
    void shouldRejectElevationOutOfRange() {
        assertThatThrownBy(() -> new PanAutomationPoint(0, 0, 91, 1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("elevationDegrees");
    }

    @Test
    void shouldRejectNegativeDistance() {
        assertThatThrownBy(() -> new PanAutomationPoint(0, 0, 0, -1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("distanceMeters");
    }

    @Test
    void shouldConvertToSpatialPosition() {
        var point = new PanAutomationPoint(0, 45.0, 10.0, 2.0);
        var pos = point.toSpatialPosition();
        assertThat(pos.azimuthDegrees()).isEqualTo(45.0);
        assertThat(pos.elevationDegrees()).isEqualTo(10.0);
        assertThat(pos.distanceMeters()).isEqualTo(2.0);
    }

    @Test
    void shouldNormalizeAzimuthInConversion() {
        var point = new PanAutomationPoint(0, -90.0, 0, 1.0);
        var pos = point.toSpatialPosition();
        assertThat(pos.azimuthDegrees()).isEqualTo(270.0);
    }
}
