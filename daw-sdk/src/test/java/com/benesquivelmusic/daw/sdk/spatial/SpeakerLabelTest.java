package com.benesquivelmusic.daw.sdk.spatial;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SpeakerLabelTest {

    @Test
    void shouldHaveCorrectAzimuthForFrontSpeakers() {
        assertThat(SpeakerLabel.L.azimuthDegrees()).isEqualTo(30.0);
        assertThat(SpeakerLabel.R.azimuthDegrees()).isEqualTo(330.0);
        assertThat(SpeakerLabel.C.azimuthDegrees()).isEqualTo(0.0);
    }

    @Test
    void shouldHaveCorrectElevationForHeightSpeakers() {
        assertThat(SpeakerLabel.LTF.elevationDegrees()).isEqualTo(45.0);
        assertThat(SpeakerLabel.RTF.elevationDegrees()).isEqualTo(45.0);
        assertThat(SpeakerLabel.LTR.elevationDegrees()).isEqualTo(45.0);
        assertThat(SpeakerLabel.RTR.elevationDegrees()).isEqualTo(45.0);
    }

    @Test
    void shouldHaveZeroElevationForEarLevelSpeakers() {
        assertThat(SpeakerLabel.L.elevationDegrees()).isEqualTo(0.0);
        assertThat(SpeakerLabel.R.elevationDegrees()).isEqualTo(0.0);
        assertThat(SpeakerLabel.LS.elevationDegrees()).isEqualTo(0.0);
        assertThat(SpeakerLabel.RS.elevationDegrees()).isEqualTo(0.0);
    }

    @Test
    void shouldConvertToSpatialPosition() {
        SpatialPosition pos = SpeakerLabel.L.toSpatialPosition();
        assertThat(pos.azimuthDegrees()).isEqualTo(30.0);
        assertThat(pos.elevationDegrees()).isEqualTo(0.0);
        assertThat(pos.distanceMeters()).isEqualTo(1.0);
    }

    @Test
    void shouldConvertHeightSpeakerToSpatialPosition() {
        SpatialPosition pos = SpeakerLabel.LTF.toSpatialPosition();
        assertThat(pos.azimuthDegrees()).isEqualTo(45.0);
        assertThat(pos.elevationDegrees()).isEqualTo(45.0);
        assertThat(pos.distanceMeters()).isEqualTo(1.0);
    }

    @Test
    void shouldHave12SpeakerLabels() {
        assertThat(SpeakerLabel.values()).hasSize(12);
    }
}
