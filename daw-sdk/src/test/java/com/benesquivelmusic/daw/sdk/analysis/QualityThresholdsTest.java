package com.benesquivelmusic.daw.sdk.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QualityThresholdsTest {

    @Test
    void defaultThresholdsShouldHaveReasonableValues() {
        QualityThresholds t = QualityThresholds.DEFAULT;

        assertThat(t.minSnrDb()).isGreaterThan(0.0);
        assertThat(t.maxThdPercent()).isGreaterThan(0.0);
        assertThat(t.minCrestFactorDb()).isGreaterThan(0.0);
        assertThat(t.minBandwidthUtilization()).isBetween(0.0, 1.0);
        assertThat(t.minCorrelationCoefficient()).isBetween(-1.0, 1.0);
        assertThat(t.minStereoWidthConsistency()).isBetween(0.0, 1.0);
        assertThat(t.minMonoCompatibilityScore()).isBetween(0.0, 1.0);
        assertThat(t.minPlrDb()).isGreaterThan(0.0);
        assertThat(t.minDrScore()).isGreaterThan(0.0);
    }

    @Test
    void shouldSupportCustomThresholds() {
        QualityThresholds custom = new QualityThresholds(20.0, 5.0, 2.0, 0.1, 0.2,
                -0.3, 0.4, 0.5, 2.0, 3.0);

        assertThat(custom.minSnrDb()).isEqualTo(20.0);
        assertThat(custom.maxThdPercent()).isEqualTo(5.0);
        assertThat(custom.minCrestFactorDb()).isEqualTo(2.0);
        assertThat(custom.minSpectralFlatness()).isEqualTo(0.1);
        assertThat(custom.minBandwidthUtilization()).isEqualTo(0.2);
        assertThat(custom.minCorrelationCoefficient()).isEqualTo(-0.3);
        assertThat(custom.minStereoWidthConsistency()).isEqualTo(0.4);
        assertThat(custom.minMonoCompatibilityScore()).isEqualTo(0.5);
        assertThat(custom.minPlrDb()).isEqualTo(2.0);
        assertThat(custom.minDrScore()).isEqualTo(3.0);
    }
}
