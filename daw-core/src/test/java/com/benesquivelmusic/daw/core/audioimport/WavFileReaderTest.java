package com.benesquivelmusic.daw.core.audioimport;

import com.benesquivelmusic.daw.sdk.export.AudioMetadata;
import com.benesquivelmusic.daw.sdk.export.DitherType;
import com.benesquivelmusic.daw.core.export.WavExporter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

class WavFileReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldReadStereo16BitWav() throws IOException {
        float[][] audio = generateStereoSine(44100, 1.0, 440.0);
        Path wavFile = tempDir.resolve("stereo16.wav");
        WavExporter.write(audio, 44100, 16, DitherType.NONE, AudioMetadata.EMPTY, wavFile);

        WavFileReader.WavReadResult result = WavFileReader.read(wavFile);

        assertThat(result.sampleRate()).isEqualTo(44100);
        assertThat(result.channels()).isEqualTo(2);
        assertThat(result.bitDepth()).isEqualTo(16);
        assertThat(result.numFrames()).isEqualTo(44100);
        assertThat(result.durationSeconds()).isCloseTo(1.0, offset(0.001));
        assertThat(result.audioData()).hasNumberOfRows(2);
    }

    @Test
    void shouldReadMono16BitWav() throws IOException {
        float[][] audio = new float[1][1000];
        for (int i = 0; i < 1000; i++) {
            audio[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100));
        }
        Path wavFile = tempDir.resolve("mono16.wav");
        WavExporter.write(audio, 44100, 16, DitherType.NONE, AudioMetadata.EMPTY, wavFile);

        WavFileReader.WavReadResult result = WavFileReader.read(wavFile);

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
        Path wavFile = tempDir.resolve("roundtrip16.wav");
        WavExporter.write(audio, sampleRate, 16, DitherType.NONE, AudioMetadata.EMPTY, wavFile);

        WavFileReader.WavReadResult result = WavFileReader.read(wavFile);

        // 16-bit quantization error should be small
        for (int i = 0; i < numSamples; i++) {
            assertThat((double) result.audioData()[0][i])
                    .isCloseTo(audio[0][i], offset(0.001));
        }
    }

    @Test
    void shouldRoundTrip24BitAccurately() throws IOException {
        int sampleRate = 48000;
        int numSamples = 500;
        float[][] audio = new float[2][numSamples];
        for (int i = 0; i < numSamples; i++) {
            audio[0][i] = (float) (0.7 * Math.sin(2.0 * Math.PI * 1000.0 * i / sampleRate));
            audio[1][i] = (float) (0.3 * Math.cos(2.0 * Math.PI * 1000.0 * i / sampleRate));
        }
        Path wavFile = tempDir.resolve("roundtrip24.wav");
        WavExporter.write(audio, sampleRate, 24, DitherType.NONE, AudioMetadata.EMPTY, wavFile);

        WavFileReader.WavReadResult result = WavFileReader.read(wavFile);

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
    void shouldRoundTrip32BitFloatAccurately() throws IOException {
        int sampleRate = 96000;
        int numSamples = 200;
        float[][] audio = new float[2][numSamples];
        for (int i = 0; i < numSamples; i++) {
            audio[0][i] = (float) (0.8 * Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate));
            audio[1][i] = (float) (0.8 * Math.cos(2.0 * Math.PI * 440.0 * i / sampleRate));
        }
        Path wavFile = tempDir.resolve("roundtrip32f.wav");
        WavExporter.write(audio, sampleRate, 32, DitherType.NONE, AudioMetadata.EMPTY, wavFile);

        WavFileReader.WavReadResult result = WavFileReader.read(wavFile);

        assertThat(result.sampleRate()).isEqualTo(96000);
        assertThat(result.channels()).isEqualTo(2);
        assertThat(result.bitDepth()).isEqualTo(32);
        // Float round-trip should be exact
        for (int i = 0; i < numSamples; i++) {
            assertThat(result.audioData()[0][i]).isEqualTo(audio[0][i]);
            assertThat(result.audioData()[1][i]).isEqualTo(audio[1][i]);
        }
    }

    @Test
    void shouldRead8BitWav() throws IOException {
        float[][] audio = new float[1][100];
        for (int i = 0; i < 100; i++) {
            audio[0][i] = 0.0f;
        }
        Path wavFile = tempDir.resolve("silence8.wav");
        WavExporter.write(audio, 44100, 8, DitherType.NONE, AudioMetadata.EMPTY, wavFile);

        WavFileReader.WavReadResult result = WavFileReader.read(wavFile);

        assertThat(result.bitDepth()).isEqualTo(8);
        assertThat(result.channels()).isEqualTo(1);
        // 8-bit silence should be near 0.0
        for (int i = 0; i < 100; i++) {
            assertThat((double) result.audioData()[0][i]).isCloseTo(0.0, offset(0.02));
        }
    }

    @Test
    void shouldReadWavWithMetadata() throws IOException {
        // WAV files with LIST INFO metadata chunks should still be readable
        float[][] audio = new float[1][100];
        AudioMetadata metadata = new AudioMetadata("Song", "Artist", "Album", null);
        Path wavFile = tempDir.resolve("meta.wav");
        WavExporter.write(audio, 44100, 16, DitherType.NONE, metadata, wavFile);

        WavFileReader.WavReadResult result = WavFileReader.read(wavFile);

        assertThat(result.sampleRate()).isEqualTo(44100);
        assertThat(result.channels()).isEqualTo(1);
        assertThat(result.numFrames()).isEqualTo(100);
    }

    @Test
    void shouldRejectNullPath() {
        assertThatThrownBy(() -> WavFileReader.read(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNonExistentFile() {
        Path nonExistent = tempDir.resolve("missing.wav");

        assertThatThrownBy(() -> WavFileReader.read(nonExistent))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void shouldRejectNonWavFile() throws IOException {
        Path textFile = tempDir.resolve("not_a_wav.wav");
        Files.writeString(textFile, "This is not a WAV file at all");

        assertThatThrownBy(() -> WavFileReader.read(textFile))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectTooSmallFile() throws IOException {
        Path tinyFile = tempDir.resolve("tiny.wav");
        Files.write(tinyFile, new byte[10]);

        assertThatThrownBy(() -> WavFileReader.read(tinyFile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too small");
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
