package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.recording.TakeManifest.SealedBy;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SegmentEntry;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SegmentState;
import com.benesquivelmusic.daw.core.recording.TakeManifest.TrackEntry;
import com.benesquivelmusic.daw.core.recording.TakeRecovery.RecoveredTake;
import com.benesquivelmusic.daw.core.recording.TakeRecovery.Unrecoverable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.assumeSymbolicLinks;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.rampValue;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TakeRecovery} follows no link (PR #982 review, Copilot
 * r4287534540). {@link TakeManifest.SegmentEntry#resolve} is lexical, so a
 * symbolic link at the track directory is the one way a manifest-listed
 * path leads out of the take: the scan must refuse the entry before it
 * stats, truncates, renames or deletes anything behind the link. Likewise a
 * take directory that is itself a link is no candidate: {@code recover}
 * returns empty before reading the manifest through it, and {@code scan}
 * does not list it. Every case here plants real files in a directory
 * outside the take and proves, byte for byte and by listing, that nothing
 * there changed. Each case aborts where the platform refuses to create a
 * symbolic link ({@link RampCaptureTestSupport#assumeSymbolicLinks}).
 */
class TakeRecoveryNeverFollowsALinkTest {

    private static final String TRACK = "vocal";
    private static final String SEGMENT_000 = TRACK + "/segment-000.wav";
    /** A directory name above the track directory in a two-level manifest path; the manifest's name rule admits it. */
    private static final String LINK = "linked";
    private static final double SAMPLE_RATE = 48_000.0;
    private static final int BIT_DEPTH = 16;
    private static final int MONO = 1;
    private static final Duration CADENCE = Duration.ofSeconds(5);
    private static final int FRAMES = 1_000;
    private static final Instant STARTED_AT = Instant.parse("2026-08-03T14:22:05Z");

    @TempDir
    Path projectDir;

    @TempDir
    Path elsewhere;

    private Path takesDir;
    private Path takeDir;

    @BeforeEach
    void allocateTheTake() throws IOException {
        Path audioDir = projectDir.resolve("audio");
        takesDir = TakeDirectories.takesDirectory(audioDir);
        takeDir = TakeDirectories.allocate(audioDir, STARTED_AT);
        assumeSymbolicLinks(projectDir);
    }

    @Test
    void aLinkedTrackDirectoryLeavesTheExternalPartWithFramesUntouchedAndReportsTheLink() throws IOException {
        Path externalPart = partWithFrames(elsewhere.resolve("segment-000.wav.part"), FRAMES);
        manifestFor(takeDir, streaming(SEGMENT_000, 0)).build().write(takeDir);
        Path link = Files.createSymbolicLink(takeDir.resolve(TRACK), elsewhere);
        var before = snapshot(elsewhere);

        Optional<RecoveredTake> recovered = TakeRecovery.recover(takeDir);

        assertThat(recovered).isPresent();
        RecoveredTake take = recovered.get();
        assertThat(take.sealedSegments()).as("nothing was sealed through the link").isEmpty();
        assertThat(take.unrecoverable()).extracting(Unrecoverable::file).containsExactly(takeDir.resolve(SEGMENT_000));
        assertThat(take.unrecoverable().getFirst().reason()).contains(link.toString()).contains("link");
        assertThat(snapshot(elsewhere)).as("the external directory is byte-identical").isEqualTo(before);
        assertThat(externalPart).as("not truncated, not renamed").isRegularFile();
        assertThat(elsewhere.resolve("segment-000.wav")).doesNotExist();
        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertLeftEntryManifestOnDisk();
    }

    @Test
    void aLinkedTrackDirectoryLeavesTheExternalZeroFramePartUndeleted() throws IOException {
        Path externalStandby = partWithFrames(elsewhere.resolve("segment-000.wav.part"), 0);
        assertThat(Files.size(externalStandby)).as("fixture: header only").isEqualTo(SegmentFile.HEADER_BYTES);
        manifestFor(takeDir, streaming(SEGMENT_000, 0)).build().write(takeDir);
        Path link = Files.createSymbolicLink(takeDir.resolve(TRACK), elsewhere);
        var before = snapshot(elsewhere);

        Optional<RecoveredTake> recovered = TakeRecovery.recover(takeDir);

        assertThat(recovered).isPresent();
        RecoveredTake take = recovered.get();
        assertThat(take.sealedSegments()).isEmpty();
        assertThat(take.unrecoverable()).extracting(Unrecoverable::file).containsExactly(takeDir.resolve(SEGMENT_000));
        assertThat(take.unrecoverable().getFirst().reason()).contains(link.toString()).contains("link");
        assertThat(externalStandby).as("the zero-frame rule never reached the external file").isRegularFile();
        assertThat(snapshot(elsewhere)).isEqualTo(before);
        assertLeftEntryManifestOnDisk();
    }

    @Test
    void aLinkedTrackDirectoryDoesNotMarkTheEntrySealedFromAnExternalWav() throws IOException {
        Path externalWav = sealedByWriter(elsewhere.resolve("segment-000.wav"), FRAMES);
        manifestFor(takeDir, streaming(SEGMENT_000, 0)).build().write(takeDir);
        Path link = Files.createSymbolicLink(takeDir.resolve(TRACK), elsewhere);
        var before = snapshot(elsewhere);

        Optional<RecoveredTake> recovered = TakeRecovery.recover(takeDir);

        assertThat(recovered).isPresent();
        RecoveredTake take = recovered.get();
        assertThat(take.sealedSegments()).as("the external .wav is not read as this take's segment").isEmpty();
        assertThat(take.playableSegments()).isEmpty();
        assertThat(take.unrecoverable()).extracting(Unrecoverable::file).containsExactly(takeDir.resolve(SEGMENT_000));
        assertThat(take.unrecoverable().getFirst().reason()).contains(link.toString()).contains("link");
        assertThat(externalWav).isRegularFile();
        assertThat(snapshot(elsewhere)).isEqualTo(before);
        assertLeftEntryManifestOnDisk();
    }

    /**
     * A manifest path two names deep ({@code TakeManifest.isNameSequence}
     * admits any number of {@code '/'}-separated names), the first of which
     * is a link: {@code <take>/linked/vocal/segment-000.wav} with
     * {@code linked} a link to the external directory, which really holds
     * {@code vocal/segment-000.wav.part} with frames. The last directory,
     * resolved through the link, IS a real directory, so a check of the
     * final component alone would pass and seal the external part; only the
     * top-down walk of {@code linkedAncestor} stops at the link, and it is
     * the link, not the directory behind it, that the refusal names.
     */
    @Test
    void aLinkAboveTheTrackDirectoryIsRefusedThoughTheDirectoryBehindItIsReal() throws IOException {
        Path externalPart = partWithFrames(elsewhere.resolve(TRACK).resolve("segment-000.wav.part"), FRAMES);
        String segment = LINK + "/" + SEGMENT_000;
        manifestFor(takeDir, streaming(segment, 0)).build().write(takeDir);
        Path link = Files.createSymbolicLink(takeDir.resolve(LINK), elsewhere);
        assertThat(Files.isDirectory(link.resolve(TRACK)))
                .as("fixture: the track directory behind the link is a real directory").isTrue();
        var before = snapshot(elsewhere);

        List<String> warnings = new ArrayList<>();
        Optional<RecoveredTake> recovered = warningsLoggedBy(() -> TakeRecovery.recover(takeDir), warnings);

        assertThat(recovered).isPresent();
        RecoveredTake take = recovered.get();
        assertThat(take.sealedSegments()).as("nothing was sealed through the link").isEmpty();
        assertThat(take.playableSegments()).isEmpty();
        assertThat(take.unrecoverable()).extracting(Unrecoverable::file).containsExactly(takeDir.resolve(segment));
        assertThat(take.unrecoverable().getFirst().reason())
                .as("the refusal names the link, not the real directory behind it")
                .contains("its directory " + link + " is a link");
        assertThat(warnings).singleElement().asString().contains(link.toString()).contains("link");
        assertThat(snapshot(elsewhere)).as("the external directory is byte-identical").isEqualTo(before);
        assertThat(externalPart).as("not truncated, not renamed").isRegularFile();
        assertThat(elsewhere.resolve(TRACK).resolve("segment-000.wav")).doesNotExist();
        assertThat(Files.isSymbolicLink(link)).isTrue();
        TakeManifest manifest = TakeManifest.read(TakeManifest.manifestPath(takeDir));
        assertThat(manifest.sealStatus()).isEqualTo(TakeManifest.SealStatus.ABORTED);
        assertThat(manifest.sealedBy()).contains(SealedBy.CRASH_RECOVERY);
        assertThat(manifest.segments()).as("the entry is left listed, still streaming").containsExactly(streaming(segment, 0));
    }

    @Test
    void aLinkedTakeDirectoryIsSkippedWithAWarningBeforeItsManifestIsRead() throws IOException {
        Path externalTake = externalStreamingTake();
        Path link = Files.createSymbolicLink(projectDir.resolve("linked-take"), externalTake);
        var before = snapshot(externalTake);

        List<String> warnings = new ArrayList<>();
        Optional<RecoveredTake> recovered = warningsLoggedBy(() -> TakeRecovery.recover(link), warnings);

        assertThat(recovered).isEmpty();
        assertThat(warnings).singleElement().asString().contains(link.toString()).contains("link");
        assertThat(snapshot(externalTake)).as("manifest and .part byte-identical; no .tmp, no .wav").isEqualTo(before);
        assertThat(externalTake.resolve(TakeManifest.FILE_NAME + TakeManifest.TMP_SUFFIX)).doesNotExist();
        assertThat(externalTake.resolve(SEGMENT_000)).doesNotExist();
        assertThat(Files.isSymbolicLink(link)).isTrue();
    }

    @Test
    void aMissingTakeDirectoryIsStillSilentlyNoTake() throws IOException {
        List<String> warnings = new ArrayList<>();

        Optional<RecoveredTake> recovered = warningsLoggedBy(
                () -> TakeRecovery.recover(takesDir.resolve("never-allocated")), warnings);

        assertThat(recovered).isEmpty();
        assertThat(warnings).isEmpty();
    }

    @Test
    void scanDoesNotListALinkedTakeDirectory() throws IOException {
        Path externalTake = externalStreamingTake();
        Path link = Files.createSymbolicLink(takesDir.resolve(TakeDirectories.takeDirectoryName(STARTED_AT, 2)),
                externalTake);
        assertThat(Files.isDirectory(link)).as("fixture: the link resolves to the external take").isTrue();
        var before = snapshot(externalTake);

        List<RecoveredTake> recovered = TakeRecovery.scan(takesDir);

        assertThat(recovered).as("the real take has no manifest and the linked one is not listed").isEmpty();
        assertThat(snapshot(externalTake)).isEqualTo(before);
        assertThat(externalTake.resolve(TakeManifest.FILE_NAME + TakeManifest.TMP_SUFFIX)).doesNotExist();
        assertThat(externalTake.resolve(SEGMENT_000)).doesNotExist();
    }

    /**
     * What today's scan does with a streaming manifest whose only entry was
     * left: the manifest is rewritten {@code aborted}/{@code crash-recovery}
     * (it was streaming) with the entry still listed, still streaming — the
     * outcome of {@code TakeRecoveryTest}'s "neither file exists" case.
     */
    private void assertLeftEntryManifestOnDisk() throws IOException {
        TakeManifest manifest = TakeManifest.read(TakeManifest.manifestPath(takeDir));
        assertThat(manifest.sealStatus()).isEqualTo(TakeManifest.SealStatus.ABORTED);
        assertThat(manifest.sealedBy()).contains(SealedBy.CRASH_RECOVERY);
        assertThat(manifest.segments()).containsExactly(streaming(SEGMENT_000, 0));
    }

    /** A take outside the project: a streaming manifest and a {@code .part} with frames under its track directory. */
    private Path externalStreamingTake() throws IOException {
        Path externalTake = Files.createDirectory(elsewhere.resolve(TakeDirectories.takeDirectoryName(STARTED_AT, 9)));
        partWithFrames(externalTake.resolve(TRACK).resolve("segment-000.wav.part"), FRAMES);
        manifestFor(externalTake, streaming(SEGMENT_000, 0)).build().write(externalTake);
        return externalTake;
    }

    /** A manifest for {@code dir} listing the mono track and the given segments, streaming. */
    private static TakeManifest.Builder manifestFor(Path dir, SegmentEntry... segments) {
        TakeManifest.Builder b = TakeManifest.builder()
                .take(dir.getFileName().toString())
                .startedAt(STARTED_AT)
                .sampleRate(SAMPLE_RATE)
                .bitDepth(BIT_DEPTH)
                .streamChannels(2)
                .startBeat(4.0)
                .startFrame(96_000L)
                .forceCadenceMillis(CADENCE.toMillis())
                .ringSlots(32)
                .ringFrames(512)
                .addTrack(new TrackEntry(TRACK, MONO, 0));
        for (SegmentEntry s : segments) {
            b.addSegment(s);
        }
        return b;
    }

    private static SegmentEntry streaming(String relativePath, int index) {
        return new SegmentEntry(TRACK, 0, index, relativePath, TakeManifest.FRAMES_STREAMING, SegmentState.STREAMING);
    }

    private static float[][] ramp(int frames) {
        float[][] audio = new float[MONO][frames];
        for (int i = 0; i < frames; i++) {
            audio[0][i] = rampValue(i);
        }
        return audio;
    }

    /** Writes {@code frames} ramp frames (none: the 44-byte header only) to {@code part} through an abandoned writer. */
    private static Path partWithFrames(Path part, int frames) throws IOException {
        try (SegmentWriter writer = SegmentWriter.open(part, SAMPLE_RATE, MONO, BIT_DEPTH, CADENCE, System::nanoTime)) {
            if (frames > 0) {
                writer.append(ramp(frames), MONO, frames);
            }
        }
        return part;
    }

    /** Writes and seals {@code frames} ramp frames at {@code wav}, as the live seal does. */
    private static Path sealedByWriter(Path wav, int frames) throws IOException {
        try (SegmentWriter writer = SegmentWriter.open(SegmentWriter.partPathFor(wav), SAMPLE_RATE, MONO, BIT_DEPTH,
                CADENCE, System::nanoTime)) {
            writer.append(ramp(frames), MONO, frames);
            writer.seal();
        }
        return wav;
    }

    /** Every regular file under {@code dir} (no link followed), relative name to hex of its bytes: the listing and the content in one. */
    private static TreeMap<String, String> snapshot(Path dir) throws IOException {
        TreeMap<String, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path file : walk.toList()) {
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    files.put(dir.relativize(file).toString(), HexFormat.of().formatHex(Files.readAllBytes(file)));
                } else if (!file.equals(dir)) {
                    files.put(dir.relativize(file).toString(), "<directory>");
                }
            }
        }
        return files;
    }

    /** Runs {@code body}, collecting every WARNING the recovery logs into {@code warnings}. */
    private static <T> T warningsLoggedBy(ThrowingSupplier<T> body, List<String> warnings) throws IOException {
        Logger logger = Logger.getLogger(TakeRecovery.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            return body.get();
        } finally {
            logger.removeHandler(handler);
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws IOException;
    }
}
