package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.recording.TakeManifest.GapEntry;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SealStatus;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SealedBy;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SegmentEntry;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SegmentState;
import com.benesquivelmusic.daw.core.recording.TakeManifest.TrackEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TakeManifest} — the take sidecar grammar of story 323 (book §3.3;
 * context D8).
 */
class TakeManifestTest {

    private static final String TAKE = "2026-08-03T14-22-05_take-0007";
    private static final Instant STARTED = Instant.parse("2026-08-03T14:22:05.123456789Z");

    @TempDir
    Path tempDir;

    private static TakeManifest.Builder full() {
        return TakeManifest.builder()
                .take(TAKE)
                .startedAt(STARTED)
                .sampleRate(48_000.0)
                .bitDepth(24)
                .streamChannels(2)
                .startBeat(16.5)
                .startFrame(396_000L)
                .forceCadenceMillis(5_000L)
                .ringSlots(32)
                .ringFrames(512)
                .addTrack(new TrackEntry("track-a", 2, 128))
                .addTrack(new TrackEntry("track-b", 1, 0))
                .addSegment(new SegmentEntry("track-a", 0, 0, "track-a/segment-000.wav", 1_000_000, SegmentState.SEALED))
                .addSegment(new SegmentEntry("track-a", 0, 1, "track-a/segment-001.wav", -1, SegmentState.STREAMING))
                .addSegment(new SegmentEntry("track-b", 0, 0, "track-b/segment-000.wav", 1_000_000, SegmentState.SEALED))
                .addSegment(new SegmentEntry("track-b", 1, 0, "track-b/segment-001.wav", -1, SegmentState.STREAMING))
                .addGap(new GapEntry(GapEntry.ALL_TRACKS, 48_000, 2))
                .addGap(new GapEntry("track-b", 96_000, 1))
                .overflowBlocks(3);
    }

    private static void assertFullFields(TakeManifest m) {
        assertThat(m.take()).isEqualTo(TAKE);
        assertThat(m.startedAt()).isEqualTo(STARTED);
        assertThat(m.sampleRate()).isEqualTo(48_000.0);
        assertThat(m.bitDepth()).isEqualTo(24);
        assertThat(m.streamChannels()).isEqualTo(2);
        assertThat(m.startBeat()).isEqualTo(16.5);
        assertThat(m.startFrame()).isEqualTo(396_000L);
        assertThat(m.forceCadenceMillis()).isEqualTo(5_000L);
        assertThat(m.ringSlots()).isEqualTo(32);
        assertThat(m.ringFrames()).isEqualTo(512);
        assertThat(m.tracks()).containsExactly(
                new TrackEntry("track-a", 2, 128), new TrackEntry("track-b", 1, 0));
        assertThat(m.segments()).containsExactly(
                new SegmentEntry("track-a", 0, 0, "track-a/segment-000.wav", 1_000_000, SegmentState.SEALED),
                new SegmentEntry("track-a", 0, 1, "track-a/segment-001.wav", -1, SegmentState.STREAMING),
                new SegmentEntry("track-b", 0, 0, "track-b/segment-000.wav", 1_000_000, SegmentState.SEALED),
                new SegmentEntry("track-b", 1, 0, "track-b/segment-001.wav", -1, SegmentState.STREAMING));
        assertThat(m.gaps()).containsExactly(
                new GapEntry("*", 48_000, 2), new GapEntry("track-b", 96_000, 1));
        assertThat(m.overflowBlocks()).isEqualTo(3);
    }

    @Test
    void writeThenReadRoundTripsEveryFieldWhileStreaming() throws IOException {
        TakeManifest original = full().build();

        Path file = original.write(tempDir);

        assertThat(file).isEqualTo(tempDir.resolve("take.manifest"));
        TakeManifest read = TakeManifest.read(file);
        assertFullFields(read);
        assertThat(read.sealStatus()).isEqualTo(SealStatus.STREAMING);
        assertThat(read.sealedBy()).isEmpty();
        assertThat(read).isEqualTo(original);
        assertThat(read.hashCode()).isEqualTo(original.hashCode());
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(text).contains("manifest-version=1\n", "seal-status=streaming\n");
        assertThat(text).doesNotContain("sealed-by=");
        assertThat(text)
                .as("the header comment names the format and no writer: the first write is the caller thread's")
                .startsWith("# DAWG take manifest (story 323)\nmanifest-version=1\n");
    }

    @Test
    void writeThenReadRoundTripsTheSealedState() throws IOException {
        TakeManifest original = full().sealed(SealedBy.STOP).build();
        Path file = original.write(tempDir);

        TakeManifest read = TakeManifest.read(file);

        assertFullFields(read);
        assertThat(read.sealStatus()).isEqualTo(SealStatus.SEALED);
        assertThat(read.sealedBy()).contains(SealedBy.STOP);
        assertThat(read).isEqualTo(original);
        assertThat(Files.readString(file)).contains("seal-status=sealed\n", "sealed-by=stop\n");
    }

    @Test
    void writeThenReadRoundTripsEveryAbortReason() throws IOException {
        for (SealedBy reason : new SealedBy[] {SealedBy.DISK_EXHAUSTION, SealedBy.WRITE_FAILURE, SealedBy.START_FAILURE}) {
            TakeManifest original = full().aborted(reason).build();
            Path file = original.write(tempDir);

            TakeManifest read = TakeManifest.read(file);

            assertThat(read.sealStatus()).as(reason.name()).isEqualTo(SealStatus.ABORTED);
            assertThat(read.sealedBy()).contains(reason);
            assertThat(read).isEqualTo(original);
        }
        assertThat(Files.readString(TakeManifest.manifestPath(tempDir)))
                .contains("seal-status=aborted\n", "sealed-by=start-failure\n");
    }

    @Test
    void writeIsAtomicLeavesNoTmpAndReplacesAnExistingManifest() throws IOException {
        full().build().write(tempDir);
        full().overflowBlocks(9).sealed(SealedBy.STOP).build().write(tempDir);

        try (Stream<Path> entries = Files.list(tempDir)) {
            assertThat(entries.map(p -> p.getFileName().toString()))
                    .containsExactly(TakeManifest.FILE_NAME);
        }
        assertThat(tempDir.resolve(TakeManifest.FILE_NAME + TakeManifest.TMP_SUFFIX)).doesNotExist();
        TakeManifest read = TakeManifest.read(TakeManifest.manifestPath(tempDir));
        assertThat(read.overflowBlocks()).isEqualTo(9);
        assertThat(read.sealStatus()).isEqualTo(SealStatus.SEALED);
    }

    @Test
    void segmentsForOrdersByLaneThenIndexEvenWhenTheFileListsThemOutOfOrder() throws IOException {
        String text = """
                manifest-version=1
                take=t
                started-at=2026-08-03T14:22:05Z
                sample-rate=44100.0
                bit-depth=16
                stream-channels=2
                start-beat=0.0
                start-frame=0
                force-cadence-millis=5000
                ring-slots=8
                ring-frames=256
                track=b|1|0
                track=a|2|0
                segment=a|1|0|a/segment-002.wav|-1|streaming
                segment=b|0|0|b/segment-000.wav|10|sealed
                segment=a|0|1|a/segment-001.wav|20|sealed
                segment=a|0|0|a/segment-000.wav|10|sealed
                overflow-blocks=0
                seal-status=streaming
                """;

        TakeManifest m = TakeManifest.parse(text, "inline");

        assertThat(m.segmentsFor("a")).extracting(SegmentEntry::lane, SegmentEntry::index)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(0, 0),
                        org.assertj.core.groups.Tuple.tuple(0, 1),
                        org.assertj.core.groups.Tuple.tuple(1, 0));
        assertThat(m.segmentsFor("b")).extracting(SegmentEntry::relativePath)
                .containsExactly("b/segment-000.wav");
        assertThat(m.segmentsFor("missing")).isEmpty();
        // The canonical list groups by armed-track order (b first), then lane, then index.
        assertThat(m.segments()).extracting(SegmentEntry::relativePath).containsExactly(
                "b/segment-000.wav", "a/segment-000.wav", "a/segment-001.wav", "a/segment-002.wav");
        // Re-writing emits the canonical order.
        assertThat(m.toText()).containsSubsequence(
                "segment=b|0|0|", "segment=a|0|0|", "segment=a|0|1|", "segment=a|1|0|");
    }

    @Test
    void unknownKeysAreIgnoredAndMissingSealStatusReadsAsStreaming() throws IOException {
        String text = """
                # a comment line
                manifest-version=1
                take=t
                started-at=2026-08-03T14:22:05Z
                x-future-key=some value with = signs
                sample-rate=48000.0
                bit-depth=24
                stream-channels=2
                start-beat=1.5
                start-frame=72000
                force-cadence-millis=5000
                ring-slots=16
                ring-frames=512

                """;

        TakeManifest m = TakeManifest.parse(text, "inline");

        assertThat(m.sealStatus()).isEqualTo(SealStatus.STREAMING);
        assertThat(m.sealedBy()).isEmpty();
        assertThat(m.overflowBlocks()).isZero();
        assertThat(m.tracks()).isEmpty();
        assertThat(m.segments()).isEmpty();
        assertThat(m.gaps()).isEmpty();
        assertThat(m.startFrame()).isEqualTo(72_000L);
        // Non-vacuous: the same reader does honour the key when present.
        TakeManifest sealed = TakeManifest.parse(text + "seal-status=sealed\nsealed-by=stop\n", "inline");
        assertThat(sealed.sealStatus()).isEqualTo(SealStatus.SEALED);
        assertThat(sealed.sealedBy()).contains(SealedBy.STOP);
    }

    @Test
    void relativePathsUseForwardSlashesAndNeverEmitBackslashes() throws IOException {
        Path takeDir = tempDir.resolve(TAKE);
        Files.createDirectories(takeDir.resolve("track-a"));
        Path file = takeDir.resolve("track-a").resolve("segment-000.wav");
        String relative = SegmentEntry.relativePathFor(takeDir, file);
        assertThat(relative).isEqualTo("track-a/segment-000.wav");

        SegmentEntry entry = new SegmentEntry("track-a", 0, 0, relative, -1, SegmentState.STREAMING);
        assertThat(entry.resolve(takeDir)).isEqualTo(file);
        TakeManifest m = full().segments(List.of(entry)).build();
        Path written = m.write(takeDir);
        String text = Files.readString(written, StandardCharsets.UTF_8);

        assertThat(text).contains("segment=track-a|0|0|track-a/segment-000.wav|-1|streaming\n");
        assertThat(text).doesNotContain("\\");
        assertThat(TakeManifest.read(written).segmentsFor("track-a")).containsExactly(entry);

        assertThatThrownBy(() -> new SegmentEntry("t", 0, 0, "t\\segment-000.wav", -1, SegmentState.STREAMING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forward slashes");
    }

    @Test
    void sealedSegmentEntryCarriesExactFrames() {
        SegmentEntry streaming = new SegmentEntry("t", 0, 3, "t/segment-003.wav", -1, SegmentState.STREAMING);
        SegmentEntry sealed = streaming.sealed(4_321);
        assertThat(sealed).isEqualTo(new SegmentEntry("t", 0, 3, "t/segment-003.wav", 4_321, SegmentState.SEALED));
        assertThatThrownBy(() -> new SegmentEntry("t", 0, 0, "t/x.wav", -1, SegmentState.SEALED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SegmentEntry("t", 0, 0, "t/x.wav", -2, SegmentState.STREAMING))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void putSegmentReplacesTheEntryWithTheSameKey() {
        TakeManifest m = full().build();
        TakeManifest updated = m.toBuilder()
                .putSegment(new SegmentEntry("track-a", 0, 1, "track-a/segment-001.wav", 777, SegmentState.SEALED))
                .putSegment(new SegmentEntry("track-a", 0, 2, "track-a/segment-002.wav", -1, SegmentState.STREAMING))
                .build();

        assertThat(updated.segmentsFor("track-a")).extracting(SegmentEntry::index, SegmentEntry::frames)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(0, 1_000_000L),
                        org.assertj.core.groups.Tuple.tuple(1, 777L),
                        org.assertj.core.groups.Tuple.tuple(2, -1L));
        assertThat(m.segmentsFor("track-a")).as("the original is immutable").hasSize(2);
    }

    @Test
    void sealStatusAndSealedByMustAgree() {
        assertThatThrownBy(() -> full().sealStatus(SealStatus.SEALED).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sealed-by");
        assertThatThrownBy(() -> full().sealStatus(SealStatus.STREAMING).sealedBy(SealedBy.STOP).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TakeManifest.parse(full().build().toText() + "sealed-by=stop\n", "inline"))
                .isInstanceOf(IOException.class);
    }

    @Test
    void separatorCharactersAreRejectedInIdsAndPaths() {
        assertThatThrownBy(() -> new TrackEntry("a|b", 2, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GapEntry("a\nb", 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SegmentEntry("t", 0, 0, "t/a|b.wav", -1, SegmentState.STREAMING))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> full().take("x|y").build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> full().take(" ").build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GapEntry("*", 0, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GapEntry("*", -1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrackEntry("a", 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void truncationEpisodesAndTheTruncatedTotalRoundTrip() throws IOException {
        GapEntry truncation = new GapEntry(GapEntry.ALL_TRACKS, 256, 0);
        assertThat(truncation.isTruncation()).isTrue();
        assertThat(new GapEntry(GapEntry.ALL_TRACKS, 256, 1).isTruncation()).isFalse();
        TakeManifest original = full().addGap(truncation).truncatedFrames(768).build();

        Path file = original.write(tempDir);

        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(text).contains("gap=*|256|0\n").contains("truncated-frames=768\n");
        TakeManifest read = TakeManifest.read(file);
        assertThat(read.truncatedFrames()).isEqualTo(768);
        assertThat(read.gaps()).containsExactly(
                new GapEntry("*", 48_000, 2), new GapEntry("track-b", 96_000, 1), truncation);
        assertThat(read).isEqualTo(original);
        assertThat(read.hashCode()).isEqualTo(original.hashCode());
        assertThat(read).as("the total is part of the manifest's identity")
                .isNotEqualTo(full().addGap(truncation).truncatedFrames(767).build());
        assertThat(original.toBuilder().build()).isEqualTo(original);
        assertThatThrownBy(() -> full().truncatedFrames(-1).build()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aManifestWithoutTheTruncatedFramesKeyReadsAsZero() throws IOException {
        String withKey = full().build().toText();
        assertThat(withKey).contains("truncated-frames=0\n");

        TakeManifest older = TakeManifest.parse(withKey.replace("truncated-frames=0\n", ""), "older");

        assertThat(older.truncatedFrames()).isZero();
        assertThat(older).isEqualTo(full().build());
    }

    @Test
    void aLeadingByteOrderMarkIsIgnored() throws IOException {
        TakeManifest original = full().sealed(SealedBy.STOP).build();
        String text = original.toText();
        assertThat(text).as("fixture: the first line is a comment").startsWith("#");

        String mark = String.valueOf((char) 0xFEFF); // U+FEFF, kept out of the source as a character

        assertThat(TakeManifest.parse(mark + text, "bom")).isEqualTo(original);
        // Also when the mark precedes a key=value line rather than the comment.
        String noComment = text.substring(text.indexOf('\n') + 1);
        assertThat(noComment).startsWith("manifest-version=1");
        assertThat(TakeManifest.parse(mark + noComment, "bom")).isEqualTo(original);

        Path file = tempDir.resolve("bom.manifest");
        Files.write(file, (mark + text).getBytes(StandardCharsets.UTF_8));
        assertThat(Files.readAllBytes(file)).as("fixture: the file starts with the UTF-8 byte-order mark")
                .startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(TakeManifest.read(file)).isEqualTo(original);
        // Only a LEADING mark is tolerated.
        assertThatThrownBy(() -> TakeManifest.parse(text + mark + "junk\n", "bom"))
                .isInstanceOf(IOException.class);
    }

    @Test
    void writeForcesTheStagingFileToStorageBeforeItIsMovedIntoPlace() throws IOException {
        TakeManifest first = full().build();
        TakeManifest second = full().overflowBlocks(9).sealed(SealedBy.STOP).build();
        Path target = first.write(tempDir);
        byte[] firstBytes = Files.readAllBytes(target);
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        List<byte[]> targetAtForce = new ArrayList<>();
        List<byte[]> stagingAtForce = new ArrayList<>();
        Path staging = tempDir.resolve(TakeManifest.FILE_NAME + TakeManifest.TMP_SUFFIX);
        journal.beforeForce(() -> {
            try {
                targetAtForce.add(Files.readAllBytes(target));
                stagingAtForce.add(Files.readAllBytes(staging));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        second.write(tempDir, journal.opener(TakeManifest.STAGING_CHANNEL));

        assertThat(journal.forces(true)).as("the staging file is forced, data and metadata").isEqualTo(1);
        assertThat(journal.forces(false)).isZero();
        assertThat(journal.events()).as("written, forced, closed — in that order")
                .startsWith("write").endsWith("force(true)", "close");
        assertThat(targetAtForce).as("the force ran while the previous manifest was still in place")
                .singleElement().isEqualTo(firstBytes);
        assertThat(stagingAtForce).as("and the whole new manifest was already in the staging file")
                .singleElement().isEqualTo(second.toText().getBytes(StandardCharsets.UTF_8));
        assertThat(TakeManifest.read(target)).isEqualTo(second);
        assertThat(staging).doesNotExist();
    }

    @Test
    void readRejectsUnknownVersionMissingKeysAndMalformedLines() throws IOException {
        String valid = full().build().toText();

        assertThatThrownBy(() -> TakeManifest.parse(valid.replace("manifest-version=1", "manifest-version=2"), "m"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("manifest-version 2");
        assertThatThrownBy(() -> TakeManifest.parse(valid.replace("sample-rate=48000.0\n", ""), "m"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("sample-rate");
        assertThatThrownBy(() -> TakeManifest.parse(valid + "no-equals-sign\n", "m"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("key=value");
        assertThatThrownBy(() -> TakeManifest.parse(valid + "segment=a|b\n", "m"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("6");
        assertThatThrownBy(() -> TakeManifest.parse(valid.replace("bit-depth=24", "bit-depth=twenty"), "m"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("bit-depth");
        assertThatThrownBy(() -> TakeManifest.read(tempDir.resolve("absent.manifest")))
                .isInstanceOf(IOException.class);
    }

    @Test
    void removeSegmentDropsOnlyTheEntryWithTheSameKey() {
        TakeManifest.Builder b = full();

        assertThat(b.removeSegment("track-a", 0, 1)).isTrue();
        assertThat(b.removeSegment("track-a", 0, 1)).as("already gone").isFalse();
        assertThat(b.removeSegment("track-b", 0, 1)).as("different key").isFalse();

        TakeManifest m = b.build();
        assertThat(m.segmentsFor("track-a")).extracting(SegmentEntry::index).containsExactly(0);
        assertThat(m.segmentsFor("track-b")).hasSize(2);
        assertThat(m.toText()).doesNotContain("track-a/segment-001.wav");
    }

    @Test
    void windowsLineEndingsParseTheSame() throws IOException {
        TakeManifest original = full().sealed(SealedBy.STOP).build();
        String crlf = original.toText().replace("\n", "\r\n");

        assertThat(TakeManifest.parse(crlf, "crlf")).isEqualTo(original);
    }
}
