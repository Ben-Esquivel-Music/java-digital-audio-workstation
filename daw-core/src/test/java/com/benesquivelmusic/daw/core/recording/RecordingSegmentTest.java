package com.benesquivelmusic.daw.core.recording;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordingSegmentTest {

    @Test
    void shouldCreateNewSegment() {
        RecordingSegment segment = RecordingSegment.startNew(0, Path.of("/tmp/segment-000.wav"));

        assertThat(segment.index()).isZero();
        assertThat(segment.filePath()).isEqualTo(Path.of("/tmp/segment-000.wav"));
        assertThat(segment.startTime()).isNotNull();
        assertThat(segment.endTime()).isNull();
        assertThat(segment.sampleCount()).isZero();
        assertThat(segment.sizeBytes()).isZero();
        assertThat(segment.isInProgress()).isTrue();
    }

    @Test
    void shouldCompleteSegment() {
        RecordingSegment segment = RecordingSegment.startNew(1, Path.of("/tmp/segment-001.wav"));

        RecordingSegment completed = segment.complete(44100 * 60, 5_292_000);

        assertThat(completed.isInProgress()).isFalse();
        assertThat(completed.endTime()).isNotNull();
        assertThat(completed.sampleCount()).isEqualTo(44100 * 60);
        assertThat(completed.sizeBytes()).isEqualTo(5_292_000);
        assertThat(completed.index()).isEqualTo(1);
    }

    @Test
    void shouldRejectNegativeIndex() {
        assertThatThrownBy(() -> RecordingSegment.startNew(-1, Path.of("/tmp/x.wav")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullFilePath() {
        assertThatThrownBy(() -> RecordingSegment.startNew(0, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNegativeSampleCount() {
        assertThatThrownBy(() -> new RecordingSegment(0, Path.of("/tmp/x.wav"),
                Instant.now(), null, -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeSizeBytes() {
        assertThatThrownBy(() -> new RecordingSegment(0, Path.of("/tmp/x.wav"),
                Instant.now(), null, 0, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
