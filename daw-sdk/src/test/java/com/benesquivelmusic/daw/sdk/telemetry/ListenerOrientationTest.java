package com.benesquivelmusic.daw.sdk.telemetry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListenerOrientationTest {

    @Test
    void shouldCreateWithValidParameters() {
        var pos = new Position3D(3.0, 4.0, 1.2);
        var orientation = new ListenerOrientation(pos, 45.0, 10.0);

        assertThat(orientation.position()).isEqualTo(pos);
        assertThat(orientation.yawDegrees()).isEqualTo(45.0);
        assertThat(orientation.pitchDegrees()).isEqualTo(10.0);
    }

    @Test
    void shouldAcceptZeroYawAndPitch() {
        var orientation = new ListenerOrientation(new Position3D(0, 0, 0), 0.0, 0.0);

        assertThat(orientation.yawDegrees()).isEqualTo(0.0);
        assertThat(orientation.pitchDegrees()).isEqualTo(0.0);
    }

    @Test
    void shouldAcceptBoundaryValues() {
        var pos = new Position3D(1, 1, 1);

        // Max valid yaw (just below 360)
        var atMaxYaw = new ListenerOrientation(pos, 359.9, 0.0);
        assertThat(atMaxYaw.yawDegrees()).isEqualTo(359.9);

        // Extreme pitch values
        var atMaxPitch = new ListenerOrientation(pos, 0.0, 90.0);
        assertThat(atMaxPitch.pitchDegrees()).isEqualTo(90.0);

        var atMinPitch = new ListenerOrientation(pos, 0.0, -90.0);
        assertThat(atMinPitch.pitchDegrees()).isEqualTo(-90.0);
    }

    @Test
    void shouldRejectNullPosition() {
        assertThatThrownBy(() -> new ListenerOrientation(null, 0.0, 0.0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNegativeYaw() {
        assertThatThrownBy(() -> new ListenerOrientation(new Position3D(0, 0, 0), -1.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectYawAtOrAbove360() {
        assertThatThrownBy(() -> new ListenerOrientation(new Position3D(0, 0, 0), 360.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectPitchBelowMinus90() {
        assertThatThrownBy(() -> new ListenerOrientation(new Position3D(0, 0, 0), 0.0, -91.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectPitchAbove90() {
        assertThatThrownBy(() -> new ListenerOrientation(new Position3D(0, 0, 0), 0.0, 91.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
