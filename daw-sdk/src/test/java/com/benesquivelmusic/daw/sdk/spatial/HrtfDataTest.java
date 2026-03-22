package com.benesquivelmusic.daw.sdk.spatial;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HrtfDataTest {

    @Test
    void shouldCreateValidHrtfData() {
        List<SphericalCoordinate> positions = List.of(
                new SphericalCoordinate(0, 0, 1.0),
                new SphericalCoordinate(90, 0, 1.0)
        );
        float[][][] ir = {
                {{0.5f, 0.3f}, {0.4f, 0.2f}},
                {{0.6f, 0.1f}, {0.3f, 0.5f}}
        };
        float[][] delays = {{0.0f, 1.0f}, {1.0f, 0.0f}};

        HrtfData data = new HrtfData("Test", 44100.0, positions, ir, delays);

        assertThat(data.profileName()).isEqualTo("Test");
        assertThat(data.sampleRate()).isEqualTo(44100.0);
        assertThat(data.measurementCount()).isEqualTo(2);
        assertThat(data.receiverCount()).isEqualTo(2);
        assertThat(data.irLength()).isEqualTo(2);
    }

    @Test
    void shouldRejectNullProfileName() {
        List<SphericalCoordinate> pos = List.of(new SphericalCoordinate(0, 0, 1.0));
        assertThatThrownBy(() -> new HrtfData(null, 44100, pos,
                new float[1][2][4], new float[1][2]))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNonPositiveSampleRate() {
        List<SphericalCoordinate> pos = List.of(new SphericalCoordinate(0, 0, 1.0));
        assertThatThrownBy(() -> new HrtfData("X", 0, pos,
                new float[1][2][4], new float[1][2]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampleRate");
    }

    @Test
    void shouldRejectEmptySourcePositions() {
        assertThatThrownBy(() -> new HrtfData("X", 44100, List.of(),
                new float[0][2][4], new float[0][2]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourcePositions");
    }

    @Test
    void shouldRejectMismatchedIrLength() {
        List<SphericalCoordinate> pos = List.of(new SphericalCoordinate(0, 0, 1.0));
        assertThatThrownBy(() -> new HrtfData("X", 44100, pos,
                new float[2][2][4], new float[1][2]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("impulseResponses");
    }

    @Test
    void shouldRejectMismatchedDelayLength() {
        List<SphericalCoordinate> pos = List.of(new SphericalCoordinate(0, 0, 1.0));
        assertThatThrownBy(() -> new HrtfData("X", 44100, pos,
                new float[1][2][4], new float[2][2]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("delays");
    }

    @Test
    void shouldMakeDefensiveCopyOfPositions() {
        java.util.ArrayList<SphericalCoordinate> mutableList = new java.util.ArrayList<>(
                List.of(new SphericalCoordinate(0, 0, 1.0)));
        HrtfData data = new HrtfData("Test", 44100, mutableList,
                new float[1][2][4], new float[1][2]);
        mutableList.add(new SphericalCoordinate(90, 0, 1.0));
        assertThat(data.sourcePositions()).hasSize(1);
    }
}
