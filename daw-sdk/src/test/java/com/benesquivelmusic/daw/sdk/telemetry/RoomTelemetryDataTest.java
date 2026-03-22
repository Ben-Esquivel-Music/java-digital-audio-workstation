package com.benesquivelmusic.daw.sdk.telemetry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoomTelemetryDataTest {

    @Test
    void shouldCreateWithValidData() {
        RoomDimensions dims = new RoomDimensions(10, 8, 3);
        SoundWavePath path = new SoundWavePath("Src", "Mic",
                List.of(new Position3D(0, 0, 0), new Position3D(5, 4, 1.5)),
                6.7, 19.5, -16.5, false);
        TelemetrySuggestion.AddDampening suggestion = new TelemetrySuggestion.AddDampening("walls", "too much reverb");

        RoomTelemetryData data = new RoomTelemetryData(dims, List.of(path), 0.45, List.of(suggestion));

        assertThat(data.roomDimensions()).isEqualTo(dims);
        assertThat(data.wavePaths()).hasSize(1);
        assertThat(data.estimatedRt60Seconds()).isEqualTo(0.45);
        assertThat(data.suggestions()).hasSize(1);
    }

    @Test
    void shouldRejectNullDimensions() {
        assertThatThrownBy(() -> new RoomTelemetryData(null, List.of(), 0.5, List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNegativeRt60() {
        assertThatThrownBy(() -> new RoomTelemetryData(
                new RoomDimensions(10, 8, 3), List.of(), -1.0, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldReturnDefensiveCopyOfPaths() {
        RoomDimensions dims = new RoomDimensions(10, 8, 3);
        RoomTelemetryData data = new RoomTelemetryData(dims, List.of(), 0.5, List.of());

        assertThatThrownBy(() -> data.wavePaths().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldReturnDefensiveCopyOfSuggestions() {
        RoomDimensions dims = new RoomDimensions(10, 8, 3);
        RoomTelemetryData data = new RoomTelemetryData(dims, List.of(), 0.5, List.of());

        assertThatThrownBy(() -> data.suggestions().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
