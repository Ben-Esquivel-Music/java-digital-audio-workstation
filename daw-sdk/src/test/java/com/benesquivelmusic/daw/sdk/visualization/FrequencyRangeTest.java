package com.benesquivelmusic.daw.sdk.visualization;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FrequencyRangeTest {

    @Test
    void shouldHaveFiveRanges() {
        assertThat(FrequencyRange.values()).hasSize(5);
    }

    @Test
    void shouldReturnDisplayNames() {
        assertThat(FrequencyRange.SUB_BASS.displayName()).isEqualTo("Sub-Bass");
        assertThat(FrequencyRange.BASS.displayName()).isEqualTo("Bass");
        assertThat(FrequencyRange.MIDS.displayName()).isEqualTo("Mids");
        assertThat(FrequencyRange.HIGH_MIDS.displayName()).isEqualTo("High-Mids");
        assertThat(FrequencyRange.HIGHS.displayName()).isEqualTo("Highs");
    }

    @Test
    void shouldReturnFrequencyBoundaries() {
        assertThat(FrequencyRange.SUB_BASS.lowFrequencyHz()).isEqualTo(20.0);
        assertThat(FrequencyRange.SUB_BASS.highFrequencyHz()).isEqualTo(60.0);
        assertThat(FrequencyRange.BASS.lowFrequencyHz()).isEqualTo(60.0);
        assertThat(FrequencyRange.BASS.highFrequencyHz()).isEqualTo(250.0);
        assertThat(FrequencyRange.MIDS.lowFrequencyHz()).isEqualTo(250.0);
        assertThat(FrequencyRange.MIDS.highFrequencyHz()).isEqualTo(2000.0);
        assertThat(FrequencyRange.HIGH_MIDS.lowFrequencyHz()).isEqualTo(2000.0);
        assertThat(FrequencyRange.HIGH_MIDS.highFrequencyHz()).isEqualTo(6000.0);
        assertThat(FrequencyRange.HIGHS.lowFrequencyHz()).isEqualTo(6000.0);
        assertThat(FrequencyRange.HIGHS.highFrequencyHz()).isEqualTo(20000.0);
    }

    @Test
    void shouldMapFrequencyToCorrectRange() {
        assertThat(FrequencyRange.forFrequency(30.0)).isEqualTo(FrequencyRange.SUB_BASS);
        assertThat(FrequencyRange.forFrequency(100.0)).isEqualTo(FrequencyRange.BASS);
        assertThat(FrequencyRange.forFrequency(500.0)).isEqualTo(FrequencyRange.MIDS);
        assertThat(FrequencyRange.forFrequency(3000.0)).isEqualTo(FrequencyRange.HIGH_MIDS);
        assertThat(FrequencyRange.forFrequency(10000.0)).isEqualTo(FrequencyRange.HIGHS);
    }

    @Test
    void shouldMapBoundaryFrequencies() {
        assertThat(FrequencyRange.forFrequency(20.0)).isEqualTo(FrequencyRange.SUB_BASS);
        assertThat(FrequencyRange.forFrequency(60.0)).isEqualTo(FrequencyRange.BASS);
        assertThat(FrequencyRange.forFrequency(250.0)).isEqualTo(FrequencyRange.MIDS);
        assertThat(FrequencyRange.forFrequency(2000.0)).isEqualTo(FrequencyRange.HIGH_MIDS);
        assertThat(FrequencyRange.forFrequency(6000.0)).isEqualTo(FrequencyRange.HIGHS);
    }

    @Test
    void shouldMapEdgeCaseFrequencies() {
        assertThat(FrequencyRange.forFrequency(5.0)).isEqualTo(FrequencyRange.SUB_BASS);
        assertThat(FrequencyRange.forFrequency(25000.0)).isEqualTo(FrequencyRange.HIGHS);
    }
}
