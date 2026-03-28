package com.benesquivelmusic.daw.core.browser;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SampleMetadataTest {

    @Test
    void shouldCreateValidMetadata() {
        Path path = Path.of("/audio/kick.wav");
        SampleMetadata metadata = new SampleMetadata(path, 1.5, 44100, 2, 16, 132300);

        assertThat(metadata.filePath()).isEqualTo(path);
        assertThat(metadata.durationSeconds()).isEqualTo(1.5);
        assertThat(metadata.sampleRate()).isEqualTo(44100);
        assertThat(metadata.channels()).isEqualTo(2);
        assertThat(metadata.bitDepth()).isEqualTo(16);
        assertThat(metadata.fileSizeBytes()).isEqualTo(132300);
    }

    @Test
    void shouldRejectNullFilePath() {
        assertThatThrownBy(() -> new SampleMetadata(null, 1.0, 44100, 2, 16, 100))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("filePath");
    }

    @Test
    void shouldRejectNegativeDuration() {
        assertThatThrownBy(() -> new SampleMetadata(Path.of("/a.wav"), -1.0, 44100, 2, 16, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durationSeconds");
    }

    @Test
    void shouldRejectZeroSampleRate() {
        assertThatThrownBy(() -> new SampleMetadata(Path.of("/a.wav"), 1.0, 0, 2, 16, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampleRate");
    }

    @Test
    void shouldRejectZeroChannels() {
        assertThatThrownBy(() -> new SampleMetadata(Path.of("/a.wav"), 1.0, 44100, 0, 16, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channels");
    }

    @Test
    void shouldRejectZeroBitDepth() {
        assertThatThrownBy(() -> new SampleMetadata(Path.of("/a.wav"), 1.0, 44100, 2, 0, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bitDepth");
    }

    @Test
    void shouldRejectNegativeFileSize() {
        assertThatThrownBy(() -> new SampleMetadata(Path.of("/a.wav"), 1.0, 44100, 2, 16, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fileSizeBytes");
    }

    @Test
    void shouldFormatDurationCorrectly() {
        SampleMetadata metadata = new SampleMetadata(Path.of("/a.wav"), 65.3, 44100, 2, 16, 100);
        assertThat(metadata.formattedDuration()).isEqualTo("1:05.3");
    }

    @Test
    void shouldFormatShortDuration() {
        SampleMetadata metadata = new SampleMetadata(Path.of("/a.wav"), 2.7, 44100, 2, 16, 100);
        assertThat(metadata.formattedDuration()).isEqualTo("0:02.7");
    }

    @Test
    void shouldFormatZeroDuration() {
        SampleMetadata metadata = new SampleMetadata(Path.of("/a.wav"), 0.0, 44100, 2, 16, 100);
        assertThat(metadata.formattedDuration()).isEqualTo("0:00.0");
    }

    @Test
    void shouldDescribeMonoChannel() {
        SampleMetadata metadata = new SampleMetadata(Path.of("/a.wav"), 1.0, 44100, 1, 16, 100);
        assertThat(metadata.channelDescription()).isEqualTo("Mono");
    }

    @Test
    void shouldDescribeStereoChannels() {
        SampleMetadata metadata = new SampleMetadata(Path.of("/a.wav"), 1.0, 44100, 2, 16, 100);
        assertThat(metadata.channelDescription()).isEqualTo("Stereo");
    }

    @Test
    void shouldDescribeMultiChannel() {
        SampleMetadata metadata = new SampleMetadata(Path.of("/a.wav"), 1.0, 44100, 6, 16, 100);
        assertThat(metadata.channelDescription()).isEqualTo("6ch");
    }

    @Test
    void shouldProduceSummaryString() {
        SampleMetadata metadata = new SampleMetadata(Path.of("/a.wav"), 1.5, 44100, 2, 24, 100);
        String summary = metadata.toSummaryString();

        assertThat(summary).contains("44100 Hz");
        assertThat(summary).contains("Stereo");
        assertThat(summary).contains("24-bit");
    }

    @Test
    void shouldAllowZeroDuration() {
        SampleMetadata metadata = new SampleMetadata(Path.of("/a.wav"), 0.0, 44100, 1, 16, 0);
        assertThat(metadata.durationSeconds()).isEqualTo(0.0);
        assertThat(metadata.fileSizeBytes()).isEqualTo(0);
    }
}
