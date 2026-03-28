package com.benesquivelmusic.daw.core.performance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PerformanceMetricsTest {

    @Test
    void shouldCreateValidMetrics() {
        PerformanceMetrics metrics = new PerformanceMetrics(
                45.0, 512, 11.6, 44100.0, 0, 80.0, false);

        assertThat(metrics.cpuLoadPercent()).isEqualTo(45.0);
        assertThat(metrics.bufferSizeFrames()).isEqualTo(512);
        assertThat(metrics.bufferLatencyMs()).isEqualTo(11.6);
        assertThat(metrics.sampleRate()).isEqualTo(44100.0);
        assertThat(metrics.underrunCount()).isZero();
        assertThat(metrics.warningThresholdPercent()).isEqualTo(80.0);
        assertThat(metrics.warning()).isFalse();
    }

    @Test
    void shouldCreateWarningMetrics() {
        PerformanceMetrics metrics = new PerformanceMetrics(
                95.0, 256, 5.8, 44100.0, 3, 80.0, true);

        assertThat(metrics.cpuLoadPercent()).isEqualTo(95.0);
        assertThat(metrics.underrunCount()).isEqualTo(3);
        assertThat(metrics.warning()).isTrue();
    }

    @Test
    void shouldRejectNegativeCpuLoad() {
        assertThatThrownBy(() -> new PerformanceMetrics(
                -1.0, 512, 11.6, 44100.0, 0, 80.0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cpuLoadPercent");
    }

    @Test
    void shouldRejectZeroBufferSize() {
        assertThatThrownBy(() -> new PerformanceMetrics(
                0.0, 0, 11.6, 44100.0, 0, 80.0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bufferSizeFrames");
    }

    @Test
    void shouldRejectNegativeLatency() {
        assertThatThrownBy(() -> new PerformanceMetrics(
                0.0, 512, -1.0, 44100.0, 0, 80.0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bufferLatencyMs");
    }

    @Test
    void shouldRejectZeroSampleRate() {
        assertThatThrownBy(() -> new PerformanceMetrics(
                0.0, 512, 11.6, 0.0, 0, 80.0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampleRate");
    }

    @Test
    void shouldRejectNegativeUnderrunCount() {
        assertThatThrownBy(() -> new PerformanceMetrics(
                0.0, 512, 11.6, 44100.0, -1, 80.0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("underrunCount");
    }

    @Test
    void shouldRejectThresholdAbove100() {
        assertThatThrownBy(() -> new PerformanceMetrics(
                0.0, 512, 11.6, 44100.0, 0, 101.0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("warningThresholdPercent");
    }

    @Test
    void shouldRejectNegativeThreshold() {
        assertThatThrownBy(() -> new PerformanceMetrics(
                0.0, 512, 11.6, 44100.0, 0, -1.0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("warningThresholdPercent");
    }

    @Test
    void shouldAllowZeroCpuLoad() {
        PerformanceMetrics metrics = new PerformanceMetrics(
                0.0, 512, 0.0, 44100.0, 0, 0.0, false);
        assertThat(metrics.cpuLoadPercent()).isZero();
    }

    @Test
    void shouldAllowCpuLoadAbove100() {
        PerformanceMetrics metrics = new PerformanceMetrics(
                150.0, 512, 11.6, 44100.0, 0, 80.0, true);
        assertThat(metrics.cpuLoadPercent()).isEqualTo(150.0);
    }

    @Test
    void shouldSupportEquality() {
        PerformanceMetrics a = new PerformanceMetrics(
                50.0, 512, 11.6, 44100.0, 0, 80.0, false);
        PerformanceMetrics b = new PerformanceMetrics(
                50.0, 512, 11.6, 44100.0, 0, 80.0, false);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void shouldNotEqualDifferentValues() {
        PerformanceMetrics a = new PerformanceMetrics(
                50.0, 512, 11.6, 44100.0, 0, 80.0, false);
        PerformanceMetrics b = new PerformanceMetrics(
                60.0, 512, 11.6, 44100.0, 0, 80.0, false);
        assertThat(a).isNotEqualTo(b);
    }
}
