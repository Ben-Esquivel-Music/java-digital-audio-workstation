package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.export.WavExporter;
import com.benesquivelmusic.daw.sdk.export.AudioMetadata;
import com.benesquivelmusic.daw.sdk.export.DitherType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link SegmentWriter} — the streaming WAV segment grammar of story 323
 * (book §3.4, §4.4, §5.3; context D7).
 */
class SegmentWriterTest {

    private static final double SAMPLE_RATE = 48_000.0;
    private static final int CHANNELS = 2;
    private static final Duration CADENCE = Duration.ofSeconds(5);
    private static final long SECOND = 1_000_000_000L;

    @TempDir
    Path tempDir;

    private final AtomicLong clock = new AtomicLong();

    private SegmentWriter open(String name, int bitDepth) throws IOException {
        return SegmentWriter.open(tempDir.resolve(name), SAMPLE_RATE, CHANNELS, bitDepth,
                CADENCE, clock::get);
    }

    /** A deterministic stereo signal with clipped extremes and exact values at the ends. */
    private static float[][] signal(int frames) {
        float[][] audio = new float[CHANNELS][frames];
        for (int i = 0; i < frames; i++) {
            audio[0][i] = (float) Math.sin(2 * Math.PI * 440.0 * i / SAMPLE_RATE) * 0.8f;
            audio[1][i] = (float) Math.cos(2 * Math.PI * 1000.0 * i / SAMPLE_RATE) * 0.3f;
        }
        if (frames >= 6) {
            audio[0][0] = 1.0f;
            audio[1][0] = -1.0f;
            audio[0][1] = 1.5f;   // clipped
            audio[1][1] = -2.0f;  // clipped
            audio[0][2] = 1e-6f;
            audio[1][2] = -1e-6f;
            audio[0][3] = 0.5f;
            audio[1][3] = -0.5f;
        }
        return audio;
    }

    private static String ascii(byte[] bytes, int offset) {
        return new String(bytes, offset, 4, StandardCharsets.US_ASCII);
    }

    private static void assertCanonicalHeader(byte[] bytes, int bitDepth, int formatCode,
                                              int riffSize, int dataSize) {
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int bytesPerSample = bitDepth / 8;
        assertThat(ascii(bytes, 0)).isEqualTo("RIFF");
        assertThat(buf.getInt(4)).as("RIFF size").isEqualTo(riffSize);
        assertThat(ascii(bytes, 8)).isEqualTo("WAVE");
        assertThat(ascii(bytes, 12)).isEqualTo("fmt ");
        assertThat(buf.getInt(16)).as("fmt chunk size").isEqualTo(16);
        assertThat(buf.getShort(20)).as("format code").isEqualTo((short) formatCode);
        assertThat(buf.getShort(22)).as("channels").isEqualTo((short) CHANNELS);
        assertThat(buf.getInt(24)).as("sample rate").isEqualTo(48_000);
        assertThat(buf.getInt(28)).as("byte rate").isEqualTo(48_000 * CHANNELS * bytesPerSample);
        assertThat(buf.getShort(32)).as("block align").isEqualTo((short) (CHANNELS * bytesPerSample));
        assertThat(buf.getShort(34)).as("bits per sample").isEqualTo((short) bitDepth);
        assertThat(ascii(bytes, 36)).isEqualTo("data");
        assertThat(buf.getInt(40)).as("data size").isEqualTo(dataSize);
    }

    @Test
    void streamingPartCarriesTheProvisionalCanonicalHeader() throws IOException {
        int[][] depthAndFormat = {{16, 1}, {24, 1}, {32, 3}};
        for (int[] pair : depthAndFormat) {
            int bitDepth = pair[0];
            String name = "segment-" + bitDepth + ".wav.part";
            try (SegmentWriter writer = open(name, bitDepth)) {
                Path part = tempDir.resolve(name);
                assertThat(part).exists();
                assertThat(Files.size(part)).as("header only after open").isEqualTo(SegmentWriter.DATA_OFFSET);
                assertCanonicalHeader(Files.readAllBytes(part), bitDepth, pair[1],
                        SegmentWriter.PROVISIONAL_SIZE, SegmentWriter.PROVISIONAL_SIZE);

                writer.append(signal(10), CHANNELS, 10);

                assertThat(Files.size(part)).isEqualTo(SegmentWriter.DATA_OFFSET + 10L * writer.bytesPerFrame());
                assertCanonicalHeader(Files.readAllBytes(part), bitDepth, pair[1],
                        SegmentWriter.PROVISIONAL_SIZE, SegmentWriter.PROVISIONAL_SIZE);
                assertThat(writer.frameCount()).isEqualTo(10);
                assertThat(writer.dataBytes()).isEqualTo(10L * writer.bytesPerFrame());
                assertThat(writer.isSealed()).isFalse();
                assertThat(writer.isStreaming()).isTrue();
            }
        }
    }

    @Test
    void sealPatchesExactSizesAndRenamesTheFile() throws IOException {
        SegmentWriter writer = open("segment-000.wav.part", 24);
        Path part = writer.partPath();
        Path wav = writer.sealedPath();
        assertThat(wav).isEqualTo(tempDir.resolve("segment-000.wav"));
        writer.append(signal(300), CHANNELS, 300);
        writer.append(signal(200), CHANNELS, 200);
        long dataBytes = writer.dataBytes();
        assertThat(dataBytes).isEqualTo(500L * CHANNELS * 3);

        Path sealed = writer.seal();

        assertThat(sealed).isEqualTo(wav);
        assertThat(part).doesNotExist();
        assertThat(wav).exists();
        assertThat(writer.isSealed()).isTrue();
        assertThat(writer.isStreaming()).isFalse();
        assertThat(Files.size(wav)).isEqualTo(SegmentWriter.DATA_OFFSET + dataBytes);
        assertCanonicalHeader(Files.readAllBytes(wav), 24, 1, (int) (36 + dataBytes), (int) dataBytes);
        assertThat(SegmentFile.describe(wav).frameCount()).isEqualTo(500);
    }

    @Test
    void sealedSegmentIsByteIdenticalToWavExporterOutput() throws IOException {
        float[][] audio = signal(1_000);
        for (int bitDepth : new int[] {16, 24, 32}) {
            Path exported = tempDir.resolve("export-" + bitDepth + ".wav");
            WavExporter.write(audio, (int) SAMPLE_RATE, bitDepth, DitherType.NONE,
                    AudioMetadata.EMPTY, exported);

            SegmentWriter writer = open("captured-" + bitDepth + ".wav.part", bitDepth);
            writer.append(audio, CHANNELS, 300);
            float[][] tail = new float[CHANNELS][700];
            for (int ch = 0; ch < CHANNELS; ch++) {
                System.arraycopy(audio[ch], 300, tail[ch], 0, 700);
            }
            writer.append(tail, CHANNELS, 700);
            Path sealed = writer.seal();

            byte[] exportBytes = Files.readAllBytes(exported);
            byte[] segmentBytes = Files.readAllBytes(sealed);
            assertThat(segmentBytes.length).as(bitDepth + "-bit length").isEqualTo(exportBytes.length);
            assertThat(java.util.Arrays.copyOfRange(segmentBytes, SegmentWriter.DATA_OFFSET, segmentBytes.length))
                    .as(bitDepth + "-bit data chunk")
                    .isEqualTo(java.util.Arrays.copyOfRange(exportBytes, SegmentWriter.DATA_OFFSET, exportBytes.length));
            assertThat(segmentBytes).as(bitDepth + "-bit whole file").isEqualTo(exportBytes);
        }
    }

    @Test
    void abandonLeavesARecoverablePartWithTheSentinel() throws IOException {
        float[][] audio = signal(100);
        SegmentWriter writer = open("segment-000.wav.part", 32);
        writer.append(audio, CHANNELS, 100);

        writer.abandon();

        assertThat(writer.partPath()).exists();
        assertThat(writer.sealedPath()).doesNotExist();
        assertThat(writer.isSealed()).isFalse();
        assertThat(writer.isStreaming()).isFalse();
        assertCanonicalHeader(Files.readAllBytes(writer.partPath()), 32, 3,
                SegmentWriter.PROVISIONAL_SIZE, SegmentWriter.PROVISIONAL_SIZE);

        SegmentFile.Description description = SegmentFile.describe(writer.partPath());
        assertThat(description.frameCount()).isEqualTo(100);
        assertThat(description.sealed()).isFalse();
        float[][] recovered = SegmentFile.readFrames(writer.partPath());
        for (int ch = 0; ch < CHANNELS; ch++) {
            for (int i = 0; i < 100; i++) {
                float expected = Math.max(-1f, Math.min(1f, audio[ch][i]));
                assertThat(recovered[ch][i]).as("ch " + ch + " frame " + i).isEqualTo(expected);
            }
        }
        assertThatThrownBy(() -> writer.append(audio, CHANNELS, 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(writer::seal).isInstanceOf(IllegalStateException.class);
        writer.abandon(); // idempotent
    }

    @Test
    void sealingTwiceThrows() throws IOException {
        SegmentWriter writer = open("segment-000.wav.part", 16);
        writer.append(signal(10), CHANNELS, 10);
        writer.seal();

        assertThatThrownBy(writer::seal)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sealed");
        assertThatThrownBy(() -> writer.append(signal(1), CHANNELS, 1))
                .isInstanceOf(IllegalStateException.class);
        writer.close(); // no-op once sealed
        assertThat(writer.sealedPath()).exists();
    }

    @Test
    void sealingOntoAnExistingWavThrowsAndLeavesBothFiles() throws IOException {
        SegmentWriter writer = open("segment-000.wav.part", 16);
        writer.append(signal(10), CHANNELS, 10);
        byte[] foreign = "not ours".getBytes(StandardCharsets.US_ASCII);
        Files.write(writer.sealedPath(), foreign);

        assertThatThrownBy(writer::seal).isInstanceOf(FileAlreadyExistsException.class);

        assertThat(writer.partPath()).exists();
        assertThat(Files.readAllBytes(writer.sealedPath())).isEqualTo(foreign);
        assertThat(writer.isSealed()).isFalse();
        assertThat(writer.isStreaming()).as("the writer is still usable").isTrue();
        assertCanonicalHeader(Files.readAllBytes(writer.partPath()), 16, 1,
                SegmentWriter.PROVISIONAL_SIZE, SegmentWriter.PROVISIONAL_SIZE);
        writer.abandon();
    }

    @Test
    void openRefusesAnExistingPartOrSealedFile() throws IOException {
        Files.writeString(tempDir.resolve("a.wav.part"), "x");
        assertThatThrownBy(() -> open("a.wav.part", 16)).isInstanceOf(FileAlreadyExistsException.class);

        Files.writeString(tempDir.resolve("b.wav"), "x");
        assertThatThrownBy(() -> open("b.wav.part", 16)).isInstanceOf(FileAlreadyExistsException.class);
        assertThat(tempDir.resolve("b.wav.part")).doesNotExist();
    }

    @Test
    void forceCadenceForcesOnlyAtOrAfterTheCadence() throws IOException {
        clock.set(0);
        SegmentWriter writer = open("segment-000.wav.part", 32);
        long blockBytes = 10L * writer.bytesPerFrame();
        assertThat(writer.lastForceNanos()).as("the cadence starts at open").isZero();

        clock.set(1 * SECOND);
        writer.append(signal(10), CHANNELS, 10);
        assertThat(writer.forceCount()).isZero();
        assertThat(writer.bytesSinceForce()).isEqualTo(blockBytes);

        clock.set(5 * SECOND - 1);
        writer.append(signal(10), CHANNELS, 10);
        assertThat(writer.forceCount()).as("one nanosecond short of the cadence").isZero();
        assertThat(writer.bytesSinceForce()).isEqualTo(2 * blockBytes);

        clock.set(5 * SECOND);
        writer.append(signal(10), CHANNELS, 10);
        assertThat(writer.forceCount()).as("the first append at the cadence forces").isEqualTo(1);
        assertThat(writer.bytesSinceForce()).isZero();
        assertThat(writer.lastForceNanos()).isEqualTo(5 * SECOND);

        clock.set(9 * SECOND);
        writer.append(signal(10), CHANNELS, 10);
        assertThat(writer.forceCount()).isEqualTo(1);
        assertThat(writer.bytesSinceForce()).isEqualTo(blockBytes);

        clock.set(17 * SECOND); // well past: still exactly one force per append
        writer.append(signal(10), CHANNELS, 10);
        assertThat(writer.forceCount()).isEqualTo(2);
        assertThat(writer.lastForceNanos()).isEqualTo(17 * SECOND);
        assertThat(writer.bytesSinceForce()).isZero();

        writer.seal();
        assertThat(writer.forceCount()).as("the seal's force(true) calls are not cadence forces").isEqualTo(2);
        assertThat(writer.bytesSinceForce()).isZero();
    }

    @Test
    void forceCountAfterNCadenceBoundariesEqualsN() throws IOException {
        clock.set(0);
        SegmentWriter writer = open("segment-000.wav.part", 16);
        int boundaries = 7;
        for (int n = 1; n <= boundaries; n++) {
            clock.set(n * 5 * SECOND);
            writer.append(signal(4), CHANNELS, 4);
            assertThat(writer.forceCount()).isEqualTo(n);
        }
        writer.seal();
        assertThat(writer.forceCount()).isEqualTo(boundaries);
    }

    @Test
    void forceIfDueForcesUnforcedBytesOnceTheCadenceHasElapsedWithoutAnAppend() throws IOException {
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        clock.set(0);
        SegmentWriter writer = SegmentWriter.open(tempDir.resolve("segment-000.wav.part"), SAMPLE_RATE,
                CHANNELS, 16, CADENCE, clock::get, journal.opener(SegmentWriter.CREATE_NEW_CHANNEL));
        long blockBytes = 10L * writer.bytesPerFrame();

        clock.set(60 * SECOND);
        assertThat(writer.forceIfDue()).as("nothing appended yet: nothing to force").isFalse();
        assertThat(journal.forces(false)).isZero();

        clock.set(61 * SECOND);
        writer.append(signal(10), CHANNELS, 10); // the append's own check: 61 s since the open
        assertThat(journal.forces(false)).as("fixture: the append forced").isEqualTo(1);
        clock.set(62 * SECOND);
        writer.append(signal(10), CHANNELS, 10);
        assertThat(writer.bytesSinceForce()).isEqualTo(blockBytes);

        clock.set(66 * SECOND - 1);
        assertThat(writer.forceIfDue()).as("one nanosecond short of the cadence").isFalse();
        assertThat(journal.forces(false)).isEqualTo(1);

        clock.set(66 * SECOND);
        assertThat(writer.forceIfDue()).as("the cadence has elapsed since the last force").isTrue();
        assertThat(journal.forces(false)).as("force(false) reached the channel with no append").isEqualTo(2);
        assertThat(writer.forceCount()).isEqualTo(2);
        assertThat(writer.bytesSinceForce()).isZero();
        assertThat(writer.lastForceNanos()).isEqualTo(66 * SECOND);
        assertThat(writer.frameCount()).isEqualTo(20);

        clock.set(200 * SECOND);
        assertThat(writer.forceIfDue()).as("nothing un-forced: not forced again").isFalse();
        writer.append(signal(10), CHANNELS, 0);
        assertThat(journal.forces(false)).as("nor by an append of no frames").isEqualTo(2);
        assertThat(writer.lastForceNanos()).isEqualTo(66 * SECOND);

        writer.seal();
        assertThat(journal.forces(false)).as("the seal adds no cadence force").isEqualTo(2);
        assertThatThrownBy(writer::forceIfDue).as("a sealed writer is refused, like an append")
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("forceIfDue");
    }

    @Test
    void aForceIfDueThatFailsLeavesTheWriterStreamingWithItsCountersUntouched() throws IOException {
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        clock.set(0);
        SegmentWriter writer = SegmentWriter.open(tempDir.resolve("segment-000.wav.part"), SAMPLE_RATE,
                CHANNELS, 16, CADENCE, clock::get, journal.opener(SegmentWriter.CREATE_NEW_CHANNEL));
        clock.set(1 * SECOND);
        writer.append(signal(10), CHANNELS, 10);
        long unforced = writer.bytesSinceForce();

        journal.failNextForces(1);
        clock.set(5 * SECOND);
        assertThatThrownBy(writer::forceIfDue).isInstanceOf(IOException.class);

        assertThat(writer.isStreaming()).isTrue();
        assertThat(writer.bytesSinceForce()).isEqualTo(unforced);
        assertThat(writer.forceCount()).isZero();
        assertThat(writer.lastForceNanos()).isZero();
        assertThat(writer.forceIfDue()).as("the next check forces what is still due").isTrue();
        assertThat(journal.forces(false)).isEqualTo(1);
        writer.seal();
    }

    @Test
    void forceIfDueOnAnAbandonedWriterIsRefused() throws IOException {
        SegmentWriter writer = open("segment-000.wav.part", 16);
        writer.append(signal(10), CHANNELS, 10);
        writer.abandon();
        clock.set(60 * SECOND);
        assertThatThrownBy(writer::forceIfDue).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("abandoned");
    }

    @Test
    void zeroCadenceForcesAfterEveryAppend() throws IOException {
        SegmentWriter writer = SegmentWriter.open(tempDir.resolve("z.wav.part"), SAMPLE_RATE,
                CHANNELS, 16, Duration.ZERO, clock::get);
        for (int i = 1; i <= 3; i++) {
            writer.append(signal(4), CHANNELS, 4);
            assertThat(writer.forceCount()).isEqualTo(i);
            assertThat(writer.bytesSinceForce()).isZero();
        }
        writer.seal();
    }

    @Test
    void unsupportedBitDepthIsRejectedAtOpen() {
        for (int bitDepth : new int[] {8, 12, 20, 64, 0, -16}) {
            assertThatThrownBy(() -> open("bad-" + bitDepth + ".wav.part", bitDepth))
                    .as("bit depth " + bitDepth)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("16, 24 or 32");
            assertThat(tempDir.resolve("bad-" + bitDepth + ".wav.part")).doesNotExist();
        }
        assertThatThrownBy(() -> SegmentWriter.open(tempDir.resolve("x.wav.part"), 0, 2, 16, CADENCE, clock::get))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SegmentWriter.open(tempDir.resolve("x.wav.part"), SAMPLE_RATE, 0, 16, CADENCE, clock::get))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SegmentWriter.open(tempDir.resolve("x.wav.part"), SAMPLE_RATE, 2, 16,
                Duration.ofSeconds(-1), clock::get))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void partNameMustEndWithPartSuffix() {
        assertThatThrownBy(() -> open("segment-000.wav", 16))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(".part");
        assertThatThrownBy(() -> SegmentWriter.sealedPathFor(Path.of(".part")))
                .isInstanceOf(IllegalArgumentException.class);
        Path sealed = tempDir.resolve("track").resolve("segment-003.wav");
        assertThat(SegmentWriter.sealedPathFor(SegmentWriter.partPathFor(sealed))).isEqualTo(sealed);
    }

    @Test
    void appendUsesTheFirstChannelsOfAWiderBlockAndRejectsANarrowerOne() throws IOException {
        SegmentWriter writer = open("segment-000.wav.part", 32);
        float[][] wide = new float[4][8];
        for (int ch = 0; ch < 4; ch++) {
            java.util.Arrays.fill(wide[ch], 0.1f * (ch + 1));
        }
        writer.append(wide, 4, 8);
        float[][] narrow = new float[1][8];
        assertThatThrownBy(() -> writer.append(narrow, 1, 8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");
        assertThatThrownBy(() -> writer.append(wide, 4, 9))
                .as("a row shorter than numFrames")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> writer.append(wide, 5, 8))
                .as("channels beyond the block's rows")
                .isInstanceOf(IllegalArgumentException.class);
        writer.append(wide, 4, 0); // zero frames is a legal no-op
        writer.seal();

        float[][] frames = SegmentFile.readFrames(writer.sealedPath());
        assertThat(frames).hasDimensions(2, 8);
        assertThat(frames[0]).containsOnly(0.1f);
        assertThat(frames[1]).containsOnly(0.2f);
    }

    @Test
    void closeWithoutSealLeavesTheStreamingPart() throws IOException {
        Path part = tempDir.resolve("segment-000.wav.part");
        try (SegmentWriter writer = SegmentWriter.open(part, SAMPLE_RATE, CHANNELS, 16, CADENCE, clock::get)) {
            writer.append(signal(5), CHANNELS, 5);
        }
        assertThat(part).exists();
        assertThat(SegmentWriter.sealedPathFor(part)).doesNotExist();
        assertThat(SegmentFile.describe(part).frameCount()).isEqualTo(5);
    }

    @Test
    void appendsAcrossTheInternalChunkBoundary() throws IOException {
        int frames = 8192 * 2 + 17;
        float[][] audio = new float[CHANNELS][frames];
        for (int i = 0; i < frames; i++) {
            audio[0][i] = (i % 1000) / 1000f;
            audio[1][i] = -(i % 500) / 500f;
        }
        SegmentWriter writer = open("big.wav.part", 16);
        writer.append(audio, CHANNELS, frames);
        writer.seal();

        float[][] back = SegmentFile.readFrames(writer.sealedPath());
        assertThat(back[0]).hasSize(frames);
        for (int i = 0; i < frames; i += 997) {
            assertThat(back[0][i]).isCloseTo(audio[0][i], within(1e-4f));
            assertThat(back[1][i]).isCloseTo(audio[1][i], within(1e-4f));
        }
    }

    @Test
    void appendWithFrameOffsetWritesOnlyTheRequestedRegion() throws IOException {
        float[][] audio = signal(100);
        SegmentWriter offsetWriter = open("offset.wav.part", 16);
        SegmentWriter plainWriter = open("plain.wav.part", 16);

        offsetWriter.append(audio, CHANNELS, 30, 50);
        float[][] region = new float[CHANNELS][50];
        System.arraycopy(audio[0], 30, region[0], 0, 50);
        System.arraycopy(audio[1], 30, region[1], 0, 50);
        plainWriter.append(region, CHANNELS, 50);
        offsetWriter.seal();
        plainWriter.seal();

        assertThat(offsetWriter.frameCount()).isEqualTo(50);
        assertThat(Files.readAllBytes(offsetWriter.sealedPath()))
                .isEqualTo(Files.readAllBytes(plainWriter.sealedPath()));
        assertThatThrownBy(() -> open("short.wav.part", 16).append(audio, CHANNELS, 60, 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fewer than 110");
        assertThatThrownBy(() -> open("negative.wav.part", 16).append(audio, CHANNELS, -1, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("frameOffset");
    }

    @Test
    void failNextAppendThrowsOnceBeforeWritingAndLeavesTheWriterStreaming() throws IOException {
        SegmentWriter writer = open("fault.wav.part", 16);
        writer.append(signal(10), CHANNELS, 10);
        long sizeBefore = Files.size(writer.partPath());

        writer.failNextAppend();

        assertThatThrownBy(() -> writer.append(signal(10), CHANNELS, 10))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("injected");
        assertThat(writer.isStreaming()).isTrue();
        assertThat(writer.frameCount()).as("nothing was written").isEqualTo(10);
        assertThat(Files.size(writer.partPath())).isEqualTo(sizeBefore);

        writer.append(signal(10), CHANNELS, 10);
        assertThat(writer.frameCount()).as("the fault is consumed by one append").isEqualTo(20);
        writer.seal();
        assertThat(SegmentFile.describe(writer.sealedPath()).frameCount()).isEqualTo(20);
    }

    @Test
    void aFailedRenameLeavesThePartWithTheExactSizesAlreadyPatchedIn() throws IOException {
        // Patch-before-rename, pinned deterministically: the fault fires
        // where Files.move would run, so whatever the header holds at that
        // moment is what a crash between patch and rename leaves behind.
        SegmentWriter writer = open("segment-000.wav.part", 24);
        writer.append(signal(300), CHANNELS, 300);
        long dataBytes = writer.dataBytes();
        assertThat(Story323TestSupport.sizeFields(writer.partPath()))
                .as("fixture: still provisional before the seal")
                .containsExactly(SegmentWriter.PROVISIONAL_SIZE, SegmentWriter.PROVISIONAL_SIZE);
        writer.failNextRename();

        assertThatThrownBy(writer::seal)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("injected rename failure");

        assertThat(writer.partPath()).exists();
        assertThat(writer.sealedPath()).doesNotExist();
        assertThat(writer.isStreaming()).isFalse();
        assertThat(writer.isSealed()).isFalse();
        assertThat(Story323TestSupport.sizeFields(writer.partPath()))
                .as("RIFF size and data size were patched before the rename was attempted")
                .containsExactly((int) (36 + dataBytes), (int) dataBytes);
        assertThat(SegmentFile.describe(writer.partPath()).frameCount()).isEqualTo(300);
        assertThat(Files.size(writer.partPath())).isEqualTo(SegmentWriter.DATA_OFFSET + dataBytes);
        assertThatThrownBy(() -> writer.append(signal(1), CHANNELS, 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(writer::seal).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theRenameFaultIsConsumedByOneSeal() throws IOException {
        // Non-vacuity of the seam: only the armed seal fails.
        SegmentWriter armed = open("armed.wav.part", 16);
        armed.append(signal(10), CHANNELS, 10);
        armed.failNextRename();
        assertThatThrownBy(armed::seal).isInstanceOf(IOException.class);

        SegmentWriter plain = open("plain.wav.part", 16);
        plain.append(signal(10), CHANNELS, 10);
        assertThat(plain.seal()).exists();
        assertThat(plain.isSealed()).isTrue();
    }

    @Test
    void aFailedHeaderWriteLeavesNoFileBehind() throws IOException {
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        Path part = tempDir.resolve("segment-000.wav.part");
        journal.failNextWrites(1);

        assertThatThrownBy(() -> SegmentWriter.open(part, SAMPLE_RATE, CHANNELS, 16, CADENCE, clock::get,
                journal.opener(SegmentWriter.CREATE_NEW_CHANNEL)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("injected write failure");

        assertThat(journal.events()).as("the channel was closed, and nothing was written").containsExactly("close");
        assertThat(part).as("a failed open leaves no streaming file").doesNotExist();
        assertThat(SegmentWriter.sealedPathFor(part)).doesNotExist();

        // Non-vacuity: the same opener, without the fault, does create the file.
        try (SegmentWriter writer = SegmentWriter.open(part, SAMPLE_RATE, CHANNELS, 16, CADENCE, clock::get,
                journal.opener(SegmentWriter.CREATE_NEW_CHANNEL))) {
            assertThat(part).exists();
            assertThat(writer.isStreaming()).isTrue();
        }
    }

    @Test
    void theCadenceForceAndTheSealsTwoForcesReachTheChannel() throws IOException {
        // The syscalls, not the counters: a delegating channel journals
        // force(false) / force(true), the header patches and the close.
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        clock.set(0);
        SegmentWriter writer = SegmentWriter.open(tempDir.resolve("segment-000.wav.part"), SAMPLE_RATE,
                CHANNELS, 16, CADENCE, clock::get, journal.opener(SegmentWriter.CREATE_NEW_CHANNEL));
        assertThat(journal.eventsWithoutDataWrites()).as("open writes the header").containsExactly("write@0");

        clock.set(5 * SECOND - 1);
        writer.append(signal(10), CHANNELS, 10);
        assertThat(journal.forces(false)).as("one nanosecond short of the cadence").isZero();

        clock.set(5 * SECOND);
        writer.append(signal(10), CHANNELS, 10);
        assertThat(journal.forces(false)).as("force(false) reached the channel at the cadence").isEqualTo(1);
        assertThat(writer.forceCount()).isEqualTo(1);

        clock.set(9 * SECOND);
        writer.append(signal(10), CHANNELS, 10);
        assertThat(journal.forces(false)).isEqualTo(1);

        clock.set(10 * SECOND);
        writer.append(signal(10), CHANNELS, 10);
        assertThat(journal.forces(false)).isEqualTo(2);
        assertThat(journal.forces(true)).as("no metadata force before the seal").isZero();

        writer.seal();

        assertThat(journal.forces(false)).as("the seal adds no cadence force").isEqualTo(2);
        assertThat(journal.forces(true)).as("the seal forces before and after the patch").isEqualTo(2);
        assertThat(journal.eventsWithoutDataWrites()).containsExactly(
                "write@0",
                "force(false)",
                "force(false)",
                "force(true)",
                "write@" + SegmentWriter.RIFF_SIZE_OFFSET,
                "write@" + SegmentWriter.DATA_SIZE_OFFSET,
                "force(true)",
                "close");
        assertThat(writer.sealedPath()).exists();
    }

    @Test
    void theDataLimitKeepsEverySegmentInsideSignedThirtyTwoBitSizes() {
        assertThat(SegmentWriter.MAX_DATA_BYTES).isEqualTo(Integer.MAX_VALUE - 44L);
        assertThat(SegmentWriter.DATA_OFFSET + SegmentWriter.MAX_DATA_BYTES)
                .as("the whole file's length fits a signed int")
                .isEqualTo(Integer.MAX_VALUE);
    }
}
