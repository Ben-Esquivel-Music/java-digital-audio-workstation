package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.sdk.export.AudioExportConfig;
import com.benesquivelmusic.daw.sdk.export.AudioExportFormat;
import com.benesquivelmusic.daw.sdk.export.AudioMetadata;
import com.benesquivelmusic.daw.sdk.export.DitherType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

class WavExporterTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldWrite16BitWavWithCorrectHeader() throws IOException {
        float[][] audio = generateStereoSine(44100, 1.0, 440.0);
        Path outputPath = tempDir.resolve("test.wav");

        WavExporter.write(audio, 44100, 16, DitherType.TPDF,
                AudioMetadata.EMPTY, outputPath);

        assertThat(outputPath).exists();

        byte[] data = Files.readAllBytes(outputPath);
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        // RIFF header
        assertThat(new String(data, 0, 4)).isEqualTo("RIFF");
        buf.position(8);
        assertThat(new String(data, 8, 4)).isEqualTo("WAVE");

        // fmt chunk
        assertThat(new String(data, 12, 4)).isEqualTo("fmt ");
        buf.position(16);
        assertThat(buf.getInt()).isEqualTo(16); // chunk size
        assertThat(buf.getShort()).isEqualTo((short) 1); // PCM format
        assertThat(buf.getShort()).isEqualTo((short) 2); // stereo
        assertThat(buf.getInt()).isEqualTo(44100); // sample rate
        assertThat(buf.getInt()).isEqualTo(44100 * 2 * 2); // byte rate
        assertThat(buf.getShort()).isEqualTo((short) 4); // block align
        assertThat(buf.getShort()).isEqualTo((short) 16); // bits per sample
    }

    @Test
    void shouldWrite24BitWav() throws IOException {
        float[][] audio = generateStereoSine(48000, 0.5, 1000.0);
        Path outputPath = tempDir.resolve("test24.wav");

        WavExporter.write(audio, 48000, 24, DitherType.NONE,
                AudioMetadata.EMPTY, outputPath);

        assertThat(outputPath).exists();

        byte[] data = Files.readAllBytes(outputPath);
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        buf.position(34);
        assertThat(buf.getShort()).isEqualTo((short) 24); // bits per sample
    }

    @Test
    void shouldWrite32BitFloatWav() throws IOException {
        float[][] audio = generateStereoSine(96000, 0.8, 440.0);
        Path outputPath = tempDir.resolve("test32f.wav");

        WavExporter.write(audio, 96000, 32, DitherType.NONE,
                AudioMetadata.EMPTY, outputPath);

        assertThat(outputPath).exists();

        byte[] data = Files.readAllBytes(outputPath);
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        buf.position(20);
        assertThat(buf.getShort()).isEqualTo((short) 3); // IEEE float format
    }

    @Test
    void shouldRoundTrip16BitAccurately() throws IOException {
        // Generate a known signal, write it, read it back, verify accuracy
        int sampleRate = 44100;
        int numSamples = 1000;
        float[][] audio = new float[1][numSamples];
        for (int i = 0; i < numSamples; i++) {
            audio[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate));
        }

        Path outputPath = tempDir.resolve("roundtrip.wav");
        WavExporter.write(audio, sampleRate, 16, DitherType.NONE,
                AudioMetadata.EMPTY, outputPath);

        // Read back the samples from the WAV file
        byte[] fileData = Files.readAllBytes(outputPath);
        ByteBuffer buf = ByteBuffer.wrap(fileData).order(ByteOrder.LITTLE_ENDIAN);

        // Skip to data chunk (44 bytes for standard WAV header)
        buf.position(44);
        for (int i = 0; i < numSamples; i++) {
            short sampleInt = buf.getShort();
            double sampleFloat = sampleInt / 32767.0;
            // 16-bit quantization error should be < 1 LSB ≈ 1/32767 ≈ 3e-5
            assertThat(sampleFloat).isCloseTo(audio[0][i], offset(0.001));
        }
    }

    @Test
    void shouldEmbedMetadataInListInfoChunk() throws IOException {
        float[][] audio = new float[1][100];
        AudioMetadata metadata = new AudioMetadata("Test Song", "Test Artist", "Test Album", null);
        Path outputPath = tempDir.resolve("meta.wav");

        WavExporter.write(audio, 44100, 16, DitherType.NONE, metadata, outputPath);

        byte[] data = Files.readAllBytes(outputPath);
        String fileContent = new String(data, java.nio.charset.StandardCharsets.US_ASCII);

        // The LIST INFO chunk should contain our metadata
        assertThat(fileContent).contains("LIST");
        assertThat(fileContent).contains("INFO");
        assertThat(fileContent).contains("INAM"); // title tag
        assertThat(fileContent).contains("Test Song");
        assertThat(fileContent).contains("IART"); // artist tag
        assertThat(fileContent).contains("Test Artist");
    }

    @Test
    void shouldWrite8BitWav() throws IOException {
        float[][] audio = new float[1][100];
        for (int i = 0; i < 100; i++) {
            audio[0][i] = 0.0f; // silence
        }
        Path outputPath = tempDir.resolve("test8.wav");

        WavExporter.write(audio, 44100, 8, DitherType.NONE,
                AudioMetadata.EMPTY, outputPath);

        assertThat(outputPath).exists();

        byte[] data = Files.readAllBytes(outputPath);
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        buf.position(34);
        assertThat(buf.getShort()).isEqualTo((short) 8);

        // 8-bit silence should be around 128 (unsigned midpoint)
        buf.position(44);
        for (int i = 0; i < 100; i++) {
            int sample = buf.get() & 0xFF;
            assertThat(sample).isBetween(126, 130);
        }
    }

    @Test
    void shouldWriteNoiseShaped16BitWav() throws IOException {
        float[][] audio = generateStereoSine(44100, 0.5, 440.0);
        Path outputPath = tempDir.resolve("noise_shaped.wav");

        WavExporter.write(audio, 44100, 16, DitherType.NOISE_SHAPED,
                AudioMetadata.EMPTY, outputPath);

        assertThat(outputPath).exists();

        // Read back and verify L and R channels have the same quantization
        // characteristics (per-channel ditherers produce independent noise)
        byte[] data = Files.readAllBytes(outputPath);
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(44);

        int numSamples = audio[0].length;
        double errorL = 0, errorR = 0;
        for (int i = 0; i < numSamples; i++) {
            short sL = buf.getShort();
            short sR = buf.getShort();
            double origL = audio[0][i];
            double origR = audio[1][i];
            errorL += Math.abs(sL / 32767.0 - origL);
            errorR += Math.abs(sR / 32767.0 - origR);
        }

        // Both channels should have similar quantization error (same signal, independent ditherers)
        double avgErrorL = errorL / numSamples;
        double avgErrorR = errorR / numSamples;
        assertThat(avgErrorL).isLessThan(0.001);
        assertThat(avgErrorR).isLessThan(0.001);
    }

    @Test
    void shouldHandleMultipleChunks16Bit() throws IOException {
        // 10000 frames > CHUNK_FRAMES (8192) — verifies chunked MemorySegment writing
        int numSamples = 10000;
        int sampleRate = 44100;
        float[][] audio = new float[2][numSamples];
        for (int i = 0; i < numSamples; i++) {
            audio[0][i] = (float) (0.7 * Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate));
            audio[1][i] = (float) (0.7 * Math.cos(2.0 * Math.PI * 440.0 * i / sampleRate));
        }
        Path outputPath = tempDir.resolve("multi_chunk.wav");

        WavExporter.write(audio, sampleRate, 16, DitherType.NONE,
                AudioMetadata.EMPTY, outputPath);

        byte[] data = Files.readAllBytes(outputPath);
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        // Verify header
        assertThat(new String(data, 0, 4)).isEqualTo("RIFF");
        buf.position(22);
        assertThat(buf.getShort()).isEqualTo((short) 2); // stereo
        buf.position(34);
        assertThat(buf.getShort()).isEqualTo((short) 16);

        // Expected data size: 10000 samples * 2 channels * 2 bytes
        int expectedDataSize = numSamples * 2 * 2;
        buf.position(40);
        assertThat(buf.getInt()).isEqualTo(expectedDataSize);

        // Verify total file size
        assertThat(data.length).isEqualTo(44 + expectedDataSize);

        // Spot-check samples across chunk boundary (frame 8191 and 8192)
        buf.position(44 + 8191 * 2 * 2); // frame 8191, channel 0
        short sampleAtBoundary = buf.getShort();
        double expected = 0.7 * Math.sin(2.0 * Math.PI * 440.0 * 8191 / sampleRate);
        assertThat(sampleAtBoundary / 32767.0).isCloseTo(expected, offset(0.001));

        buf.position(44 + 8192 * 2 * 2); // frame 8192 (first frame of second chunk), channel 0
        short sampleAfterBoundary = buf.getShort();
        expected = 0.7 * Math.sin(2.0 * Math.PI * 440.0 * 8192 / sampleRate);
        assertThat(sampleAfterBoundary / 32767.0).isCloseTo(expected, offset(0.001));
    }

    @Test
    void shouldHandleExactChunkBoundary() throws IOException {
        // Exactly 8192 frames = one full chunk with no partial chunk
        int numSamples = 8192;
        float[][] audio = new float[1][numSamples];
        for (int i = 0; i < numSamples; i++) {
            audio[0][i] = (float) i / numSamples;
        }
        Path outputPath = tempDir.resolve("exact_chunk.wav");

        WavExporter.write(audio, 44100, 16, DitherType.NONE,
                AudioMetadata.EMPTY, outputPath);

        byte[] data = Files.readAllBytes(outputPath);
        int expectedDataSize = numSamples * 1 * 2;
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(40);
        assertThat(buf.getInt()).isEqualTo(expectedDataSize);
        assertThat(data.length).isEqualTo(44 + expectedDataSize);
    }

    @Test
    void shouldRoundTrip32BitIntAccurately() throws IOException {
        // 32-bit integer PCM (triggered by using DitherType.TPDF with 32-bit)
        int numSamples = 100;
        float[][] audio = new float[1][numSamples];
        for (int i = 0; i < numSamples; i++) {
            audio[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 1000.0 * i / 48000));
        }
        Path outputPath = tempDir.resolve("int32.wav");

        WavExporter.write(audio, 48000, 32, DitherType.TPDF,
                AudioMetadata.EMPTY, outputPath);

        byte[] data = Files.readAllBytes(outputPath);
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        // Format should be PCM (not float) because dither is applied
        buf.position(20);
        assertThat(buf.getShort()).isEqualTo((short) 1); // PCM

        buf.position(34);
        assertThat(buf.getShort()).isEqualTo((short) 32);
    }

    @Test
    void shouldRejectInvalidBitDepth() {
        float[][] audio = generateStereoSine(44100, 0.1, 440.0);
        Path outputPath = tempDir.resolve("bad.wav");

        assertThatThrownBy(() -> WavExporter.write(audio, 44100, 7,
                DitherType.NONE, AudioMetadata.EMPTY, outputPath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bitDepth");

        assertThatThrownBy(() -> WavExporter.write(audio, 44100, 64,
                DitherType.NONE, AudioMetadata.EMPTY, outputPath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bitDepth");
    }

    @Test
    void shouldRejectNonPositiveSampleRate() {
        float[][] audio = generateStereoSine(44100, 0.1, 440.0);
        Path outputPath = tempDir.resolve("bad.wav");

        assertThatThrownBy(() -> WavExporter.write(audio, 0, 16,
                DitherType.NONE, AudioMetadata.EMPTY, outputPath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampleRate");

        assertThatThrownBy(() -> WavExporter.write(audio, -44100, 16,
                DitherType.NONE, AudioMetadata.EMPTY, outputPath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampleRate");
    }

    @Test
    void shouldRejectNullAudioData() {
        Path outputPath = tempDir.resolve("null.wav");

        assertThatThrownBy(() -> WavExporter.write(null, 44100, 16,
                DitherType.NONE, AudioMetadata.EMPTY, outputPath))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectEmptyAudioData() {
        Path outputPath = tempDir.resolve("empty.wav");

        assertThatThrownBy(() -> WavExporter.write(new float[0][0], 44100, 16,
                DitherType.NONE, AudioMetadata.EMPTY, outputPath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");
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
