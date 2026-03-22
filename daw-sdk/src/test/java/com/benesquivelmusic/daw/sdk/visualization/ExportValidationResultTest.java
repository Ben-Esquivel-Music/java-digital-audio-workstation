package com.benesquivelmusic.daw.sdk.visualization;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExportValidationResultTest {

    @Test
    void shouldPassWhenBothChecksPass() {
        ExportValidationResult result = new ExportValidationResult(
                LoudnessTarget.SPOTIFY, -14.0, -2.0,
                true, true, "Spotify: PASS");

        assertThat(result.passed()).isTrue();
        assertThat(result.loudnessPass()).isTrue();
        assertThat(result.truePeakPass()).isTrue();
    }

    @Test
    void shouldFailWhenLoudnessCheckFails() {
        ExportValidationResult result = new ExportValidationResult(
                LoudnessTarget.SPOTIFY, -10.0, -2.0,
                false, true, "FAIL");

        assertThat(result.passed()).isFalse();
        assertThat(result.loudnessPass()).isFalse();
        assertThat(result.truePeakPass()).isTrue();
    }

    @Test
    void shouldFailWhenTruePeakCheckFails() {
        ExportValidationResult result = new ExportValidationResult(
                LoudnessTarget.SPOTIFY, -14.0, 0.0,
                true, false, "FAIL");

        assertThat(result.passed()).isFalse();
        assertThat(result.truePeakPass()).isFalse();
    }

    @Test
    void shouldFailWhenBothChecksFail() {
        ExportValidationResult result = new ExportValidationResult(
                LoudnessTarget.SPOTIFY, -10.0, 0.0,
                false, false, "FAIL");

        assertThat(result.passed()).isFalse();
    }

    @Test
    void shouldExposeTarget() {
        ExportValidationResult result = new ExportValidationResult(
                LoudnessTarget.APPLE_MUSIC, -16.0, -2.0,
                true, true, "Apple Music: PASS");

        assertThat(result.target()).isEqualTo(LoudnessTarget.APPLE_MUSIC);
    }

    @Test
    void shouldExposeMeasuredValues() {
        ExportValidationResult result = new ExportValidationResult(
                LoudnessTarget.YOUTUBE, -14.5, -1.5,
                true, true, "YouTube: PASS");

        assertThat(result.measuredIntegratedLufs()).isEqualTo(-14.5);
        assertThat(result.measuredTruePeakDbtp()).isEqualTo(-1.5);
    }
}
