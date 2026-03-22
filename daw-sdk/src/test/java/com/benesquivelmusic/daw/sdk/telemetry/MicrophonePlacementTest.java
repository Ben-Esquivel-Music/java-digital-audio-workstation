package com.benesquivelmusic.daw.sdk.telemetry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MicrophonePlacementTest {

    @Test
    void shouldCreateWithValidParameters() {
        Position3D pos = new Position3D(2.0, 3.0, 1.5);
        MicrophonePlacement mic = new MicrophonePlacement("Overhead", pos, 90.0, 0.0);

        assertThat(mic.name()).isEqualTo("Overhead");
        assertThat(mic.position()).isEqualTo(pos);
        assertThat(mic.azimuth()).isEqualTo(90.0);
        assertThat(mic.elevation()).isEqualTo(0.0);
    }

    @Test
    void shouldRejectNullName() {
        assertThatThrownBy(() -> new MicrophonePlacement(null, new Position3D(0, 0, 0), 0, 0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullPosition() {
        assertThatThrownBy(() -> new MicrophonePlacement("Mic", null, 0, 0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNegativeAzimuth() {
        assertThatThrownBy(() -> new MicrophonePlacement("Mic", new Position3D(0, 0, 0), -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectAzimuthAtOrAbove360() {
        assertThatThrownBy(() -> new MicrophonePlacement("Mic", new Position3D(0, 0, 0), 360, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectElevationOutOfRange() {
        assertThatThrownBy(() -> new MicrophonePlacement("Mic", new Position3D(0, 0, 0), 0, 91))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MicrophonePlacement("Mic", new Position3D(0, 0, 0), 0, -91))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAllowBoundaryElevation() {
        MicrophonePlacement mic90 = new MicrophonePlacement("Up", new Position3D(0, 0, 0), 0, 90);
        assertThat(mic90.elevation()).isEqualTo(90);

        MicrophonePlacement micNeg90 = new MicrophonePlacement("Down", new Position3D(0, 0, 0), 0, -90);
        assertThat(micNeg90.elevation()).isEqualTo(-90);
    }
}
