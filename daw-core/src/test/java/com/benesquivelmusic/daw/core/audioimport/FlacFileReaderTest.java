package com.benesquivelmusic.daw.core.audioimport;

import com.benesquivelmusic.daw.core.export.FlacExporter;
import com.benesquivelmusic.daw.sdk.export.DitherType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class FlacFileReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldReadStereo16BitFlac() throws IOException {
        float[][] audio = generateStereoSine(44100, 1.0, 440.0);
        Path flacFile = tempDir.resolve("stereo16.flac");
        FlacExporter.write(audio, 44100, 16, DitherType.NONE, flacFile);

        AudioReadResult result = FlacFileReader.read(flacFile);

        assertThat(result.sampleRate()).isEqualTo(44100);
        assertThat(result.channels()).isEqualTo(2);
        assertThat(result.bitDepth()).isEqualTo(16);
        assertThat(result.numFrames()).isEqualTo(44100);
        assertThat(result.durationSeconds()).isCloseTo(1.0, offset(0.001));
        assertThat(result.audioData()).hasNumberOfRows(2);
    }

    @Test
    void shouldReadMono16BitFlac() throws IOException {
        float[][] audio = new float[1][1000];
        for (int i = 0; i < 1000; i++) {
            audio[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100));
        }
        Path flacFile = tempDir.resolve("mono16.flac");
        FlacExporter.write(audio, 44100, 16, DitherType.NONE, flacFile);

        AudioReadResult result = FlacFileReader.read(flacFile);

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
        Path flacFile = tempDir.resolve("roundtrip16.flac");
        FlacExporter.write(audio, sampleRate, 16, DitherType.NONE, flacFile);

        AudioReadResult result = FlacFileReader.read(flacFile);

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
        Path flacFile = tempDir.resolve("roundtrip24.flac");
        FlacExporter.write(audio, sampleRate, 24, DitherType.NONE, flacFile);

        AudioReadResult result = FlacFileReader.read(flacFile);

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
    void shouldReadFlacAt48kHz() throws IOException {
        float[][] audio = generateStereoSine(48000, 0.5, 1000.0);
        Path flacFile = tempDir.resolve("48khz.flac");
        FlacExporter.write(audio, 48000, 16, DitherType.NONE, flacFile);

        AudioReadResult result = FlacFileReader.read(flacFile);

        assertThat(result.sampleRate()).isEqualTo(48000);
        assertThat(result.numFrames()).isEqualTo(24000);
    }

    @Test
    void shouldRejectNullPath() {
        assertThatThrownBy(() -> FlacFileReader.read(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNonExistentFile() {
        Path nonExistent = tempDir.resolve("missing.flac");

        assertThatThrownBy(() -> FlacFileReader.read(nonExistent))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void shouldRejectNonFlacFile() throws IOException {
        Path textFile = tempDir.resolve("not_a_flac.flac");
        Files.writeString(textFile, "This is not a FLAC file at all");

        assertThatThrownBy(() -> FlacFileReader.read(textFile))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectTooSmallFile() throws IOException {
        Path tinyFile = tempDir.resolve("tiny.flac");
        Files.write(tinyFile, new byte[10]);

        assertThatThrownBy(() -> FlacFileReader.read(tinyFile))
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
