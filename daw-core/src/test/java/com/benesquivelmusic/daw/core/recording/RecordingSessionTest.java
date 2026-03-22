package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.sdk.event.RecordingListener;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.benesquivelmusic.daw.core.recording.RecordingSession.DEFAULT_MAX_SEGMENT_BYTES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordingSessionTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldStartSession() {
        var session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir);

        session.start();

        assertThat(session.isActive()).isTrue();
        assertThat(session.isPaused()).isFalse();
        assertThat(session.getSessionStartTime()).isNotNull();
        assertThat(session.getSegmentCount()).isEqualTo(1);
        assertThat(session.getCurrentSegment()).isNotNull();
    }

    @Test
    void shouldRejectDoubleStart() {
        var session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir);
        session.start();

        assertThatThrownBy(session::start)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldPauseAndResumeSession() {
        var session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir);
        session.start();

        session.pause();
        assertThat(session.isPaused()).isTrue();

        session.resume();
        assertThat(session.isPaused()).isFalse();
        assertThat(session.isActive()).isTrue();
    }

    @Test
    void shouldStopSession() {
        var session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir);
        session.start();

        session.stop();

        assertThat(session.isActive()).isFalse();
        assertThat(session.isPaused()).isFalse();
    }

    @Test
    void shouldNotRecordWhenInactive() {
        var session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir);

        session.recordSamples(1000, 4000);

        assertThat(session.getTotalSamplesRecorded()).isZero();
    }

    @Test
    void shouldNotRecordWhenPaused() {
        var session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir);
        session.start();
        session.pause();

        session.recordSamples(1000, 4000);

        assertThat(session.getTotalSamplesRecorded()).isZero();
    }

    @Test
    void shouldRecordSamples() {
        var session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir);
        session.start();

        session.recordSamples(44100, 176400);

        assertThat(session.getTotalSamplesRecorded()).isEqualTo(44100);
    }

    @Test
    void shouldNotifyListenersOnStart() {
        var session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir);
        List<String> events = new ArrayList<>();

        session.addListener(new TestRecordingListener(events));
        session.start();

        assertThat(events).contains("started", "segment:0");
    }

    @Test
    void shouldNotifyListenersOnPauseAndResume() {
        var session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir);
        List<String> events = new ArrayList<>();
        session.addListener(new TestRecordingListener(events));

        session.start();
        session.pause();
        session.resume();

        assertThat(events).contains("paused", "resumed");
    }

    @Test
    void shouldNotifyListenersOnStop() {
        var session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir);
        List<String> events = new ArrayList<>();
        session.addListener(new TestRecordingListener(events));

        session.start();
        session.stop();

        assertThat(events).contains("stopped");
    }

    @Test
    void shouldReturnFormat() {
        var session = new RecordingSession(AudioFormat.STUDIO_QUALITY, tempDir);

        assertThat(session.getFormat()).isEqualTo(AudioFormat.STUDIO_QUALITY);
    }

    @Test
    void shouldReturnDefaultSegmentLimits() {
        var session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir);

        assertThat(session.getMaxSegmentDuration()).isEqualTo(Duration.ofMinutes(30));
        assertThat(session.getMaxSegmentBytes()).isEqualTo(500L * 1024 * 1024);
    }

    @Test
    void shouldReturnCustomSegmentLimits() {
        var session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir,
                Duration.ofMinutes(10), 100_000_000L);

        assertThat(session.getMaxSegmentDuration()).isEqualTo(Duration.ofMinutes(10));
        assertThat(session.getMaxSegmentBytes()).isEqualTo(100_000_000L);
    }

    @Test
    void shouldRejectNonPositiveMaxSegmentBytes() {
        assertThatThrownBy(() -> new RecordingSession(AudioFormat.CD_QUALITY, tempDir,
                Duration.ofMinutes(10), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectZeroMaxSegmentDuration() {
        assertThatThrownBy(() -> new RecordingSession(AudioFormat.CD_QUALITY, tempDir,
                Duration.ZERO, DEFAULT_MAX_SEGMENT_BYTES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxSegmentDuration");
    }

    @Test
    void shouldRejectNegativeMaxSegmentDuration() {
        assertThatThrownBy(() -> new RecordingSession(AudioFormat.CD_QUALITY, tempDir,
                Duration.ofMinutes(-1), DEFAULT_MAX_SEGMENT_BYTES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxSegmentDuration");
    }

    @Test
    void shouldComputeTotalDuration() {
        var session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir);
        session.start();

        session.recordSamples(44100, 176400);

        assertThat(session.getTotalDuration()).isGreaterThanOrEqualTo(Duration.ofMillis(900));
    }

    @Test
    void shouldRemoveListener() {
        var session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir);
        List<String> events = new ArrayList<>();
        var listener = new TestRecordingListener(events);

        session.addListener(listener);
        session.removeListener(listener);
        session.start();

        assertThat(events).isEmpty();
    }

    @Test
    void shouldRotateSegmentWhenByteLimitExceeded() {
        // Very small byte limit to trigger rotation
        var session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir,
                Duration.ofHours(1), 1000L);
        session.start();
        assertThat(session.getSegmentCount()).isEqualTo(1);

        // Record enough cumulative bytes to exceed the limit
        session.recordSamples(100, 400);
        session.recordSamples(100, 400);
        session.recordSamples(100, 400); // total: 1200 > 1000

        // Should have rotated to a second segment
        assertThat(session.getSegmentCount()).isEqualTo(2);
    }

    @Test
    void shouldReturnUnmodifiableSegments() {
        var session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir);
        session.start();

        assertThatThrownBy(() -> session.getSegments().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static class TestRecordingListener implements RecordingListener {
        private final List<String> events;

        TestRecordingListener(List<String> events) {
            this.events = events;
        }

        @Override
        public void onRecordingStarted() {
            events.add("started");
        }

        @Override
        public void onRecordingPaused() {
            events.add("paused");
        }

        @Override
        public void onRecordingResumed() {
            events.add("resumed");
        }

        @Override
        public void onRecordingStopped() {
            events.add("stopped");
        }

        @Override
        public void onNewSegmentCreated(int segmentIndex) {
            events.add("segment:" + segmentIndex);
        }
    }
}
