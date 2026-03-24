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

        RoomTelemetryData data = new RoomTelemetryData(dims, List.of(path), 0.45, List.of(suggestion), List.of());

        assertThat(data.roomDimensions()).isEqualTo(dims);
        assertThat(data.wavePaths()).hasSize(1);
        assertThat(data.estimatedRt60Seconds()).isEqualTo(0.45);
        assertThat(data.suggestions()).hasSize(1);
        assertThat(data.audienceMembers()).isEmpty();
    }

    @Test
    void shouldRejectNullDimensions() {
        assertThatThrownBy(() -> new RoomTelemetryData(null, List.of(), 0.5, List.of(), List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNegativeRt60() {
        assertThatThrownBy(() -> new RoomTelemetryData(
                new RoomDimensions(10, 8, 3), List.of(), -1.0, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldReturnDefensiveCopyOfPaths() {
        RoomDimensions dims = new RoomDimensions(10, 8, 3);
        RoomTelemetryData data = new RoomTelemetryData(dims, List.of(), 0.5, List.of(), List.of());

        assertThatThrownBy(() -> data.wavePaths().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldReturnDefensiveCopyOfSuggestions() {
        RoomDimensions dims = new RoomDimensions(10, 8, 3);
        RoomTelemetryData data = new RoomTelemetryData(dims, List.of(), 0.5, List.of(), List.of());

        assertThatThrownBy(() -> data.suggestions().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldStoreAudienceMembers() {
        RoomDimensions dims = new RoomDimensions(10, 8, 3);
        AudienceMember member = new AudienceMember("Audience A1", new Position3D(3, 5, 0));
        RoomTelemetryData data = new RoomTelemetryData(dims, List.of(), 0.5, List.of(), List.of(member));

        assertThat(data.audienceMembers()).hasSize(1);
        assertThat(data.audienceMembers().getFirst().name()).isEqualTo("Audience A1");
    }

    @Test
    void shouldReturnDefensiveCopyOfAudienceMembers() {
        RoomDimensions dims = new RoomDimensions(10, 8, 3);
        RoomTelemetryData data = new RoomTelemetryData(dims, List.of(), 0.5, List.of(), List.of());

        assertThatThrownBy(() -> data.audienceMembers().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldRejectNullAudienceMembers() {
        RoomDimensions dims = new RoomDimensions(10, 8, 3);
        assertThatThrownBy(() -> new RoomTelemetryData(dims, List.of(), 0.5, List.of(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldCreateWithoutAudienceViaFactory() {
        RoomDimensions dims = new RoomDimensions(10, 8, 3);
        RoomTelemetryData data = RoomTelemetryData.withoutAudience(dims, List.of(), 0.5, List.of());

        assertThat(data.audienceMembers()).isEmpty();
        assertThat(data.roomDimensions()).isEqualTo(dims);
    }

    @Test
    void shouldSupportMultipleAudienceMembers() {
        RoomDimensions dims = new RoomDimensions(10, 8, 3);
        List<AudienceMember> members = List.of(
                new AudienceMember("Row 1 Seat 1", new Position3D(2, 5, 0)),
                new AudienceMember("Row 1 Seat 2", new Position3D(3, 5, 0)),
                new AudienceMember("Row 2 Seat 1", new Position3D(2, 6, 0))
        );
        RoomTelemetryData data = new RoomTelemetryData(dims, List.of(), 0.5, List.of(), members);

        assertThat(data.audienceMembers()).hasSize(3);
    }
}
