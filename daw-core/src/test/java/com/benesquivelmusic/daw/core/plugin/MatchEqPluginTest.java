package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.reference.ReferenceTrack;
import com.benesquivelmusic.daw.sdk.export.AudioMetadata;
import com.benesquivelmusic.daw.sdk.export.DitherType;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.core.export.WavExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link MatchEqPlugin}, focused on the one-off reference-file
 * loader that drives the UI's "Load Reference…" button.
 */
class MatchEqPluginTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadReferenceFileAndPopulateReferenceSpectrum() throws IOException {
        MatchEqPlugin plugin = new MatchEqPlugin();
        plugin.initialize(stubContext());

        // Generate a short 440 Hz sine tone and write it as a 16-bit WAV file.
        int sampleRate = 48_000;
        int numFrames = 8_192;
        float[][] audio = new float[2][numFrames];
        for (int i = 0; i < numFrames; i++) {
            float sample = (float) (0.25 * Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate));
            audio[0][i] = sample;
            audio[1][i] = sample;
        }
        Path wav = tempDir.resolve("reference.wav");
        WavExporter.write(audio, sampleRate, 16, DitherType.NONE, AudioMetadata.EMPTY, wav);

        ReferenceTrack track = plugin.loadReferenceFile(wav);

        assertThat(track).isNotNull();
        assertThat(track.getAudioData()).isNotNull();
        assertThat(track.getAudioData()[0].length).isEqualTo(numFrames);
        assertThat(plugin.getProcessor().getReferenceSpectrum()).isNotNull();
    }

    @Test
    void shouldResampleReferenceFileWhenSampleRatesDiffer() throws IOException {
        MatchEqPlugin plugin = new MatchEqPlugin();
        plugin.initialize(stubContext()); // processor sample rate = 48 000

        // Reference file at 44.1 kHz — must be resampled to 48 kHz to keep
        // FFT bins aligned with the processor's frequency grid.
        int sourceRate = 44_100;
        int numFrames = 22_050; // 0.5 s
        float[][] audio = new float[2][numFrames];
        for (int i = 0; i < numFrames; i++) {
            float sample = (float) (0.25 * Math.sin(2.0 * Math.PI * 440.0 * i / sourceRate));
            audio[0][i] = sample;
            audio[1][i] = sample;
        }
        Path wav = tempDir.resolve("reference-44100.wav");
        WavExporter.write(audio, sourceRate, 16, DitherType.NONE, AudioMetadata.EMPTY, wav);

        ReferenceTrack track = plugin.loadReferenceFile(wav);

        // Audio inside the track was resampled to 48 kHz: the new frame count
        // should be approximately numFrames * 48000/44100 (allow a few-frame
        // edge tolerance from the windowed-sinc converter).
        int expected = (int) Math.round(numFrames * 48_000.0 / sourceRate);
        assertThat(track.getAudioData()[0].length)
                .isCloseTo(expected, org.assertj.core.data.Offset.offset(8));
        assertThat(plugin.getProcessor().getReferenceSpectrum()).isNotNull();
    }

    @Test
    void shouldFailWhenPluginNotInitialized() {
        MatchEqPlugin plugin = new MatchEqPlugin();
        assertThatThrownBy(() -> plugin.loadReferenceFile(tempDir.resolve("x.wav")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not been initialized");
    }

    @Test
    void shouldRejectUnsupportedFileExtension() throws IOException {
        MatchEqPlugin plugin = new MatchEqPlugin();
        plugin.initialize(stubContext());
        Path unsupported = tempDir.resolve("ref.xyz");
        java.nio.file.Files.writeString(unsupported, "not audio");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> plugin.loadReferenceFile(unsupported))
                .withMessageContaining("Unsupported");
    }

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return 48_000; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) { }
        };
    }
}
