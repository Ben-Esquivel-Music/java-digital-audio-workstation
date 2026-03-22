package com.benesquivelmusic.daw.sdk.visualization;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoudnessHistoryPointTest {

    @Test
    void shouldStoreTimestampAndLoudnessValues() {
        LoudnessHistoryPoint point = new LoudnessHistoryPoint(1.5, -14.0, -16.0, -15.0);

        assertThat(point.timestampSeconds()).isEqualTo(1.5);
        assertThat(point.momentaryLufs()).isEqualTo(-14.0);
        assertThat(point.shortTermLufs()).isEqualTo(-16.0);
        assertThat(point.integratedLufs()).isEqualTo(-15.0);
    }

    @Test
    void shouldImplementEqualityForRecords() {
        LoudnessHistoryPoint a = new LoudnessHistoryPoint(1.0, -14.0, -16.0, -15.0);
        LoudnessHistoryPoint b = new LoudnessHistoryPoint(1.0, -14.0, -16.0, -15.0);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void shouldNotBeEqualWhenValuessDiffer() {
        LoudnessHistoryPoint a = new LoudnessHistoryPoint(1.0, -14.0, -16.0, -15.0);
        LoudnessHistoryPoint b = new LoudnessHistoryPoint(2.0, -14.0, -16.0, -15.0);

        assertThat(a).isNotEqualTo(b);
    }
}
