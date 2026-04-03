package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.sdk.export.AudioMetadata;
import com.benesquivelmusic.daw.sdk.export.DitherType;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class Mp3ExporterTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void checkNativeLibrary() {
        assumeTrue(NativeCodecAvailability.isLameAvailable(),
                "libmp3lame not available — skipping MP3 exporter tests");
    }

    @Test
    void shouldWriteValidMp3File() throws IOException {
        float[][] audio = generateStereoSine(44100, 1.0, 440.0);
        Path outputPath = tempDir.resolve("test.mp3");

        Mp3Exporter.write(audio, 44100, 16, DitherType.TPDF,
                AudioMetadata.EMPTY, 0.8, outputPath);

        assertThat(outputPath).exists();
        byte[] data = Files.readAllBytes(outputPath);
        assertThat(data.length).isGreaterThan(0);
    }

    @Test
    void shouldWriteMp3WithMetadata() throws IOException {
        float[][] audio = generateStereoSine(44100, 0.5, 440.0);
        AudioMetadata metadata = new AudioMetadata("Test Song", "Test Artist", "Test Album", null);
        Path outputPath = tempDir.resolve("meta.mp3");

        Mp3Exporter.write(audio, 44100, 16, DitherType.NONE,
                metadata, 0.8, outputPath);

        assertThat(outputPath).exists();
        byte[] data = Files.readAllBytes(outputPath);
        assertThat(data.length).isGreaterThan(0);
    }

    @Test
    void shouldWriteMp3WithNoiseShapedDithering() throws IOException {
        float[][] audio = generateStereoSine(44100, 0.5, 440.0);
        Path outputPath = tempDir.resolve("noise_shaped.mp3");

        Mp3Exporter.write(audio, 44100, 24, DitherType.NOISE_SHAPED,
                AudioMetadata.EMPTY, 0.8, outputPath);

        assertThat(outputPath).exists();
        byte[] data = Files.readAllBytes(outputPath);
        assertThat(data.length).isGreaterThan(0);
    }

    @Test
    void shouldWriteMonoMp3() throws IOException {
        float[][] audio = generateMonoSine(44100, 0.5, 440.0);
        Path outputPath = tempDir.resolve("mono.mp3");

        Mp3Exporter.write(audio, 44100, 16, DitherType.NONE,
                AudioMetadata.EMPTY, 0.5, outputPath);

        assertThat(outputPath).exists();
        byte[] data = Files.readAllBytes(outputPath);
        assertThat(data.length).isGreaterThan(0);
    }

    @Test
    void shouldWriteMp3AtDifferentSampleRates() throws IOException {
        float[][] audio = generateStereoSine(48000, 0.5, 440.0);
        Path outputPath = tempDir.resolve("48k.mp3");

        Mp3Exporter.write(audio, 48000, 16, DitherType.NONE,
                AudioMetadata.EMPTY, 0.8, outputPath);

        assertThat(outputPath).exists();
    }

    @Test
    void shouldWriteMp3AtLowQuality() throws IOException {
        float[][] audio = generateStereoSine(44100, 0.5, 440.0);
        Path lowQuality = tempDir.resolve("low.mp3");
        Path highQuality = tempDir.resolve("high.mp3");

        Mp3Exporter.write(audio, 44100, 16, DitherType.NONE,
                AudioMetadata.EMPTY, 0.0, lowQuality);
        Mp3Exporter.write(audio, 44100, 16, DitherType.NONE,
                AudioMetadata.EMPTY, 1.0, highQuality);

        assertThat(lowQuality).exists();
        assertThat(highQuality).exists();
        // Higher quality VBR should generally produce a larger file
        long lowSize = Files.size(lowQuality);
        long highSize = Files.size(highQuality);
        assertThat(highSize).isGreaterThanOrEqualTo(lowSize);
    }

    @Test
    void shouldRejectNullAudioData() {
        Path outputPath = tempDir.resolve("null.mp3");

        assertThatThrownBy(() -> Mp3Exporter.write(null, 44100, 16,
                DitherType.NONE, AudioMetadata.EMPTY, 0.8, outputPath))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectEmptyAudioData() {
        Path outputPath = tempDir.resolve("empty.mp3");

        assertThatThrownBy(() -> Mp3Exporter.write(new float[0][0], 44100, 16,
                DitherType.NONE, AudioMetadata.EMPTY, 0.8, outputPath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");
    }

    @Test
    void shouldRejectNonPositiveSampleRate() {
        float[][] audio = generateStereoSine(44100, 0.1, 440.0);
        Path outputPath = tempDir.resolve("bad.mp3");

        assertThatThrownBy(() -> Mp3Exporter.write(audio, 0, 16,
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
