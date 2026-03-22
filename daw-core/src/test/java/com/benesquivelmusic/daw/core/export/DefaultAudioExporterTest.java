package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.sdk.export.AudioExportConfig;
import com.benesquivelmusic.daw.sdk.export.AudioExportFormat;
import com.benesquivelmusic.daw.sdk.export.AudioMetadata;
import com.benesquivelmusic.daw.sdk.export.DitherType;
import com.benesquivelmusic.daw.sdk.export.ExportPreset;
import com.benesquivelmusic.daw.sdk.export.ExportResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultAudioExporterTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldExportWavSuccessfully() throws IOException {
        var exporter = new DefaultAudioExporter();
        float[][] audio = generateStereoSine(44100, 0.5, 440.0);
        var config = new AudioExportConfig(AudioExportFormat.WAV, 44100, 16, DitherType.TPDF);

        ExportResult result = exporter.export(audio, 44100, tempDir, "test", config);

        assertThat(result.success()).isTrue();
        assertThat(result.outputPath()).exists();
        assertThat(result.outputPath().getFileName().toString()).isEqualTo("test.wav");
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.message()).contains("successfully");
    }

    @Test
    void shouldExportWithSampleRateConversion() throws IOException {
        var exporter = new DefaultAudioExporter();
        float[][] audio = generateStereoSine(96000, 0.5, 440.0);
        var config = new AudioExportConfig(AudioExportFormat.WAV, 44100, 16, DitherType.TPDF);

        ExportResult result = exporter.export(audio, 96000, tempDir, "downsampled", config);

        assertThat(result.success()).isTrue();
        assertThat(result.outputPath()).exists();
    }

    @Test
    void shouldBatchExportMultipleConfigs() throws IOException {
        var exporter = new DefaultAudioExporter();
        float[][] audio = generateStereoSine(96000, 0.5, 440.0);

        var configs = List.of(
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
    void shouldReturnFailureForUnsupportedFormat() throws IOException {
        var exporter = new DefaultAudioExporter();
        float[][] audio = generateStereoSine(44100, 0.1, 440.0);
        var config = new AudioExportConfig(AudioExportFormat.FLAC, 44100, 16, DitherType.NONE);

        ExportResult result = exporter.export(audio, 44100, tempDir, "test", config);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("not yet implemented");
    }

    @Test
    void shouldExportUsingPresets() throws IOException {
        var exporter = new DefaultAudioExporter();
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
        var exporter = new DefaultAudioExporter();
        float[][] audio = new float[1][100];
        Path nestedDir = tempDir.resolve("sub").resolve("dir");
        var config = new AudioExportConfig(AudioExportFormat.WAV, 44100, 16, DitherType.NONE);

        ExportResult result = exporter.export(audio, 44100, nestedDir, "test", config);

        assertThat(result.success()).isTrue();
        assertThat(result.outputPath()).exists();
    }

    @Test
    void shouldRejectNullAudioData() {
        var exporter = new DefaultAudioExporter();
        var config = new AudioExportConfig(AudioExportFormat.WAV, 44100, 16, DitherType.NONE);

        assertThatThrownBy(() -> exporter.export(null, 44100, tempDir, "test", config))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectEmptyAudioData() {
        var exporter = new DefaultAudioExporter();
        float[][] audio = new float[0][];
        var config = new AudioExportConfig(AudioExportFormat.WAV, 44100, 16, DitherType.NONE);

        assertThatThrownBy(() -> exporter.export(audio, 44100, tempDir, "test", config))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldExportWithMetadata() throws IOException {
        var exporter = new DefaultAudioExporter();
        float[][] audio = generateStereoSine(44100, 0.1, 440.0);
        var metadata = new AudioMetadata("My Song", "My Artist", "My Album", "US1234567890");
        var config = new AudioExportConfig(
                AudioExportFormat.WAV, 44100, 16, DitherType.TPDF, metadata, 0.8);

        ExportResult result = exporter.export(audio, 44100, tempDir, "with_meta", config);

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
