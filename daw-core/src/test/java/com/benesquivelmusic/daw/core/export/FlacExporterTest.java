package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.sdk.export.DitherType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlacExporterTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldWriteFlacFileWithCorrectMagicBytes() throws IOException {
        float[][] audio = generateStereoSine(44100, 0.5, 440.0);
        Path outputPath = tempDir.resolve("test.flac");

        FlacExporter.write(audio, 44100, 16, DitherType.NONE, outputPath);

        assertThat(outputPath).exists();
        byte[] data = Files.readAllBytes(outputPath);
        assertThat(data.length).isGreaterThan(42); // header + at least some data
        // FLAC magic: "fLaC"
        assertThat(new String(data, 0, 4)).isEqualTo("fLaC");
    }

    @Test
    void shouldWriteStreamInfoBlock() throws IOException {
        float[][] audio = generateStereoSine(44100, 0.1, 440.0);
        Path outputPath = tempDir.resolve("streaminfo.flac");

        FlacExporter.write(audio, 44100, 16, DitherType.NONE, outputPath);

        byte[] data = Files.readAllBytes(outputPath);
        // After "fLaC" (4 bytes), the STREAMINFO block header:
        // byte 4: 0x80 (last-metadata-block flag + type 0)
        assertThat(data[4] & 0xFF).isEqualTo(0x80);
        // bytes 5-7: length = 34
        assertThat(data[7] & 0xFF).isEqualTo(34);
    }

    @Test
    void shouldWrite24BitFlac() throws IOException {
        float[][] audio = generateStereoSine(96000, 0.2, 1000.0);
        Path outputPath = tempDir.resolve("test24.flac");

        FlacExporter.write(audio, 96000, 24, DitherType.NONE, outputPath);

        assertThat(outputPath).exists();
        byte[] data = Files.readAllBytes(outputPath);
        assertThat(new String(data, 0, 4)).isEqualTo("fLaC");
        assertThat(data.length).isGreaterThan(42);
    }

    @Test
    void shouldWriteFlacWithTpdfDithering() throws IOException {
        float[][] audio = generateStereoSine(44100, 0.5, 440.0);
        Path outputPath = tempDir.resolve("tpdf.flac");

        FlacExporter.write(audio, 44100, 16, DitherType.TPDF, outputPath);

        assertThat(outputPath).exists();
        byte[] data = Files.readAllBytes(outputPath);
        assertThat(new String(data, 0, 4)).isEqualTo("fLaC");
    }

    @Test
    void shouldWriteFlacWithNoiseShapedDithering() throws IOException {
        float[][] audio = generateStereoSine(44100, 0.5, 440.0);
        Path outputPath = tempDir.resolve("ns.flac");

        FlacExporter.write(audio, 44100, 16, DitherType.NOISE_SHAPED, outputPath);

        assertThat(outputPath).exists();
        byte[] data = Files.readAllBytes(outputPath);
        assertThat(new String(data, 0, 4)).isEqualTo("fLaC");
    }

    @Test
    void shouldWriteMonoFlac() throws IOException {
        float[][] audio = new float[1][4410]; // 0.1 seconds mono
        for (int i = 0; i < audio[0].length; i++) {
            audio[0][i] = (float) Math.sin(2.0 * Math.PI * 440.0 * i / 44100);
        }
        Path outputPath = tempDir.resolve("mono.flac");

        FlacExporter.write(audio, 44100, 16, DitherType.NONE, outputPath);

        assertThat(outputPath).exists();
        byte[] data = Files.readAllBytes(outputPath);
        assertThat(new String(data, 0, 4)).isEqualTo("fLaC");
    }

    @Test
    void shouldHandleMultipleBlocks() throws IOException {
        // 10000 frames > FLAC_BLOCK_SIZE (4096) — verifies multi-frame writing
        float[][] audio = generateStereoSine(44100, 0.5, 440.0); // 22050 samples
        Path outputPath = tempDir.resolve("multiblock.flac");

        FlacExporter.write(audio, 44100, 16, DitherType.NONE, outputPath);

        assertThat(outputPath).exists();
        byte[] data = Files.readAllBytes(outputPath);
        assertThat(new String(data, 0, 4)).isEqualTo("fLaC");
        // With verbatim encoding, file should be approximately:
        // header + streaminfo + frames ≈ 4 + 38 + 22050 * 2 * 2 + frame overhead
        assertThat(data.length).isGreaterThan(44100);
    }

    @Test
    void shouldRejectNullAudioData() {
        Path outputPath = tempDir.resolve("null.flac");
        assertThatThrownBy(() -> FlacExporter.write(null, 44100, 16,
                DitherType.NONE, outputPath))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectEmptyAudioData() {
        Path outputPath = tempDir.resolve("empty.flac");
        assertThatThrownBy(() -> FlacExporter.write(new float[0][0], 44100, 16,
                DitherType.NONE, outputPath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");
    }

    @Test
    void shouldRejectInvalidBitDepth() {
        float[][] audio = generateStereoSine(44100, 0.1, 440.0);
        Path outputPath = tempDir.resolve("bad.flac");
        assertThatThrownBy(() -> FlacExporter.write(audio, 44100, 32,
                DitherType.NONE, outputPath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bitDepth");
    }

    @Test
    void shouldRejectNonPositiveSampleRate() {
        float[][] audio = generateStereoSine(44100, 0.1, 440.0);
        Path outputPath = tempDir.resolve("bad.flac");
        assertThatThrownBy(() -> FlacExporter.write(audio, 0, 16,
                DitherType.NONE, outputPath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampleRate");
    }

    @Test
    void flacFilesShouldBeDifferentSizeForDifferentBitDepths() throws IOException {
        float[][] audio = generateStereoSine(44100, 0.5, 440.0);

        Path path16 = tempDir.resolve("depth16.flac");
        Path path24 = tempDir.resolve("depth24.flac");

        FlacExporter.write(audio, 44100, 16, DitherType.NONE, path16);
        FlacExporter.write(audio, 44100, 24, DitherType.NONE, path24);

        long size16 = Files.size(path16);
        long size24 = Files.size(path24);
        // 24-bit should produce a larger file than 16-bit
        assertThat(size24).isGreaterThan(size16);
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
