package com.benesquivelmusic.daw.core.browser;

import com.benesquivelmusic.daw.core.export.WavExporter;
import com.benesquivelmusic.daw.sdk.export.AudioMetadata;
import com.benesquivelmusic.daw.sdk.export.DitherType;
import com.benesquivelmusic.daw.sdk.visualization.WaveformData;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SamplePreviewServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadMetadataFromWavFile() throws IOException {
        Path wavFile = createTestWavFile("test.wav", 44100, 2, 16, 1000);

        SamplePreviewService service = new SamplePreviewService();
        Optional<SampleMetadata> metadata = service.loadMetadata(wavFile);

        assertThat(metadata).isPresent();
        assertThat(metadata.get().sampleRate()).isEqualTo(44100);
        assertThat(metadata.get().channels()).isEqualTo(2);
        assertThat(metadata.get().bitDepth()).isEqualTo(16);
        assertThat(metadata.get().filePath()).isEqualTo(wavFile);
        assertThat(metadata.get().fileSizeBytes()).isGreaterThan(0);
    }

    @Test
    void shouldReturnEmptyForNonExistentFile() {
        SamplePreviewService service = new SamplePreviewService();
        Optional<SampleMetadata> metadata = service.loadMetadata(Path.of("/nonexistent/file.wav"));
        assertThat(metadata).isEmpty();
    }

    @Test
    void shouldReturnEmptyForInvalidFile() throws IOException {
        Path textFile = tempDir.resolve("not_audio.txt");
        Files.writeString(textFile, "This is not audio data");

        SamplePreviewService service = new SamplePreviewService();
        Optional<SampleMetadata> metadata = service.loadMetadata(textFile);
        assertThat(metadata).isEmpty();
    }

    @Test
    void shouldRejectNullFilePathForMetadata() {
        SamplePreviewService service = new SamplePreviewService();
        assertThatThrownBy(() -> service.loadMetadata(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldGenerateWaveformThumbnail() throws IOException {
        Path wavFile = createTestWavFile("thumbnail.wav", 44100, 1, 16, 4410);

        SamplePreviewService service = new SamplePreviewService();
        Optional<WaveformData> thumbnail = service.loadThumbnail(wavFile);

        assertThat(thumbnail).isPresent();
        assertThat(thumbnail.get().columns()).isEqualTo(SamplePreviewService.DEFAULT_THUMBNAIL_COLUMNS);
    }

    @Test
    void shouldGenerateThumbnailWithCustomColumns() throws IOException {
        Path wavFile = createTestWavFile("custom.wav", 44100, 1, 16, 4410);

        SamplePreviewService service = new SamplePreviewService();
        Optional<WaveformData> thumbnail = service.loadThumbnail(wavFile, 32);

        assertThat(thumbnail).isPresent();
        assertThat(thumbnail.get().columns()).isEqualTo(32);
    }

    @Test
    void shouldCacheThumbnails() throws IOException {
        Path wavFile = createTestWavFile("cached.wav", 44100, 1, 16, 4410);
        WaveformThumbnailCache cache = new WaveformThumbnailCache();
        SamplePreviewService service = new SamplePreviewService(cache);

        // First load generates and caches
        service.loadThumbnail(wavFile);
        assertThat(cache.contains(wavFile)).isTrue();

        // Second load returns from cache
        Optional<WaveformData> cached = service.loadThumbnail(wavFile);
        assertThat(cached).isPresent();
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void shouldReturnEmptyThumbnailForNonExistentFile() {
        SamplePreviewService service = new SamplePreviewService();
        Optional<WaveformData> thumbnail = service.loadThumbnail(Path.of("/nonexistent.wav"));
        assertThat(thumbnail).isEmpty();
    }

    @Test
    void shouldRejectNullFilePathForThumbnail() {
        SamplePreviewService service = new SamplePreviewService();
        assertThatThrownBy(() -> service.loadThumbnail(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectZeroColumnsForThumbnail() throws IOException {
        Path wavFile = createTestWavFile("zero_cols.wav", 44100, 1, 16, 100);
        SamplePreviewService service = new SamplePreviewService();
        assertThatThrownBy(() -> service.loadThumbnail(wavFile, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullCacheInConstructor() {
        assertThatThrownBy(() -> new SamplePreviewService(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldReturnThumbnailCache() {
        WaveformThumbnailCache cache = new WaveformThumbnailCache();
        SamplePreviewService service = new SamplePreviewService(cache);
        assertThat(service.getThumbnailCache()).isSameAs(cache);
    }

    @Test
    void shouldHandleStereoThumbnail() throws IOException {
        Path wavFile = createTestWavFile("stereo.wav", 44100, 2, 16, 4410);

        SamplePreviewService service = new SamplePreviewService();
        Optional<WaveformData> thumbnail = service.loadThumbnail(wavFile);

        assertThat(thumbnail).isPresent();
        assertThat(thumbnail.get().columns()).isEqualTo(SamplePreviewService.DEFAULT_THUMBNAIL_COLUMNS);
    }

    @Test
    void shouldReturnEmptyForDirectory() throws IOException {
        Path dir = tempDir.resolve("subdir");
        Files.createDirectories(dir);

        SamplePreviewService service = new SamplePreviewService();
        assertThat(service.loadMetadata(dir)).isEmpty();
    }

    private Path createTestWavFile(String name, int sampleRate, int channels,
                                   int bitDepth, int numFrames) throws IOException {
        float[][] audio = new float[channels][numFrames];
        for (int ch = 0; ch < channels; ch++) {
            for (int i = 0; i < numFrames; i++) {
                audio[ch][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate));
            }
        }
        Path wavFile = tempDir.resolve(name);
        WavExporter.write(audio, sampleRate, bitDepth, DitherType.NONE, AudioMetadata.EMPTY, wavFile);
        return wavFile;
    }
}
