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
import java.util.List;

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

    /** Stereo ramp of 16-bit codes: frame {@code n} is code {@code n} left, {@code -n} right. */
    private static float[][] codeRamp(int firstCode, int frames) {
        float[][] audio = new float[2][frames];
        for (int i = 0; i < frames; i++) {
            audio[0][i] = (firstCode + i) / 32767f;
            audio[1][i] = -(firstCode + i) / 32767f;
        }
        return audio;
    }

    @Test
    void aRotatedTakeIsReadBackAsOneArrayWithEverySegmentInItsPlace() throws IOException {
        // A session capped at 400 frames per segment and fed blocks that do
        // not fit twice: every block rotates into a segment of its own. The
        // codes run on across the three seams.
        Path trackDir = tempDir.resolve("rotated");
        RecordingSession session = new RecordingSession(
                new com.benesquivelmusic.daw.core.audio.AudioFormat(SAMPLE_RATE, 2, 16, 256),
                trackDir, Duration.ofHours(1), 400L * 4);
        session.start();
        int fed = 0;
        for (int frames : new int[] {250, 250, 250, 200}) {
            session.recordAudioData(codeRamp(fed, frames), frames);
            fed += frames;
        }
        session.stop();
        assertThat(session.getSegments()).extracting(RecordingSegment::sampleCount)
                .as("fixture: the take rotated three times").containsExactly(250L, 250L, 250L, 200L);
        List<Path> segments = session.getSegments().stream().map(RecordingSegment::filePath).toList();

        float[][] whole = SegmentFile.readFrames(segments);

        assertThat(whole).hasDimensions(2, 950);
        for (int i = 0; i < 950; i++) {
            assertThat(whole[0][i]).as("left frame %d", i).isEqualTo(i / 32768f);
            assertThat(whole[1][i]).as("right frame %d", i).isEqualTo(-i / 32768f);
        }
    }

    @Test
    void sealedSegmentsAndAStreamingPartAreReadTogether() throws IOException {
        SegmentWriter first = open("segment-000.wav.part", 2, 16);
        first.append(codeRamp(0, 20_000), 2, 20_000); // longer than one read chunk
        Path sealed = first.seal();
        SegmentWriter second = open("segment-001.wav.part", 2, 16);
        second.append(codeRamp(20_000, 37), 2, 37);
        try {
            float[][] whole = SegmentFile.readFrames(List.of(sealed, second.partPath()));

            assertThat(whole).hasDimensions(2, 20_037);
            for (int i = 0; i < 20_037; i++) {
                if (whole[0][i] != i / 32768f || whole[1][i] != -i / 32768f) {
                    assertThat(whole[0][i]).as("left frame %d", i).isEqualTo(i / 32768f);
                    assertThat(whole[1][i]).as("right frame %d", i).isEqualTo(-i / 32768f);
                }
            }
            assertThat(SegmentFile.readFrames(List.of(sealed)))
                    .as("a one-segment list reads as the single-file reader does")
                    .isEqualTo(SegmentFile.readFrames(sealed));
        } finally {
            second.abandon();
        }
    }

    @Test
    void segmentsOfDifferentBitDepthsDecodeIntoOneArray() throws IOException {
        SegmentWriter sixteen = open("a.wav.part", 1, 16);
        sixteen.append(new float[][] {{0.25f, -0.25f}}, 1, 2);
        SegmentWriter floats = open("b.wav.part", 1, 32);
        floats.append(new float[][] {{0.1f, 0.2f, 0.3f}}, 1, 3);

        float[][] whole = SegmentFile.readFrames(List.of(sixteen.seal(), floats.seal()));

        assertThat(whole).hasDimensions(1, 5);
        assertThat(whole[0]).containsExactly(0.25f, -0.25f, 0.1f, 0.2f, 0.3f);
    }

    @Test
    void aSegmentWithAnotherChannelCountIsRefusedNamingBothFiles() throws IOException {
        SegmentWriter stereo = open("segment-000.wav.part", 2, 16);
        stereo.append(codeRamp(0, 10), 2, 10);
        Path first = stereo.seal();
        SegmentWriter mono = open("segment-001.wav.part", 1, 16);
        mono.append(codeRamp(0, 10), 1, 10);
        Path second = mono.seal();

        assertThatThrownBy(() -> SegmentFile.readFrames(List.of(first, second)))
                .isInstanceOf(IOException.class)
                .hasMessage(second + " has 1 channel(s) but " + first + " has 2"
                        + ": the segments of one take share a channel count");
    }

    @Test
    void aSegmentWithAnotherSampleRateIsRefusedNamingBothFiles() throws IOException {
        SegmentWriter atSessionRate = open("segment-000.wav.part", 2, 16);
        atSessionRate.append(codeRamp(0, 10), 2, 10);
        Path first = atSessionRate.seal();
        SegmentWriter atAnotherRate = SegmentWriter.open(tempDir.resolve("segment-001.wav.part"), 44_100.0, 2, 16,
                Duration.ofSeconds(5), () -> 0L);
        atAnotherRate.append(codeRamp(0, 10), 2, 10);
        Path second = atAnotherRate.seal();

        assertThatThrownBy(() -> SegmentFile.readFrames(List.of(first, second)))
                .isInstanceOf(IOException.class)
                .hasMessage(second + " has a sample rate of 44100 Hz but " + first + " has 48000 Hz"
                        + ": the segments of one take share a sample rate");
    }

    @Test
    void anEmptySegmentListIsRefusedBecauseNothingGivesTheResultAShape() {
        assertThatThrownBy(() -> SegmentFile.readFrames(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("segments must not be empty");
        assertThatThrownBy(() -> SegmentFile.readFrames((List<Path>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("segments");
    }

    @Test
    void aMissingSegmentFailsTheWholeReadInsteadOfLeavingAHole() throws IOException {
        SegmentWriter writer = open("segment-000.wav.part", 2, 16);
        writer.append(codeRamp(0, 10), 2, 10);
        Path first = writer.seal();
        Path missing = tempDir.resolve("segment-001.wav");

        assertThatThrownBy(() -> SegmentFile.readFrames(List.of(first, missing)))
                .isInstanceOf(java.nio.file.NoSuchFileException.class)
                .hasMessageContaining("segment-001.wav");
    }
}
