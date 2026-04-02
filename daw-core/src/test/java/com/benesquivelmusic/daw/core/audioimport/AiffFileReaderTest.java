package com.benesquivelmusic.daw.core.audioimport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

class AiffFileReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldReadStereo16BitAiff() throws IOException {
        float[][] audio = generateStereoSine(44100, 1.0, 440.0);
        Path aiffFile = tempDir.resolve("stereo16.aiff");
        writeTestAiff(aiffFile, audio, 44100, 16);

        AudioReadResult result = AiffFileReader.read(aiffFile);

        assertThat(result.sampleRate()).isEqualTo(44100);
        assertThat(result.channels()).isEqualTo(2);
        assertThat(result.bitDepth()).isEqualTo(16);
        assertThat(result.numFrames()).isEqualTo(44100);
        assertThat(result.durationSeconds()).isCloseTo(1.0, offset(0.001));
        assertThat(result.audioData()).hasNumberOfRows(2);
    }

    @Test
    void shouldReadMono16BitAiff() throws IOException {
        int numSamples = 1000;
        float[][] audio = new float[1][numSamples];
        for (int i = 0; i < numSamples; i++) {
            audio[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100));
        }
        Path aiffFile = tempDir.resolve("mono16.aiff");
        writeTestAiff(aiffFile, audio, 44100, 16);

        AudioReadResult result = AiffFileReader.read(aiffFile);

        assertThat(result.sampleRate()).isEqualTo(44100);
        assertThat(result.channels()).isEqualTo(1);
        assertThat(result.bitDepth()).isEqualTo(16);
        assertThat(result.numFrames()).isEqualTo(1000);
    }

    @Test
    void shouldRoundTrip16BitAccurately() throws IOException {
        int sampleRate = 44100;
        int numSamples = 500;
        float[][] audio = new float[1][numSamples];
        for (int i = 0; i < numSamples; i++) {
            audio[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate));
        }
        Path aiffFile = tempDir.resolve("roundtrip16.aiff");
        writeTestAiff(aiffFile, audio, sampleRate, 16);

        AudioReadResult result = AiffFileReader.read(aiffFile);

        for (int i = 0; i < numSamples; i++) {
            assertThat((double) result.audioData()[0][i])
                    .isCloseTo(audio[0][i], offset(0.001));
        }
    }

    @Test
    void shouldReadStereo24BitAiff() throws IOException {
        int sampleRate = 48000;
        int numSamples = 500;
        float[][] audio = new float[2][numSamples];
        for (int i = 0; i < numSamples; i++) {
            audio[0][i] = (float) (0.7 * Math.sin(2.0 * Math.PI * 1000.0 * i / sampleRate));
            audio[1][i] = (float) (0.3 * Math.cos(2.0 * Math.PI * 1000.0 * i / sampleRate));
        }
        Path aiffFile = tempDir.resolve("stereo24.aiff");
        writeTestAiff(aiffFile, audio, sampleRate, 24);

        AudioReadResult result = AiffFileReader.read(aiffFile);

        assertThat(result.sampleRate()).isEqualTo(48000);
        assertThat(result.channels()).isEqualTo(2);
        assertThat(result.bitDepth()).isEqualTo(24);
        for (int i = 0; i < numSamples; i++) {
            assertThat((double) result.audioData()[0][i])
                    .isCloseTo(audio[0][i], offset(0.0001));
            assertThat((double) result.audioData()[1][i])
                    .isCloseTo(audio[1][i], offset(0.0001));
        }
    }

    @Test
    void shouldReadAiffWithAifExtension() throws IOException {
        float[][] audio = generateStereoSine(44100, 0.5, 440.0);
        Path aifFile = tempDir.resolve("test.aif");
        writeTestAiff(aifFile, audio, 44100, 16);

        AudioReadResult result = AiffFileReader.read(aifFile);

        assertThat(result.sampleRate()).isEqualTo(44100);
        assertThat(result.channels()).isEqualTo(2);
    }

    @Test
    void shouldRejectNullPath() {
        assertThatThrownBy(() -> AiffFileReader.read(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNonExistentFile() {
        Path nonExistent = tempDir.resolve("missing.aiff");

        assertThatThrownBy(() -> AiffFileReader.read(nonExistent))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void shouldRejectNonAiffFile() throws IOException {
        Path textFile = tempDir.resolve("not_an_aiff.aiff");
        Files.writeString(textFile, "This is not an AIFF file at all");

        assertThatThrownBy(() -> AiffFileReader.read(textFile))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectTooSmallFile() throws IOException {
        Path tinyFile = tempDir.resolve("tiny.aiff");
        Files.write(tinyFile, new byte[5]);

        assertThatThrownBy(() -> AiffFileReader.read(tinyFile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too small");
    }

    // ── Helper methods ──────────────────────────────────────────────────────

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

    /**
     * Writes a minimal AIFF file for testing purposes.
     */
    private static void writeTestAiff(Path path, float[][] audioData, int sampleRate,
                                      int bitDepth) throws IOException {
        int channels = audioData.length;
        int numFrames = audioData[0].length;
        int bytesPerSample = bitDepth / 8;
        int ssndDataSize = numFrames * channels * bytesPerSample;

        // COMM chunk: 18 bytes (channels=2, frames=4, bitDepth=2, sampleRate=10)
        int commChunkSize = 18;
        // SSND chunk: 8 (offset+blockSize) + ssndDataSize
        int ssndChunkSize = 8 + ssndDataSize;

        int formSize = 4 + 8 + commChunkSize + 8 + ssndChunkSize; // "AIFF" + COMM header + data + SSND header + data

        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // FORM header
        baos.write("FORM".getBytes());
        writeInt32BE(baos, formSize);
        baos.write("AIFF".getBytes());

        // COMM chunk
        baos.write("COMM".getBytes());
        writeInt32BE(baos, commChunkSize);
        writeInt16BE(baos, channels);
        writeInt32BE(baos, numFrames);
        writeInt16BE(baos, bitDepth);
        writeExtended80(baos, sampleRate);

        // SSND chunk
        baos.write("SSND".getBytes());
        writeInt32BE(baos, ssndChunkSize);
        writeInt32BE(baos, 0); // offset
        writeInt32BE(baos, 0); // block size

        // Write audio data (big-endian PCM)
        for (int frame = 0; frame < numFrames; frame++) {
            for (int ch = 0; ch < channels; ch++) {
                float sample = audioData[ch][frame];
                sample = Math.max(-1.0f, Math.min(1.0f, sample));
                switch (bitDepth) {
                    case 8 -> baos.write((byte) (sample * 127));
                    case 16 -> {
                        short s = (short) (sample * 32767);
                        writeInt16BE(baos, s);
                    }
                    case 24 -> {
                        int value = (int) (sample * 8388607);
                        baos.write((value >> 16) & 0xFF);
                        baos.write((value >> 8) & 0xFF);
                        baos.write(value & 0xFF);
                    }
                    case 32 -> {
                        int value = (int) (sample * 2147483647.0f);
                        writeInt32BE(baos, value);
                    }
                }
            }
        }

        Files.write(path, baos.toByteArray());
    }

    private static void writeInt32BE(OutputStream out, int value) throws IOException {
        out.write((value >> 24) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeInt16BE(OutputStream out, int value) throws IOException {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    /**
     * Writes a sample rate as an 80-bit IEEE 754 extended precision float.
     */
    private static void writeExtended80(OutputStream out, int sampleRate) throws IOException {
        // Convert integer sample rate to 80-bit extended float
        // For positive integers, this is straightforward
        if (sampleRate == 0) {
            out.write(new byte[10]);
            return;
        }

        int exponent = 16383 + 63; // bias + 63 (since mantissa is 64-bit with integer bit)
        long mantissa = sampleRate;

        // Normalize: shift mantissa left until MSB is set
        while ((mantissa & (1L << 63)) == 0) {
            mantissa <<= 1;
            exponent--;
        }

        // Write: 2 bytes exponent, 8 bytes mantissa
        out.write((exponent >> 8) & 0xFF);
        out.write(exponent & 0xFF);
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.putLong(mantissa);
        out.write(buf.array());
    }
}
