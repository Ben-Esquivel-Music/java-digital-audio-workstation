package com.benesquivelmusic.daw.core.performance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrackPerformanceMetricsTest {

    @Test
    void shouldCreateValidMetrics() {
        TrackPerformanceMetrics metrics = new TrackPerformanceMetrics("Vocals", 25.5);

        assertThat(metrics.trackName()).isEqualTo("Vocals");
        assertThat(metrics.dspLoadPercent()).isEqualTo(25.5);
    }

    @Test
    void shouldRejectNullTrackName() {
        assertThatThrownBy(() -> new TrackPerformanceMetrics(null, 10.0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("trackName");
    }

    @Test
    void shouldRejectNegativeDspLoad() {
        assertThatThrownBy(() -> new TrackPerformanceMetrics("Drums", -1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dspLoadPercent");
    }

    @Test
    void shouldAllowZeroDspLoad() {
        TrackPerformanceMetrics metrics = new TrackPerformanceMetrics("Silent", 0.0);
        assertThat(metrics.dspLoadPercent()).isZero();
    }

    @Test
    void shouldAllowHighDspLoad() {
        TrackPerformanceMetrics metrics = new TrackPerformanceMetrics("Heavy FX", 120.0);
        assertThat(metrics.dspLoadPercent()).isEqualTo(120.0);
    }

    @Test
    void shouldSupportEquality() {
        TrackPerformanceMetrics a = new TrackPerformanceMetrics("Bass", 15.0);
        TrackPerformanceMetrics b = new TrackPerformanceMetrics("Bass", 15.0);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void shouldNotEqualDifferentValues() {
        TrackPerformanceMetrics a = new TrackPerformanceMetrics("Bass", 15.0);
        TrackPerformanceMetrics b = new TrackPerformanceMetrics("Bass", 20.0);
        assertThat(a).isNotEqualTo(b);
    }
}
