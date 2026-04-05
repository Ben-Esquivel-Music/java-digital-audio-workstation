package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.sdk.export.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DefaultAudioExporterTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldExportWavSuccessfully() throws IOException {
        DefaultAudioExporter exporter = new DefaultAudioExporter();
        float[][] audio = generateStereoSine(44100, 0.5, 440.0);
        AudioExportConfig config = new AudioExportConfig(AudioExportFormat.WAV, 44100, 16, DitherType.TPDF);

        ExportResult result = exporter.export(audio, 44100, tempDir, "test", config);

        assertThat(result.success()).isTrue();
        assertThat(result.outputPath()).exists();
        assertThat(result.outputPath().getFileName().toString()).isEqualTo("test.wav");
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.message()).contains("successfully");
    }

    @Test
    void shouldExportWithSampleRateConversion() throws IOException {
        DefaultAudioExporter exporter = new DefaultAudioExporter();
        float[][] audio = generateStereoSine(96000, 0.5, 440.0);
        AudioExportConfig config = new AudioExportConfig(AudioExportFormat.WAV, 44100, 16, DitherType.TPDF);

        ExportResult result = exporter.export(audio, 96000, tempDir, "downsampled", config);

        assertThat(result.success()).isTrue();
        assertThat(result.outputPath()).exists();
    }

    @Test
    void shouldBatchExportMultipleConfigs() throws IOException {
        DefaultAudioExporter exporter = new DefaultAudioExporter();
        float[][] audio = generateStereoSine(96000, 0.5, 440.0);

        List<AudioExportConfig> configs = List.of(
                new AudioExportConfig(AudioExportFormat.WAV, 44100, 16, DitherType.TPDF),
                new AudioExportConfig(AudioExportFormat.WAV, 96000, 24, DitherType.NONE),
                new AudioExportConfig(AudioExportFormat.WAV, 48000, 32, DitherType.NONE)
        );

        List<ExportResult> results = exporter.exportBatch(audio, 96000,
                tempDir, "batch", configs);

        assertThat(results).hasSize(3);
        for (ExportResult result : results) {
            assertThat(result.success()).isTrue();
            assertThat(result.outputPath()).exists();
        }
    }

    @Test
    void shouldExportMp3Successfully() throws IOException {
        assumeTrue(NativeCodecAvailability.isLameAvailable(),
                "libmp3lame not available");
        DefaultAudioExporter exporter = new DefaultAudioExporter();
        float[][] audio = generateStereoSine(44100, 0.1, 440.0);
        AudioExportConfig config = new AudioExportConfig(AudioExportFormat.MP3, 44100, 16, DitherType.NONE);

        ExportResult result = exporter.export(audio, 44100, tempDir, "test", config);

        assertThat(result.success()).isTrue();
        assertThat(result.outputPath()).exists();
        assertThat(result.outputPath().getFileName().toString()).isEqualTo("test.mp3");
    }

    @Test
    void shouldExportOggSuccessfully() throws IOException {
        assumeTrue(NativeCodecAvailability.isVorbisAvailable(),
                "libvorbis/libogg not available");
        DefaultAudioExporter exporter = new DefaultAudioExporter();
        float[][] audio = generateStereoSine(44100, 0.5, 440.0);
        AudioExportConfig config = new AudioExportConfig(AudioExportFormat.OGG, 44100, 16, DitherType.NONE);

        ExportResult result = exporter.export(audio, 44100, tempDir, "test_ogg", config);

        assertThat(result.success()).isTrue();
        assertThat(result.outputPath()).exists();
        assertThat(result.outputPath().getFileName().toString()).isEqualTo("test_ogg.ogg");
    }

    @Test
    void shouldExportAacSuccessfully() throws IOException {
        assumeTrue(NativeCodecAvailability.isFdkAacAvailable(),
                "libfdk-aac not available");
        DefaultAudioExporter exporter = new DefaultAudioExporter();
        float[][] audio = generateStereoSine(44100, 0.5, 440.0);
        AudioExportConfig config = new AudioExportConfig(AudioExportFormat.AAC, 44100, 16, DitherType.NONE);

        ExportResult result = exporter.export(audio, 44100, tempDir, "test_aac", config);

        assertThat(result.success()).isTrue();
        assertThat(result.outputPath()).exists();
        assertThat(result.outputPath().getFileName().toString()).isEqualTo("test_aac.aac");
    }

    @Test
    void shouldExportUsingPresets() throws IOException {
        DefaultAudioExporter exporter = new DefaultAudioExporter();
        float[][] audio = generateStereoSine(96000, 0.5, 440.0);

        ExportResult cdResult = exporter.export(audio, 96000, tempDir, "cd",
                ExportPreset.CD.config());
        assertThat(cdResult.success()).isTrue();

        ExportResult vinylResult = exporter.export(audio, 96000, tempDir, "vinyl",
                ExportPreset.VINYL.config());
        assertThat(vinylResult.success()).isTrue();
    }

    @Test
    void shouldCreateOutputDirectoryIfMissing() throws IOException {
        DefaultAudioExporter exporter = new DefaultAudioExporter();
        float[][] audio = new float[1][100];
        Path nestedDir = tempDir.resolve("sub").resolve("dir");
        AudioExportConfig config = new AudioExportConfig(AudioExportFormat.WAV, 44100, 16, DitherType.NONE);

        ExportResult result = exporter.export(audio, 44100, nestedDir, "test", config);

        assertThat(result.success()).isTrue();
        assertThat(result.outputPath()).exists();
    }

    @Test
    void shouldRejectNullAudioData() {
        DefaultAudioExporter exporter = new DefaultAudioExporter();
        AudioExportConfig config = new AudioExportConfig(AudioExportFormat.WAV, 44100, 16, DitherType.NONE);

        assertThatThrownBy(() -> exporter.export(null, 44100, tempDir, "test", config))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectEmptyAudioData() {
        DefaultAudioExporter exporter = new DefaultAudioExporter();
        float[][] audio = new float[0][];
        AudioExportConfig config = new AudioExportConfig(AudioExportFormat.WAV, 44100, 16, DitherType.NONE);

        assertThatThrownBy(() -> exporter.export(audio, 44100, tempDir, "test", config))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldExportWithMetadata() throws IOException {
        DefaultAudioExporter exporter = new DefaultAudioExporter();
        float[][] audio = generateStereoSine(44100, 0.1, 440.0);
        AudioMetadata metadata = new AudioMetadata("My Song", "My Artist", "My Album", "US1234567890");
        AudioExportConfig config = new AudioExportConfig(
                AudioExportFormat.WAV, 44100, 16, DitherType.TPDF, metadata, 0.8);

        ExportResult result = exporter.export(audio, 44100, tempDir, "with_meta", config);

        assertThat(result.success()).isTrue();
        assertThat(result.outputPath()).exists();
    }

    @Test
    void shouldRejectEmptyBaseName() {
        DefaultAudioExporter exporter = new DefaultAudioExporter();
        float[][] audio = generateStereoSine(44100, 0.1, 440.0);
        AudioExportConfig config = new AudioExportConfig(AudioExportFormat.WAV, 44100, 16, DitherType.NONE);

        assertThatThrownBy(() -> exporter.export(audio, 44100, tempDir, "", config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseName");
    }

    @Test
    void shouldRejectPathTraversalInBaseName() {
        DefaultAudioExporter exporter = new DefaultAudioExporter();
        float[][] audio = generateStereoSine(44100, 0.1, 440.0);
        AudioExportConfig config = new AudioExportConfig(AudioExportFormat.WAV, 44100, 16, DitherType.NONE);

        assertThatThrownBy(() -> exporter.export(audio, 44100, tempDir, "../escape", config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseName");
    }

    @Test
    void shouldExportFlacSuccessfully() throws IOException {
        DefaultAudioExporter exporter = new DefaultAudioExporter();
        float[][] audio = generateStereoSine(44100, 0.5, 440.0);
        AudioExportConfig config = new AudioExportConfig(AudioExportFormat.FLAC, 44100, 16, DitherType.TPDF);

        ExportResult result = exporter.export(audio, 44100, tempDir, "test_flac", config);

        assertThat(result.success()).isTrue();
        assertThat(result.outputPath()).exists();
        assertThat(result.outputPath().getFileName().toString()).isEqualTo("test_flac.flac");
    }

    @Test
    void shouldExportWithProgressListener() throws IOException {
        DefaultAudioExporter exporter = new DefaultAudioExporter();
        float[][] audio = generateStereoSine(44100, 0.5, 440.0);
        AudioExportConfig config = new AudioExportConfig(AudioExportFormat.WAV, 44100, 16, DitherType.TPDF);

        java.util.List<Double> progressValues = new java.util.ArrayList<>();
        com.benesquivelmusic.daw.sdk.export.ExportProgressListener listener =
                (progress, stage) -> progressValues.add(progress);

        ExportResult result = exporter.export(audio, 44100, tempDir, "progress",
                config, listener);

        assertThat(result.success()).isTrue();
        assertThat(progressValues).isNotEmpty();
        assertThat(progressValues.getLast()).isEqualTo(1.0);
    }

    @Test
    void shouldExportWithTimeRange() throws IOException {
        DefaultAudioExporter exporter = new DefaultAudioExporter();
        float[][] audio = generateStereoSine(44100, 5.0, 440.0);
        AudioExportConfig config = new AudioExportConfig(AudioExportFormat.WAV, 44100, 16, DitherType.NONE);
        com.benesquivelmusic.daw.sdk.export.ExportRange range =
                new com.benesquivelmusic.daw.sdk.export.ExportRange(1.0, 3.0);

        ExportResult result = exporter.export(audio, 44100, tempDir, "ranged",
                config, range, com.benesquivelmusic.daw.sdk.export.ExportProgressListener.NONE);

        assertThat(result.success()).isTrue();
        assertThat(result.outputPath()).exists();
    }

    private static float[][] generateStereoSine(int sampleRate, double duration, double freq) {
        int numSamples = (int) (sampleRate * duration);
        float[][] audio = new float[2][numSamples];
        for (int i = 0; i < numSamples; i++) {
            float value = (float) Math.sin(2.0 * Math.PI * freq * i / sampleRate);
            audio[0][i] = value;
            audio[1][i] = value;
        }
        return audio;
    }
}
