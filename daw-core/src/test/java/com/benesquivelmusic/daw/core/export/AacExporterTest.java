package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.sdk.export.AudioMetadata;
import com.benesquivelmusic.daw.sdk.export.DitherType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AacExporterTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldWriteValidAacFile() throws IOException {
        float[][] audio = generateStereoSine(44100, 1.0, 440.0);
        Path outputPath = tempDir.resolve("test.aac");

        AacExporter.write(audio, 44100, 16, DitherType.TPDF,
                AudioMetadata.EMPTY, 0.8, outputPath);

        assertThat(outputPath).exists();
        byte[] data = Files.readAllBytes(outputPath);
        assertThat(data.length).isGreaterThan(0);
        // Verify ADTS sync word: first 12 bits are 0xFFF
        assertThat(data[0] & 0xFF).isEqualTo(0xFF);
        assertThat(data[1] & 0xF0).isEqualTo(0xF0);
    }

    @Test
    void shouldWriteAacWithDithering() throws IOException {
        float[][] audio = generateStereoSine(44100, 0.5, 440.0);
        Path outputPath = tempDir.resolve("dithered.aac");

        AacExporter.write(audio, 44100, 24, DitherType.NOISE_SHAPED,
                AudioMetadata.EMPTY, 0.8, outputPath);

        assertThat(outputPath).exists();
        byte[] data = Files.readAllBytes(outputPath);
        assertThat(data.length).isGreaterThan(0);
    }

    @Test
    void shouldWriteMonoAac() throws IOException {
        float[][] audio = generateMonoSine(44100, 0.5, 440.0);
        Path outputPath = tempDir.resolve("mono.aac");

        AacExporter.write(audio, 44100, 16, DitherType.NONE,
                AudioMetadata.EMPTY, 0.5, outputPath);

        assertThat(outputPath).exists();
        byte[] data = Files.readAllBytes(outputPath);
        assertThat(data.length).isGreaterThan(0);
    }

    @Test
    void shouldWriteAacAtDifferentSampleRates() throws IOException {
        float[][] audio = generateStereoSine(48000, 0.5, 440.0);
        Path outputPath = tempDir.resolve("48k.aac");

        AacExporter.write(audio, 48000, 16, DitherType.NONE,
                AudioMetadata.EMPTY, 0.8, outputPath);

        assertThat(outputPath).exists();
    }

    @Test
    void shouldWriteAacAtDifferentQualities() throws IOException {
        float[][] audio = generateStereoSine(44100, 1.0, 440.0);
        Path lowQuality = tempDir.resolve("low.aac");
        Path highQuality = tempDir.resolve("high.aac");

        AacExporter.write(audio, 44100, 16, DitherType.NONE,
                AudioMetadata.EMPTY, 0.0, lowQuality);
        AacExporter.write(audio, 44100, 16, DitherType.NONE,
                AudioMetadata.EMPTY, 1.0, highQuality);

        assertThat(lowQuality).exists();
        assertThat(highQuality).exists();
        // Higher quality/bitrate should produce a larger file
        long lowSize = Files.size(lowQuality);
        long highSize = Files.size(highQuality);
        assertThat(highSize).isGreaterThanOrEqualTo(lowSize);
    }

    @Test
    void shouldRejectNullAudioData() {
        Path outputPath = tempDir.resolve("null.aac");

        assertThatThrownBy(() -> AacExporter.write(null, 44100, 16,
                DitherType.NONE, AudioMetadata.EMPTY, 0.8, outputPath))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectEmptyAudioData() {
        Path outputPath = tempDir.resolve("empty.aac");

        assertThatThrownBy(() -> AacExporter.write(new float[0][0], 44100, 16,
                DitherType.NONE, AudioMetadata.EMPTY, 0.8, outputPath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");
    }

    @Test
    void shouldRejectNonPositiveSampleRate() {
        float[][] audio = generateStereoSine(44100, 0.1, 440.0);
        Path outputPath = tempDir.resolve("bad.aac");

        assertThatThrownBy(() -> AacExporter.write(audio, 0, 16,
                DitherType.NONE, AudioMetadata.EMPTY, 0.8, outputPath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampleRate");
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

    private static float[][] generateMonoSine(int sampleRate, double duration, double freq) {
        int numSamples = (int) (sampleRate * duration);
        float[][] audio = new float[1][numSamples];
        for (int i = 0; i < numSamples; i++) {
            audio[0][i] = (float) Math.sin(2.0 * Math.PI * freq * i / sampleRate);
        }
        return audio;
    }
}
