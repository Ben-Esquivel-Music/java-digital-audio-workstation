package com.benesquivelmusic.daw.sdk.telemetry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoomPresetTest {

    @Test
    void allPresetsShouldHavePositiveDimensions() {
        for (RoomPreset preset : RoomPreset.values()) {
            assertThat(preset.dimensions().width())
                    .as("width for %s", preset)
                    .isGreaterThan(0);
            assertThat(preset.dimensions().length())
                    .as("length for %s", preset)
                    .isGreaterThan(0);
            assertThat(preset.dimensions().height())
                    .as("height for %s", preset)
                    .isGreaterThan(0);
        }
    }

    @Test
    void allPresetsShouldHaveNonNullMaterial() {
        for (RoomPreset preset : RoomPreset.values()) {
            assertThat(preset.wallMaterial())
                    .as("wallMaterial for %s", preset)
                    .isNotNull();
        }
    }

    @Test
    void studioShouldHaveModerateDimensions() {
        RoomPreset studio = RoomPreset.STUDIO;
        assertThat(studio.dimensions().volume()).isBetween(50.0, 500.0);
    }

    @Test
    void cathedralShouldBeLargerThanStudio() {
        assertThat(RoomPreset.CATHEDRAL.dimensions().volume())
                .isGreaterThan(RoomPreset.STUDIO.dimensions().volume());
    }

    @Test
    void recordingBoothShouldUseAbsorptiveMaterial() {
        assertThat(RoomPreset.RECORDING_BOOTH.wallMaterial().absorptionCoefficient())
                .isGreaterThanOrEqualTo(0.5);
    }

    @Test
    void cathedralShouldUseReflectiveMaterial() {
        assertThat(RoomPreset.CATHEDRAL.wallMaterial().absorptionCoefficient())
                .isLessThanOrEqualTo(0.1);
    }

    @Test
    void shouldHaveExpectedPresetCount() {
        assertThat(RoomPreset.values()).hasSize(8);
    }
}
