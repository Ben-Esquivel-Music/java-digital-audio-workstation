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

class OggVorbisExporterTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldWriteValidOggFile() throws IOException {
        float[][] audio = generateStereoSine(44100, 1.0, 440.0);
        Path outputPath = tempDir.resolve("test.ogg");

        OggVorbisExporter.write(audio, 44100, 16, DitherType.NONE,
                AudioMetadata.EMPTY, 0.8, outputPath);

        assertThat(outputPath).exists();
        byte[] data = Files.readAllBytes(outputPath);
        assertThat(data.length).isGreaterThan(0);
        // Verify Ogg magic number: "OggS"
        assertThat(new String(data, 0, 4)).isEqualTo("OggS");
    }

    @Test
    void shouldWriteOggWithMetadata() throws IOException {
        float[][] audio = generateStereoSine(44100, 0.5, 440.0);
        AudioMetadata metadata = new AudioMetadata("Vorbis Song", "Vorbis Artist", "Vorbis Album", null);
        Path outputPath = tempDir.resolve("meta.ogg");

        OggVorbisExporter.write(audio, 44100, 16, DitherType.NONE,
                metadata, 0.8, outputPath);

        assertThat(outputPath).exists();
        byte[] data = Files.readAllBytes(outputPath);
        // Metadata is encoded in Vorbis comments within the Ogg stream
        assertThat(data.length).isGreaterThan(0);
    }

    @Test
    void shouldWriteMonoOgg() throws IOException {
        float[][] audio = generateMonoSine(44100, 0.5, 440.0);
        Path outputPath = tempDir.resolve("mono.ogg");

        OggVorbisExporter.write(audio, 44100, 16, DitherType.NONE,
                AudioMetadata.EMPTY, 0.5, outputPath);

        assertThat(outputPath).exists();
        byte[] data = Files.readAllBytes(outputPath);
        assertThat(new String(data, 0, 4)).isEqualTo("OggS");
    }

    @Test
    void shouldWriteOggAtDifferentSampleRates() throws IOException {
        float[][] audio = generateStereoSine(48000, 0.5, 440.0);
        Path outputPath = tempDir.resolve("48k.ogg");

        OggVorbisExporter.write(audio, 48000, 16, DitherType.NONE,
                AudioMetadata.EMPTY, 0.8, outputPath);

        assertThat(outputPath).exists();
    }

    @Test
    void shouldWriteOggAtDifferentQualities() throws IOException {
        float[][] audio = generateStereoSine(44100, 1.0, 440.0);
        Path lowQuality = tempDir.resolve("low.ogg");
        Path highQuality = tempDir.resolve("high.ogg");

        OggVorbisExporter.write(audio, 44100, 16, DitherType.NONE,
                AudioMetadata.EMPTY, 0.0, lowQuality);
        OggVorbisExporter.write(audio, 44100, 16, DitherType.NONE,
                AudioMetadata.EMPTY, 1.0, highQuality);

        assertThat(lowQuality).exists();
        assertThat(highQuality).exists();
        // Higher quality should produce a larger file
        long lowSize = Files.size(lowQuality);
        long highSize = Files.size(highQuality);
        assertThat(highSize).isGreaterThanOrEqualTo(lowSize);
    }

    @Test
    void shouldRejectNullAudioData() {
        Path outputPath = tempDir.resolve("null.ogg");

        assertThatThrownBy(() -> OggVorbisExporter.write(null, 44100, 16,
                DitherType.NONE, AudioMetadata.EMPTY, 0.8, outputPath))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectEmptyAudioData() {
        Path outputPath = tempDir.resolve("empty.ogg");

        assertThatThrownBy(() -> OggVorbisExporter.write(new float[0][0], 44100, 16,
                DitherType.NONE, AudioMetadata.EMPTY, 0.8, outputPath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");
    }

    @Test
    void shouldRejectNonPositiveSampleRate() {
        float[][] audio = generateStereoSine(44100, 0.1, 440.0);
        Path outputPath = tempDir.resolve("bad.ogg");

        assertThatThrownBy(() -> OggVorbisExporter.write(audio, 0, 16,
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
