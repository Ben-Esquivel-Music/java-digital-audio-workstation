package com.benesquivelmusic.daw.core.dsp.regression;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trip tests for {@link WavFile}: encoding-and-decoding a buffer of
 * floats must produce the same buffer to within the 16-bit PCM quantisation
 * step (≈ 1/32767 ≈ 3 × 10⁻⁵). Also exercises the multi-channel layout
 * and rejection of unsupported formats.
 */
class WavFileTest {

    @Test
    void monoRoundTripPreservesSamplesWithin16BitQuantisation() throws Exception {
        float[] mono = new float[256];
        for (int i = 0; i < mono.length; i++) {
            mono[i] = (float) Math.sin(2.0 * Math.PI * i / 64.0) * 0.5f;
        }
        byte[] bytes = WavFile.toByteArray(new float[][]{ mono }, 44_100);
        WavFile.Audio decoded = WavFile.read(new java.io.ByteArrayInputStream(bytes));

        assertThat(decoded.channels()).isEqualTo(1);
        assertThat(decoded.frames()).isEqualTo(mono.length);
        assertThat(decoded.sampleRate()).isEqualTo(44_100);
        for (int i = 0; i < mono.length; i++) {
            // 1 LSB at int16 = 1/32767 — pick a slightly looser tolerance for safety.
            assertThat(decoded.samples()[0][i])
                    .as("sample %d", i)
                    .isCloseTo(mono[i], org.assertj.core.data.Offset.offset(1.0f / 32000.0f));
        }
    }

    @Test
    void stereoRoundTripInterleavesChannelsCorrectly(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        float[] left  = { 1.0f, -1.0f, 0.5f, -0.5f, 0.0f };
        float[] right = { 0.25f, 0.75f, -0.25f, -0.75f, 0.1f };
        Path file = tmp.resolve("stereo.wav");
        WavFile.write(file, new float[][]{ left, right }, 48_000);
        assertThat(Files.size(file)).isGreaterThan(44L);

        WavFile.Audio decoded = WavFile.read(file);
        assertThat(decoded.channels()).isEqualTo(2);
        assertThat(decoded.sampleRate()).isEqualTo(48_000);
        assertThat(decoded.samples()[0])
                .containsExactly(1.0f, -1.0f,
                        Math.round(0.5f * 32767) / 32767.0f,
                        Math.round(-0.5f * 32767) / 32767.0f,
                        0.0f);
        assertThat(decoded.samples()[1][0])
                .isCloseTo(0.25f, org.assertj.core.data.Offset.offset(1.0f / 32000.0f));
    }

    @Test
    void clipsOutOfRangeSamplesToInt16FullScale(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        float[] mono = { 2.0f, -2.0f, 0.0f };
        Path file = tmp.resolve("clip.wav");
        WavFile.write(file, new float[][]{ mono }, 44_100);

        WavFile.Audio decoded = WavFile.read(file);
        assertThat(decoded.samples()[0][0]).isEqualTo( 1.0f);
        assertThat(decoded.samples()[0][1]).isEqualTo(-1.0f);
        assertThat(decoded.samples()[0][2]).isEqualTo( 0.0f);
    }

    @Test
    void rejectsNonWavInput() {
        byte[] junk = "not a wav file at all, really".getBytes();
        assertThatThrownBy(() -> WavFile.read(new java.io.ByteArrayInputStream(junk)))
                .isInstanceOf(java.io.IOException.class);
    }
}
