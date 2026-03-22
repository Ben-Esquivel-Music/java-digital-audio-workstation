package com.benesquivelmusic.daw.sdk.telemetry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SoundWavePathTest {

    @Test
    void shouldCreateDirectPath() {
        var src = new Position3D(0, 0, 0);
        var mic = new Position3D(3, 4, 0);
        var path = new SoundWavePath("Guitar", "Mic1", List.of(src, mic), 5.0, 14.58, -13.98, false);

        assertThat(path.sourceName()).isEqualTo("Guitar");
        assertThat(path.microphoneName()).isEqualTo("Mic1");
        assertThat(path.waypoints()).hasSize(2);
        assertThat(path.totalDistance()).isEqualTo(5.0);
        assertThat(path.reflected()).isFalse();
    }

    @Test
    void shouldCreateReflectedPath() {
        var src = new Position3D(0, 0, 0);
        var wall = new Position3D(5, 2, 0);
        var mic = new Position3D(3, 4, 0);
        var path = new SoundWavePath("Guitar", "Mic1", List.of(src, wall, mic), 8.0, 23.3, -18.0, true);

        assertThat(path.waypoints()).hasSize(3);
        assertThat(path.reflected()).isTrue();
    }

    @Test
    void shouldRejectNullSourceName() {
        assertThatThrownBy(() -> new SoundWavePath(null, "Mic", List.of(
                new Position3D(0, 0, 0), new Position3D(1, 1, 1)), 1.0, 1.0, -5.0, false))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectTooFewWaypoints() {
        assertThatThrownBy(() -> new SoundWavePath("Src", "Mic",
                List.of(new Position3D(0, 0, 0)), 1.0, 1.0, -5.0, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeDistance() {
        assertThatThrownBy(() -> new SoundWavePath("Src", "Mic",
                List.of(new Position3D(0, 0, 0), new Position3D(1, 1, 1)),
                -1.0, 1.0, -5.0, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeDelay() {
        assertThatThrownBy(() -> new SoundWavePath("Src", "Mic",
                List.of(new Position3D(0, 0, 0), new Position3D(1, 1, 1)),
                1.0, -1.0, -5.0, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldReturnDefensiveCopyOfWaypoints() {
        var waypoints = new java.util.ArrayList<>(List.of(
                new Position3D(0, 0, 0), new Position3D(1, 1, 1)));
        var path = new SoundWavePath("Src", "Mic", waypoints, 1.0, 1.0, -5.0, false);

        assertThatThrownBy(() -> path.waypoints().add(new Position3D(2, 2, 2)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
