package com.benesquivelmusic.daw.core.performance;

import com.benesquivelmusic.daw.core.audio.AudioFormat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class PerformanceMonitorTest {

    private static final AudioFormat FORMAT = new AudioFormat(44_100.0, 2, 16, 512);

    /** Buffer duration in nanoseconds: (512 / 44100) * 1e9 ≈ 11_609_977 ns */
    private static final double BUFFER_DURATION_NS = (512.0 / 44_100.0) * 1_000_000_000.0;

    private PerformanceMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new PerformanceMonitor(FORMAT);
    }

    @Test
    void shouldReturnConfiguredFormat() {
        assertThat(monitor.getFormat()).isEqualTo(FORMAT);
    }

    @Test
    void shouldCalculateBufferLatency() {
        double expectedMs = (512.0 / 44_100.0) * 1000.0;
        assertThat(monitor.getBufferLatencyMs()).isCloseTo(expectedMs, within(0.001));
    }

    @Test
    void shouldHaveDefaultWarningThreshold() {
        assertThat(monitor.getWarningThresholdPercent()).isEqualTo(80.0);
    }

    @Test
    void shouldSetWarningThreshold() {
        monitor.setWarningThresholdPercent(70.0);
        assertThat(monitor.getWarningThresholdPercent()).isEqualTo(70.0);
    }

    @Test
    void shouldRejectInvalidWarningThreshold() {
        assertThatThrownBy(() -> monitor.setWarningThresholdPercent(-1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> monitor.setWarningThresholdPercent(101.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldStartWithZeroCpuLoad() {
        assertThat(monitor.getCpuLoadPercent()).isZero();
    }

    @Test
    void shouldStartWithZeroUnderrunCount() {
        assertThat(monitor.getUnderrunCount()).isZero();
    }

    @Test
    void shouldStartWithNoWarning() {
        assertThat(monitor.isWarningActive()).isFalse();
    }

    @Test
    void shouldUpdateCpuLoadOnRecord() {
        // Record a processing time that is 50% of the budget
        long halfBudget = (long) (BUFFER_DURATION_NS * 0.5);
        monitor.recordProcessingTime(halfBudget);

        // After one recording, CPU load should move toward 50% (smoothed)
        assertThat(monitor.getCpuLoadPercent()).isGreaterThan(0.0);
    }

    @Test
    void shouldConvergeCpuLoadWithRepeatedRecordings() {
        // Simulate many buffers at exactly 50% utilization
        long halfBudget = (long) (BUFFER_DURATION_NS * 0.5);
        for (int i = 0; i < 200; i++) {
            monitor.recordProcessingTime(halfBudget);
        }

        // After many iterations, the smoothed load should converge near 50%
        assertThat(monitor.getCpuLoadPercent()).isCloseTo(50.0, within(1.0));
    }

    @Test
    void shouldDetectBufferUnderrun() {
        // Processing time exceeds the budget
        long overBudget = (long) (BUFFER_DURATION_NS * 1.5);
        monitor.recordProcessingTime(overBudget);

        assertThat(monitor.getUnderrunCount()).isEqualTo(1);
    }

    @Test
    void shouldNotCountUnderrunWithinBudget() {
        long withinBudget = (long) (BUFFER_DURATION_NS * 0.8);
        monitor.recordProcessingTime(withinBudget);

        assertThat(monitor.getUnderrunCount()).isZero();
    }

    @Test
    void shouldAccumulateUnderrunCount() {
        long overBudget = (long) (BUFFER_DURATION_NS * 2.0);
        monitor.recordProcessingTime(overBudget);
        monitor.recordProcessingTime(overBudget);
        monitor.recordProcessingTime(overBudget);

        assertThat(monitor.getUnderrunCount()).isEqualTo(3);
    }

    @Test
    void shouldTriggerWarningWhenThresholdExceeded() {
        monitor.setWarningThresholdPercent(10.0);

        // Simulate high CPU load until warning triggers
        long highLoad = (long) (BUFFER_DURATION_NS * 0.9);
        for (int i = 0; i < 200; i++) {
            monitor.recordProcessingTime(highLoad);
        }

        assertThat(monitor.isWarningActive()).isTrue();
    }

    @Test
    void shouldNotifyListenerOnWarning() {
        List<PerformanceMetrics> captured = new ArrayList<>();
        monitor.addWarningListener(captured::add);
        monitor.setWarningThresholdPercent(10.0);

        // Drive the load above the threshold
        long highLoad = (long) (BUFFER_DURATION_NS * 0.9);
        for (int i = 0; i < 200; i++) {
            monitor.recordProcessingTime(highLoad);
        }

        assertThat(captured).isNotEmpty();
        assertThat(captured.get(0).warning()).isTrue();
    }

    @Test
    void shouldNotifyListenerWhenWarningClears() {
        List<PerformanceMetrics> captured = new ArrayList<>();
        monitor.setWarningThresholdPercent(10.0);

        // First drive load high
        long highLoad = (long) (BUFFER_DURATION_NS * 0.9);
        for (int i = 0; i < 200; i++) {
            monitor.recordProcessingTime(highLoad);
        }

        // Now add listener and drive load low until warning clears
        monitor.addWarningListener(captured::add);
        long lowLoad = (long) (BUFFER_DURATION_NS * 0.01);
        for (int i = 0; i < 200; i++) {
            monitor.recordProcessingTime(lowLoad);
        }

        assertThat(captured).isNotEmpty();
        assertThat(captured.get(0).warning()).isFalse();
    }

    @Test
    void shouldRemoveWarningListener() {
        List<PerformanceMetrics> captured = new ArrayList<>();
        PerformanceWarningListener listener = captured::add;
        monitor.addWarningListener(listener);
        assertThat(monitor.removeWarningListener(listener)).isTrue();

        monitor.setWarningThresholdPercent(1.0);
        long highLoad = (long) (BUFFER_DURATION_NS * 0.9);
        for (int i = 0; i < 200; i++) {
            monitor.recordProcessingTime(highLoad);
        }

        assertThat(captured).isEmpty();
    }

    @Test
    void shouldRecordTrackProcessingTime() {
        long trackTime = (long) (BUFFER_DURATION_NS * 0.2);
        monitor.recordTrackProcessingTime("Vocals", trackTime);
        monitor.recordTrackProcessingTime("Drums", trackTime);
        monitor.commitTrackMetrics();

        List<TrackPerformanceMetrics> metrics = monitor.getTrackMetrics();
        assertThat(metrics).hasSize(2);
        assertThat(metrics.get(0).trackName()).isEqualTo("Vocals");
        assertThat(metrics.get(0).dspLoadPercent()).isCloseTo(20.0, within(1.0));
        assertThat(metrics.get(1).trackName()).isEqualTo("Drums");
    }

    @Test
    void shouldClearPendingTrackMetricsAfterCommit() {
        long trackTime = (long) (BUFFER_DURATION_NS * 0.1);
        monitor.recordTrackProcessingTime("Track 1", trackTime);
        monitor.commitTrackMetrics();

        // New cycle with different tracks
        monitor.recordTrackProcessingTime("Track 2", trackTime);
        monitor.commitTrackMetrics();

        List<TrackPerformanceMetrics> metrics = monitor.getTrackMetrics();
        assertThat(metrics).hasSize(1);
        assertThat(metrics.get(0).trackName()).isEqualTo("Track 2");
    }

    @Test
    void shouldReturnEmptyTrackMetricsInitially() {
        assertThat(monitor.getTrackMetrics()).isEmpty();
    }

    @Test
    void shouldProduceSnapshot() {
        long halfBudget = (long) (BUFFER_DURATION_NS * 0.5);
        monitor.recordProcessingTime(halfBudget);

        PerformanceMetrics snapshot = monitor.snapshot();
        assertThat(snapshot.bufferSizeFrames()).isEqualTo(512);
        assertThat(snapshot.sampleRate()).isEqualTo(44_100.0);
        assertThat(snapshot.warningThresholdPercent()).isEqualTo(80.0);
        assertThat(snapshot.cpuLoadPercent()).isGreaterThan(0.0);
    }

    @Test
    void shouldResetMonitor() {
        long overBudget = (long) (BUFFER_DURATION_NS * 2.0);
        monitor.recordProcessingTime(overBudget);

        monitor.recordTrackProcessingTime("Test", overBudget);
        monitor.commitTrackMetrics();

        monitor.reset();

        assertThat(monitor.getCpuLoadPercent()).isZero();
        assertThat(monitor.getUnderrunCount()).isZero();
        assertThat(monitor.isWarningActive()).isFalse();
        assertThat(monitor.getTrackMetrics()).isEmpty();
    }

    @Test
    void shouldRejectNullTrackName() {
        assertThatThrownBy(() -> monitor.recordTrackProcessingTime(null, 100))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullFormat() {
        assertThatThrownBy(() -> new PerformanceMonitor(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullListener() {
        assertThatThrownBy(() -> monitor.addWarningListener(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldReturnFalseRemovingUnknownListener() {
        PerformanceWarningListener unknown = metrics -> {};
        assertThat(monitor.removeWarningListener(unknown)).isFalse();
    }

    @Test
    void shouldSnapshotReflectCurrentWarningState() {
        monitor.setWarningThresholdPercent(10.0);
        long highLoad = (long) (BUFFER_DURATION_NS * 0.9);
        for (int i = 0; i < 200; i++) {
            monitor.recordProcessingTime(highLoad);
        }

        PerformanceMetrics snapshot = monitor.snapshot();
        assertThat(snapshot.warning()).isTrue();
    }

    @Test
    void shouldWorkWithDifferentAudioFormats() {
        AudioFormat studioFormat = AudioFormat.STUDIO_QUALITY;
        PerformanceMonitor studioMonitor = new PerformanceMonitor(studioFormat);

        double expectedLatency = (256.0 / 96_000.0) * 1000.0;
        assertThat(studioMonitor.getBufferLatencyMs()).isCloseTo(expectedLatency, within(0.001));
        assertThat(studioMonitor.snapshot().bufferSizeFrames()).isEqualTo(256);
        assertThat(studioMonitor.snapshot().sampleRate()).isEqualTo(96_000.0);
    }
}
