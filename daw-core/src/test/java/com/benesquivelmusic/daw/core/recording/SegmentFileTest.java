package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.export.WavExporter;
import com.benesquivelmusic.daw.sdk.export.AudioMetadata;
import com.benesquivelmusic.daw.sdk.export.DitherType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link SegmentFile} — the reader side of the §3.4 segment grammar
 * (story 323), the one a recovery scan (story 332) will use.
 */
class SegmentFileTest {

    private static final double SAMPLE_RATE = 48_000.0;

    @TempDir
    Path tempDir;

    private SegmentWriter open(String name, int channels, int bitDepth) throws IOException {
        return SegmentWriter.open(tempDir.resolve(name), SAMPLE_RATE, channels, bitDepth,
                Duration.ofSeconds(5), () -> 0L);
    }

    private static float[][] sine(int channels, int frames) {
        float[][] audio = new float[channels][frames];
        for (int ch = 0; ch < channels; ch++) {
            for (int i = 0; i < frames; i++) {
                audio[ch][i] = (float) Math.sin(2 * Math.PI * (220.0 * (ch + 1)) * i / SAMPLE_RATE) * 0.7f;
            }
        }
        return audio;
    }

    @Test
    void describeAndReadAgreeBetweenStreamingPartAndSealedWav() throws IOException {
        float[][] audio = sine(2, 500);
        SegmentWriter writer = open("segment-000.wav.part", 2, 24);
        writer.append(audio, 2, 500);
        Path part = writer.partPath();

        SegmentFile.Description streaming = SegmentFile.describe(part);
        assertThat(streaming.sealed()).isFalse();
        assertThat(streaming.frameCount()).isEqualTo(500);
        assertThat(streaming.sampleRate()).isEqualTo(48_000.0);
        assertThat(streaming.channels()).isEqualTo(2);
        assertThat(streaming.bitDepth()).isEqualTo(24);
        assertThat(streaming.bytesPerFrame()).isEqualTo(6);
        assertThat(SegmentFile.isStreamingName(part)).isTrue();
        float[][] fromPart = SegmentFile.readFrames(part);

        Path wav = writer.seal();

        SegmentFile.Description sealed = SegmentFile.describe(wav);
        assertThat(sealed.sealed()).isTrue();
        assertThat(sealed.frameCount()).as("data-size field agrees with the length-derived count")
                .isEqualTo(streaming.frameCount());
        assertThat(sealed).isEqualTo(new SegmentFile.Description(48_000.0, 2, 24, 500, true));
        assertThat(SegmentFile.isStreamingName(wav)).isFalse();
        float[][] fromWav = SegmentFile.readFrames(wav);

        assertThat(fromWav).isEqualTo(fromPart);
        assertThat(fromWav).hasDimensions(2, 500);
        for (int ch = 0; ch < 2; ch++) {
            for (int i = 0; i < 500; i++) {
                assertThat(fromWav[ch][i]).as("ch " + ch + " frame " + i)
                        .isCloseTo(audio[ch][i], within(2f / 8_388_608f));
            }
        }
    }

    @Test
    void truncatedPartIsFlooredToWholeFrames() throws IOException {
        float[][] audio = sine(2, 10);
        SegmentWriter writer = open("segment-000.wav.part", 2, 16);
        writer.append(audio, 2, 10);
        writer.abandon();
        Path part = writer.partPath();
        float[][] intact = SegmentFile.readFrames(part);
        assertThat(intact[0]).hasSize(10);

        // A crash mid-write leaves a partial trailing frame: 4-byte frames, cut 3 bytes.
        try (FileChannel fc = FileChannel.open(part, StandardOpenOption.WRITE)) {
            fc.truncate(fc.size() - 3);
        }

        SegmentFile.Description description = SegmentFile.describe(part);
        assertThat(description.frameCount()).isEqualTo(9);
        float[][] recovered = SegmentFile.readFrames(part);
        assertThat(recovered).hasDimensions(2, 9);
        for (int ch = 0; ch < 2; ch++) {
            for (int i = 0; i < 9; i++) {
                assertThat(recovered[ch][i]).isEqualTo(intact[ch][i]);
            }
        }
    }

    @Test
    void fileShorterThanTheHeaderIsRejectedWithAClearMessage() throws IOException {
        Path stub = tempDir.resolve("segment-000.wav.part");
        Files.write(stub, new byte[20]);

        assertThatThrownBy(() -> SegmentFile.describe(stub))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("20 bytes")
                .hasMessageContaining("44");
        assertThatThrownBy(() -> SegmentFile.readFrames(stub))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("44");

        Path empty = tempDir.resolve("empty.wav");
        Files.write(empty, new byte[0]);
        assertThatThrownBy(() -> SegmentFile.describe(empty))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("0 bytes");
    }

    @Test
    void sealedNameWithProvisionalSentinelIsRejected() throws IOException {
        SegmentWriter writer = open("segment-000.wav.part", 1, 16);
        writer.append(sine(1, 8), 1, 8);
        writer.abandon();
        // Simulate a half-seal: the rename happened without the patch — the grammar forbids it.
        Path fake = tempDir.resolve("segment-000.wav");
        Files.move(writer.partPath(), fake);

        assertThatThrownBy(() -> SegmentFile.describe(fake))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("provisional");
    }

    @Test
    void sealedDataSizeBeyondTheFileIsRejected() throws IOException {
        SegmentWriter writer = open("segment-000.wav.part", 1, 16);
        writer.append(sine(1, 8), 1, 8);
        Path wav = writer.seal();
        try (FileChannel fc = FileChannel.open(wav, StandardOpenOption.WRITE)) {
            fc.truncate(fc.size() - 2);
        }

        assertThatThrownBy(() -> SegmentFile.describe(wav))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("bytes long");
    }

    @Test
    void nonWavBytesAreRejected() throws IOException {
        Path junk = tempDir.resolve("junk.wav.part");
        Files.write(junk, new byte[64]);

        assertThatThrownBy(() -> SegmentFile.describe(junk))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("RIFF");
    }

    @Test
    void readsWavExporterOutputAtTheHouseDecodeScale() throws IOException {
        float[][] audio = sine(2, 300);
        Path exported = tempDir.resolve("export.wav");
        WavExporter.write(audio, 44_100, 16, DitherType.NONE, AudioMetadata.EMPTY, exported);

        SegmentFile.Description description = SegmentFile.describe(exported);
        assertThat(description).isEqualTo(new SegmentFile.Description(44_100.0, 2, 16, 300, true));
        float[][] back = SegmentFile.readFrames(exported);
        for (int ch = 0; ch < 2; ch++) {
            for (int i = 0; i < 300; i++) {
                assertThat(back[ch][i]).isCloseTo(audio[ch][i], within(1e-4f));
            }
        }
    }

    @Test
    void thirtyTwoBitFloatRoundTripsExactly() throws IOException {
        float[][] audio = sine(2, 64);
        audio[0][0] = 1f;
        audio[1][0] = -1f;
        audio[0][1] = Float.MIN_NORMAL;
        SegmentWriter writer = open("f.wav.part", 2, 32);
        writer.append(audio, 2, 64);
        Path wav = writer.seal();

        assertThat(SegmentFile.readFrames(wav)).isEqualTo(audio);
    }
}
