package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.sdk.export.AudioExportConfig;
import com.benesquivelmusic.daw.sdk.export.AudioExportFormat;
import com.benesquivelmusic.daw.sdk.export.DitherType;
import com.benesquivelmusic.daw.sdk.export.ExportPreset;
import com.benesquivelmusic.daw.sdk.export.ExportProgressListener;
import com.benesquivelmusic.daw.sdk.export.ExportRange;
import com.benesquivelmusic.daw.sdk.visualization.LoudnessTarget;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExportServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldExportWavWithFullPipeline() throws IOException {
        ExportService service = new ExportService();
        float[][] audio = generateStereoSine(44100, 2.0, 440.0, 0.5f);
        AudioExportConfig config = new AudioExportConfig(
                AudioExportFormat.WAV, 44100, 16, DitherType.TPDF);

        ExportService.ExportServiceResult result = service.exportWithValidation(
                audio, 44100, tempDir, "test_wav", config,
                ExportRange.FULL, null, ExportProgressListener.NONE);

        assertThat(result.exportResult().success()).isTrue();
        assertThat(result.exportResult().outputPath()).exists();
        assertThat(result.validationResult()).isNull();
        assertThat(result.gainAppliedDb()).isEqualTo(0.0);
    }

    @Test
    void shouldExportFlacWithFullPipeline() throws IOException {
        ExportService service = new ExportService();
        float[][] audio = generateStereoSine(44100, 2.0, 440.0, 0.5f);
        AudioExportConfig config = new AudioExportConfig(
                AudioExportFormat.FLAC, 44100, 16, DitherType.TPDF);

        ExportService.ExportServiceResult result = service.exportWithValidation(
                audio, 44100, tempDir, "test_flac", config,
                ExportRange.FULL, null, ExportProgressListener.NONE);

        assertThat(result.exportResult().success()).isTrue();
        assertThat(result.exportResult().outputPath()).exists();
        assertThat(result.exportResult().outputPath().getFileName().toString())
                .isEqualTo("test_flac.flac");
    }

    @Test
    void shouldExportWithLoudnessNormalization() throws IOException {
        ExportService service = new ExportService();
        float[][] audio = generateStereoSine(44100, 2.0, 1000.0, 0.5f);
        AudioExportConfig config = new AudioExportConfig(
                AudioExportFormat.WAV, 44100, 16, DitherType.TPDF);

        ExportService.ExportServiceResult result = service.exportWithValidation(
                audio, 44100, tempDir, "normalized", config,
                ExportRange.FULL, LoudnessTarget.SPOTIFY, ExportProgressListener.NONE);

        assertThat(result.exportResult().success()).isTrue();
        assertThat(result.gainAppliedDb()).isNotEqualTo(0.0);
        assertThat(result.validationResult()).isNotNull();
    }

    @Test
    void shouldExportTimeRange() throws IOException {
        ExportService service = new ExportService();
        float[][] audio = generateStereoSine(44100, 10.0, 440.0, 0.5f);
        AudioExportConfig config = new AudioExportConfig(
                AudioExportFormat.WAV, 44100, 16, DitherType.NONE);
        ExportRange range = new ExportRange(2.0, 5.0);

        ExportService.ExportServiceResult result = service.exportWithValidation(
                audio, 44100, tempDir, "ranged", config,
                range, null, ExportProgressListener.NONE);

        assertThat(result.exportResult().success()).isTrue();
        assertThat(result.exportResult().outputPath()).exists();
    }

    @Test
    void shouldReportProgressDuringExport() throws IOException {
        ExportService service = new ExportService();
        float[][] audio = generateStereoSine(44100, 1.0, 440.0, 0.5f);
        AudioExportConfig config = new AudioExportConfig(
                AudioExportFormat.WAV, 44100, 16, DitherType.TPDF);

        List<Double> progressValues = new ArrayList<>();
        List<String> stageValues = new ArrayList<>();
        ExportProgressListener listener = (progress, stage) -> {
            progressValues.add(progress);
            stageValues.add(stage);
        };

        service.exportWithValidation(audio, 44100, tempDir, "progress", config,
                ExportRange.FULL, null, listener);

        // Should have received at least start and end progress
        assertThat(progressValues).isNotEmpty();
        assertThat(progressValues.getFirst()).isEqualTo(0.0);
        assertThat(progressValues.getLast()).isEqualTo(1.0);
    }

    @Test
    void shouldReportProgressWithLoudnessNormalization() throws IOException {
        ExportService service = new ExportService();
        float[][] audio = generateStereoSine(44100, 2.0, 1000.0, 0.5f);
        AudioExportConfig config = new AudioExportConfig(
                AudioExportFormat.WAV, 44100, 16, DitherType.TPDF);

        List<String> stageValues = new ArrayList<>();
        ExportProgressListener listener = (progress, stage) -> stageValues.add(stage);

        service.exportWithValidation(audio, 44100, tempDir, "progress_norm", config,
                ExportRange.FULL, LoudnessTarget.SPOTIFY, listener);

        // Should include normalization stage descriptions
        assertThat(stageValues).anyMatch(s -> s.contains("Normalizing"));
        assertThat(stageValues).anyMatch(s -> s.contains("Validating"));
    }

    @Test
    void shouldExportWithPreset() throws IOException {
        ExportService service = new ExportService();
        float[][] audio = generateStereoSine(44100, 1.0, 440.0, 0.5f);

        ExportService.ExportServiceResult result = service.exportWithPreset(
                audio, 44100, tempDir, "preset_cd",
                ExportPreset.CD, ExportProgressListener.NONE);

        assertThat(result.exportResult().success()).isTrue();
        assertThat(result.exportResult().outputPath().getFileName().toString())
                .isEqualTo("preset_cd.wav");
    }

    @Test
    void shouldExportWithSpotifyPreset() throws IOException {
        ExportService service = new ExportService();
        float[][] audio = generateStereoSine(44100, 1.0, 440.0, 0.5f);

        ExportService.ExportServiceResult result = service.exportWithPreset(
                audio, 44100, tempDir, "preset_spotify",
                ExportPreset.SPOTIFY, ExportProgressListener.NONE);

        assertThat(result.exportResult().success()).isTrue();
    }

    @Test
    void shouldExportWithAppleMusicPreset() throws IOException {
        ExportService service = new ExportService();
        float[][] audio = generateStereoSine(44100, 1.0, 440.0, 0.5f);

        ExportService.ExportServiceResult result = service.exportWithPreset(
                audio, 44100, tempDir, "preset_apple",
                ExportPreset.APPLE_MUSIC, ExportProgressListener.NONE);

        assertThat(result.exportResult().success()).isTrue();
    }

    @Test
    void shouldReturnBuiltInPresets() {
        List<ExportPreset> presets = ExportService.getBuiltInPresets();
        assertThat(presets).hasSize(6);
        assertThat(presets).contains(
                ExportPreset.CD,
                ExportPreset.STREAMING,
                ExportPreset.HI_RES,
                ExportPreset.VINYL,
                ExportPreset.SPOTIFY,
                ExportPreset.APPLE_MUSIC
        );
    }

    @Test
    void shouldReturnSupportedSampleRates() {
        List<Integer> rates = ExportService.getSupportedSampleRates();
        assertThat(rates).containsExactly(44_100, 48_000, 88_200, 96_000);
    }

    @Test
    void shouldReturnSupportedBitDepths() {
        List<Integer> depths = ExportService.getSupportedBitDepths();
        assertThat(depths).containsExactly(16, 24, 32);
    }

    @Test
    void shouldReturnSupportedLoudnessTargets() {
        List<LoudnessTarget> targets = ExportService.getSupportedLoudnessTargets();
        assertThat(targets).isNotEmpty();
        assertThat(targets).contains(LoudnessTarget.SPOTIFY, LoudnessTarget.APPLE_MUSIC);
    }

    @Test
    void shouldHandleUnsupportedFormat() throws IOException {
        ExportService service = new ExportService();
        float[][] audio = generateStereoSine(44100, 1.0, 440.0, 0.5f);
        AudioExportConfig config = new AudioExportConfig(
                AudioExportFormat.MP3, 44100, 16, DitherType.NONE);

        ExportService.ExportServiceResult result = service.exportWithValidation(
                audio, 44100, tempDir, "mp3_test", config,
                ExportRange.FULL, null, ExportProgressListener.NONE);

        assertThat(result.exportResult().success()).isFalse();
        assertThat(result.exportResult().message()).contains("not yet implemented");
    }

    @Test
    void shouldExportWithSampleRateConversion() throws IOException {
        ExportService service = new ExportService();
        float[][] audio = generateStereoSine(96000, 1.0, 440.0, 0.5f);
        AudioExportConfig config = new AudioExportConfig(
                AudioExportFormat.WAV, 44100, 16, DitherType.TPDF);

        ExportService.ExportServiceResult result = service.exportWithValidation(
                audio, 96000, tempDir, "src_test", config,
                ExportRange.FULL, null, ExportProgressListener.NONE);

        assertThat(result.exportResult().success()).isTrue();
        assertThat(result.exportResult().outputPath()).exists();
    }

    @Test
    void shouldRejectNullArguments() {
        ExportService service = new ExportService();
        float[][] audio = generateStereoSine(44100, 0.1, 440.0, 0.5f);
        AudioExportConfig config = new AudioExportConfig(
                AudioExportFormat.WAV, 44100, 16, DitherType.NONE);

        assertThatThrownBy(() -> service.exportWithValidation(
                null, 44100, tempDir, "test", config,
                ExportRange.FULL, null, ExportProgressListener.NONE))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> service.exportWithValidation(
                audio, 44100, null, "test", config,
                ExportRange.FULL, null, ExportProgressListener.NONE))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> service.exportWithValidation(
                audio, 44100, tempDir, null, config,
                ExportRange.FULL, null, ExportProgressListener.NONE))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> service.exportWithValidation(
                audio, 44100, tempDir, "test", null,
                ExportRange.FULL, null, ExportProgressListener.NONE))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> service.exportWithValidation(
                audio, 44100, tempDir, "test", config,
                null, null, ExportProgressListener.NONE))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> service.exportWithValidation(
                audio, 44100, tempDir, "test", config,
                ExportRange.FULL, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    private static float[][] generateStereoSine(int sampleRate, double duration,
                                                 double freq, float amplitude) {
        int numSamples = (int) (sampleRate * duration);
        float[][] audio = new float[2][numSamples];
        for (int i = 0; i < numSamples; i++) {
            float value = (float) (amplitude * Math.sin(2.0 * Math.PI * freq * i / sampleRate));
            audio[0][i] = value;
            audio[1][i] = value;
        }
        return audio;
    }
}
