package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.recording.TakeManifest.SealStatus;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SealedBy;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SegmentEntry;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SegmentState;
import com.benesquivelmusic.daw.core.recording.TakeManifest.TrackEntry;
import com.benesquivelmusic.daw.core.recording.TakeRecovery.RecoveredTake;
import com.benesquivelmusic.daw.core.recording.TakeRecovery.Unrecoverable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.assertDecodedRamp;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.hasProvisionalSizes;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.rampValue;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.sizeFields;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link TakeRecovery} — the crash grammar of Recording Reliability book
 * §4.7 (story 327): a streaming manifest plus a {@code .part} left by a
 * writer that was never sealed — the files a crash mid-take leaves —
 * become a playable take marked {@code aborted}/{@code crash-recovery},
 * with the frame count derived from the file length; a second scan is a
 * no-op; anything the grammar cannot vouch for is reported and left as it
 * is. The crash is simulated by abandoning a {@link SegmentWriter} without
 * sealing it, the way {@code RecordingSession.abandonWithoutSeal()} does,
 * and by appending a torn partial frame behind it. Also: a streaming entry
 * under an already aborted manifest is healed and the found reason kept; an
 * empty standby {@code .part} is deleted as the live seal deletes an empty
 * tail — only once the manifest that drops its entry is on disk, so a
 * manifest write that fails leaves it, and a delete that fails after the
 * write is reported with the manifest standing; an unlisted zero-frame
 * {@code .part} (the live seal's undeletable standby; the file a scan
 * dropped and then could not delete) is deleted wherever a scan finds it,
 * while an unlisted {@code .part} with frames, one shorter than the header,
 * one of another format, one whose header claims more channels than the
 * track its directory names (a widened frame that floors real mono frames
 * to none) and one under a directory naming no listed track are left; a
 * sealed take with an orphan is no candidate; one take's failure never
 * hides the next.
 */
class TakeRecoveryTest {

    private static final String TRACK = "vocal";
    private static final String SEGMENT_000 = TRACK + "/segment-000.wav";
    private static final String SEGMENT_001 = TRACK + "/segment-001.wav";
    private static final double SAMPLE_RATE = 48_000.0;
    private static final int BIT_DEPTH = 16;
    private static final int MONO = 1;
    private static final int BYTES_PER_FRAME_MONO_16 = 2;
    private static final Duration CADENCE = Duration.ofSeconds(5);
    private static final int FRAMES = 1_000;

    @TempDir
    Path projectDir;

    private Path takesDir;
    private Path takeDir;

    @BeforeEach
    void allocateTheTake() throws IOException {
        Path audioDir = projectDir.resolve("audio");
        takesDir = TakeDirectories.takesDirectory(audioDir);
        takeDir = TakeDirectories.allocate(audioDir, Instant.parse("2026-08-03T14:22:05Z"));
    }

    @AfterEach
    void clearTheFaultSeam() {
        TakeRecovery.setRecoverFaultForTest(null);
    }

    /** A manifest for {@code dir} listing the mono track and the given segments, streaming unless told otherwise. */
    private static TakeManifest.Builder manifestFor(Path dir, SegmentEntry... segments) {
        TakeManifest.Builder b = TakeManifest.builder()
                .take(dir.getFileName().toString())
                .startedAt(Instant.parse("2026-08-03T14:22:05Z"))
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

    /** The ramp from global frame 0, {@code frames} long, in {@code channels} identical rows. */
    private static float[][] ramp(int channels, int frames) {
        float[][] audio = new float[channels][frames];
        for (int ch = 0; ch < channels; ch++) {
            for (int i = 0; i < frames; i++) {
                audio[ch][i] = rampValue(i);
            }
        }
        return audio;
    }

    /**
     * Writes {@code frames} ramp frames to {@code part} through a writer
     * that is then abandoned — closed with no size patch and no rename, as
     * a crash leaves it — and returns the writer's part path.
     */
    private Path abandonedPart(String relativePath, int channels, int frames) throws IOException {
        Path part = SegmentWriter.partPathFor(takeDir.resolve(relativePath));
        try (SegmentWriter writer = SegmentWriter.open(part, SAMPLE_RATE, channels, BIT_DEPTH, CADENCE,
                System::nanoTime)) {
            if (frames > 0) {
                writer.append(ramp(channels, frames), channels, frames);
            }
        }
        assertThat(hasProvisionalSizes(part)).as("fixture: the abandoned part keeps the streaming sentinel").isTrue();
        return part;
    }

    /** Writes and seals {@code frames} ramp frames at {@code relativePath}, as the live seal does. */
    private Path sealedByWriter(String relativePath, int frames) throws IOException {
        Path wav = takeDir.resolve(relativePath);
        try (SegmentWriter writer = SegmentWriter.open(SegmentWriter.partPathFor(wav),
                SAMPLE_RATE, MONO, BIT_DEPTH, CADENCE, System::nanoTime)) {
            writer.append(ramp(MONO, frames), MONO, frames);
            writer.seal();
        }
        return wav;
    }

    /**
     * Writes at {@code relativePath} a {@code .part} whose header claims
     * {@code headerChannels} channels and then holds {@code monoFrames}
     * whole frames of the listed mono track's width — a header that widens
     * the frame past the data behind it, so the length-derived count floors
     * to no frame at all — and returns its path.
     */
    private Path partWhoseHeaderClaimsChannels(String relativePath, int headerChannels, int monoFrames)
            throws IOException {
        Path part = SegmentWriter.partPathFor(takeDir.resolve(relativePath));
        try (SegmentWriter _ = SegmentWriter.open(part, SAMPLE_RATE, headerChannels, BIT_DEPTH, CADENCE,
                System::nanoTime)) {
            // The open writes the header; nothing is appended through the writer.
        }
        Files.write(part, new byte[monoFrames * BYTES_PER_FRAME_MONO_16], StandardOpenOption.APPEND);
        assertThat(SegmentFile.describe(part).frameCount())
                .as("fixture: under the widened header the mono frames floor to none").isZero();
        return part;
    }

    private static SegmentEntry sealed(String relativePath, int index, long frames) {
        return new SegmentEntry(TRACK, 0, index, relativePath, frames, SegmentState.SEALED);
    }

    /** Collects every WARNING the recovery logs while {@code body} runs. */
    private static List<String> warningsLoggedBy(ThrowingRunnable body) throws IOException {
        Logger logger = Logger.getLogger(TakeRecovery.class.getName());
        List<String> messages = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    messages.add(record.getMessage());
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
            body.run();
        } finally {
            logger.removeHandler(handler);
        }
        return messages;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws IOException;
    }

    /** Appends one byte — less than a frame — the way a write torn by the crash would leave it. */
    private static void tearATrailingFrame(Path part) throws IOException {
        Files.write(part, new byte[] {0x7f}, StandardOpenOption.APPEND);
    }

    private TakeManifest manifestOnDisk() throws IOException {
        return TakeManifest.read(TakeManifest.manifestPath(takeDir));
    }

    private byte[] manifestBytesOnDisk() throws IOException {
        return Files.readAllBytes(TakeManifest.manifestPath(takeDir));
    }

    /**
     * Plants a non-empty directory where {@link TakeManifest#write(Path)}
     * stages its file ({@code take.manifest.tmp}), so that opening the
     * staging channel fails and the write throws, the manifest on disk
     * untouched; the returned path is what to delete to let a later write
     * land.
     */
    private Path refuseTheManifestWrite() throws IOException {
        Path staging = takeDir.resolve(TakeManifest.FILE_NAME + TakeManifest.TMP_SUFFIX);
        Files.createDirectory(staging);
        Files.writeString(staging.resolve("occupied"), "the staging file cannot be opened");
        return staging;
    }

    private static void allowTheManifestWriteAgain(Path staging) throws IOException {
        Files.delete(staging.resolve("occupied"));
        Files.delete(staging);
    }

    /**
     * Keeps {@code file} from being deleted while the returned hold is open,
     * the way a platform refuses a delete: on Windows a {@code java.io}
     * stream holds it open without delete sharing, so {@code Files.delete}
     * fails with a sharing violation; elsewhere the write bits of its
     * directory are withdrawn, so the unlink is refused. Closing the hold
     * releases the stream or restores the bits. The caller assumes, never
     * asserts, that the refusal took: a POSIX process that overrides the
     * permission bits (root) or a mount that ignores them (WSL's drvfs)
     * deletes the file all the same, and the test is skipped there.
     */
    private static AutoCloseable holdUndeletable(Path file) throws IOException {
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            InputStream open = new FileInputStream(file.toFile());
            return open::close;
        }
        Path directory = file.getParent();
        Set<PosixFilePermission> before = Files.getPosixFilePermissions(directory);
        Set<PosixFilePermission> withoutWrite = EnumSet.noneOf(PosixFilePermission.class);
        withoutWrite.addAll(before);
        withoutWrite.removeAll(EnumSet.of(PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.OTHERS_WRITE));
        Files.setPosixFilePermissions(directory, withoutWrite);
        return () -> Files.setPosixFilePermissions(directory, before);
    }

    @Test
    void aStreamingManifestWithAnAbandonedPartBecomesAPlayableTakeSealedByCrashRecovery() throws IOException {
        manifestFor(takeDir, streaming(SEGMENT_000, 0)).build().write(takeDir);
        Path part = abandonedPart(SEGMENT_000, MONO, FRAMES);
        tearATrailingFrame(part);
        Path wav = takeDir.resolve(SEGMENT_000);
        Instant before = Instant.now();

        List<RecoveredTake> recovered = TakeRecovery.scan(takesDir);

        assertThat(recovered).hasSize(1);
        RecoveredTake take = recovered.getFirst();
        assertThat(take.takeDirectory()).isEqualTo(takeDir);
        assertThat(take.unrecoverable()).isEmpty();
        SegmentEntry sealed = new SegmentEntry(TRACK, 0, 0, SEGMENT_000, FRAMES, SegmentState.SEALED);
        assertThat(take.sealedSegments()).containsExactly(sealed);
        assertThat(take.playableSegments()).containsExactly(sealed);

        TakeManifest manifest = manifestOnDisk();
        assertThat(manifest).isEqualTo(take.manifest());
        assertThat(manifest.sealStatus()).isEqualTo(SealStatus.ABORTED);
        assertThat(manifest.sealedBy()).contains(SealedBy.CRASH_RECOVERY);
        assertThat(manifest.sealedAt()).isPresent();
        assertThat(manifest.sealedAt().orElseThrow()).isBetween(before, Instant.now());
        assertThat(manifest.segments()).containsExactly(sealed);
        assertThat(Files.readString(TakeManifest.manifestPath(takeDir)))
                .contains("seal-status=aborted\n", "sealed-by=crash-recovery\n", "sealed-at=");

        assertThat(part).as("the .part was renamed").doesNotExist();
        assertThat(wav).isRegularFile();
        assertThat(Files.size(wav)).as("the torn byte is gone; whole frames remain")
                .isEqualTo(SegmentFile.HEADER_BYTES + (long) FRAMES * BYTES_PER_FRAME_MONO_16);
        assertThat(sizeFields(wav)).containsExactly(36 + FRAMES * BYTES_PER_FRAME_MONO_16, FRAMES * BYTES_PER_FRAME_MONO_16);
        SegmentFile.Description description = SegmentFile.describe(wav);
        assertThat(description.sealed()).isTrue();
        assertThat(description.frameCount()).isEqualTo(FRAMES);
        assertDecodedRamp(SegmentFile.readFrames(wav)[0], 0, FRAMES);
    }

    @Test
    void aSecondScanOverARecoveredTakeIsANoOp() throws IOException {
        manifestFor(takeDir, streaming(SEGMENT_000, 0)).build().write(takeDir);
        abandonedPart(SEGMENT_000, MONO, FRAMES);
        assertThat(TakeRecovery.scan(takesDir)).hasSize(1);
        byte[] manifestBytes = Files.readAllBytes(TakeManifest.manifestPath(takeDir));
        byte[] wavBytes = Files.readAllBytes(takeDir.resolve(SEGMENT_000));

        assertThat(TakeRecovery.scan(takesDir)).isEmpty();
        assertThat(TakeRecovery.recover(takeDir)).isEmpty();

        assertThat(Files.readAllBytes(TakeManifest.manifestPath(takeDir))).isEqualTo(manifestBytes);
        assertThat(Files.readAllBytes(takeDir.resolve(SEGMENT_000))).isEqualTo(wavBytes);
    }

    @Test
    void aStreamingManifestWhoseEverySegmentWasSealedIsOnlyMarkedRecovered() throws IOException {
        // The crash fell after the last segment's seal and before the final manifest write.
        Path part = abandonedPart(SEGMENT_000, MONO, FRAMES);
        Path wav = takeDir.resolve(SEGMENT_000);
        try (SegmentWriter writer = SegmentWriter.open(SegmentWriter.partPathFor(takeDir.resolve(SEGMENT_001)),
                SAMPLE_RATE, MONO, BIT_DEPTH, CADENCE, System::nanoTime)) {
            writer.append(ramp(MONO, FRAMES), MONO, FRAMES);
            writer.seal();
        }
        Files.delete(part);
        manifestFor(takeDir, streaming(SEGMENT_000, 0), streaming(SEGMENT_001, 1)).build().write(takeDir);
        assertThat(wav).doesNotExist();

        List<RecoveredTake> recovered = TakeRecovery.scan(takesDir);

        assertThat(recovered).hasSize(1);
        RecoveredTake take = recovered.getFirst();
        // segment-000: neither file exists — reported, entry left streaming.
        // segment-001: the .wav exists with no .part — sealed from its data size.
        assertThat(take.sealedSegments()).containsExactly(
                new SegmentEntry(TRACK, 0, 1, SEGMENT_001, FRAMES, SegmentState.SEALED));
        assertThat(take.unrecoverable()).extracting(Unrecoverable::file).containsExactly(wav);
        assertThat(take.unrecoverable().getFirst().reason()).contains("neither");
        TakeManifest manifest = manifestOnDisk();
        assertThat(manifest.sealedBy()).contains(SealedBy.CRASH_RECOVERY);
        assertThat(manifest.segments()).containsExactly(
                streaming(SEGMENT_000, 0),
                new SegmentEntry(TRACK, 0, 1, SEGMENT_001, FRAMES, SegmentState.SEALED));
    }

    @Test
    void aSealedTakeWithNoPartIsNotACandidate() throws IOException {
        try (SegmentWriter writer = SegmentWriter.open(SegmentWriter.partPathFor(takeDir.resolve(SEGMENT_000)),
                SAMPLE_RATE, MONO, BIT_DEPTH, CADENCE, System::nanoTime)) {
            writer.append(ramp(MONO, FRAMES), MONO, FRAMES);
            writer.seal();
        }
        manifestFor(takeDir, new SegmentEntry(TRACK, 0, 0, SEGMENT_000, FRAMES, SegmentState.SEALED))
                .sealed(SealedBy.STOP).sealedAt(Instant.parse("2026-08-03T14:25:05Z")).build().write(takeDir);
        byte[] manifestBytes = Files.readAllBytes(TakeManifest.manifestPath(takeDir));

        assertThat(TakeRecovery.scan(takesDir)).isEmpty();
        assertThat(TakeRecovery.recover(takeDir)).isEmpty();

        assertThat(Files.readAllBytes(TakeManifest.manifestPath(takeDir))).isEqualTo(manifestBytes);
    }

    @Test
    void aPartShorterThanTheHeaderIsReportedAndLeftUntouched() throws IOException {
        manifestFor(takeDir, streaming(SEGMENT_000, 0)).build().write(takeDir);
        Path part = SegmentWriter.partPathFor(takeDir.resolve(SEGMENT_000));
        Files.createDirectories(part.getParent());
        byte[] stub = "RIFF\u0000\u0000\u0000\u0000WAVE".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        Files.write(part, stub);

        List<RecoveredTake> recovered = TakeRecovery.scan(takesDir);

        assertThat(recovered).hasSize(1);
        RecoveredTake take = recovered.getFirst();
        assertThat(take.sealedSegments()).isEmpty();
        assertThat(take.unrecoverable()).hasSize(1);
        assertThat(take.unrecoverable().getFirst().file()).isEqualTo(part);
        assertThat(take.unrecoverable().getFirst().reason()).contains("shorter than the 44-byte");
        assertThat(part).as("never deleted, never patched").isRegularFile();
        assertThat(Files.readAllBytes(part)).isEqualTo(stub);
        assertThat(takeDir.resolve(SEGMENT_000)).doesNotExist();
        // The manifest is still marked recovered: the take streamed when the JVM died.
        TakeManifest manifest = manifestOnDisk();
        assertThat(manifest.sealStatus()).isEqualTo(SealStatus.ABORTED);
        assertThat(manifest.sealedBy()).contains(SealedBy.CRASH_RECOVERY);
        assertThat(manifest.segments()).containsExactly(streaming(SEGMENT_000, 0));
        // And the file keeps the take a candidate: it is reported again, nothing is rewritten.
        byte[] manifestBytes = Files.readAllBytes(TakeManifest.manifestPath(takeDir));
        List<RecoveredTake> again = TakeRecovery.scan(takesDir);
        assertThat(again).hasSize(1);
        assertThat(again.getFirst().unrecoverable()).extracting(Unrecoverable::file).containsExactly(part);
        assertThat(Files.readAllBytes(TakeManifest.manifestPath(takeDir))).isEqualTo(manifestBytes);
    }

    @Test
    void aPartWhoseHeaderDisagreesWithTheManifestIsReportedAndLeftUntouched() throws IOException {
        manifestFor(takeDir, streaming(SEGMENT_000, 0)).build().write(takeDir);
        // The manifest lists the track as mono; the file says stereo.
        Path part = abandonedPart(SEGMENT_000, 2, FRAMES);
        byte[] partBytes = Files.readAllBytes(part);

        List<RecoveredTake> recovered = TakeRecovery.scan(takesDir);

        assertThat(recovered).hasSize(1);
        RecoveredTake take = recovered.getFirst();
        assertThat(take.sealedSegments()).isEmpty();
        assertThat(take.unrecoverable()).hasSize(1);
        assertThat(take.unrecoverable().getFirst().file()).isEqualTo(part);
        assertThat(take.unrecoverable().getFirst().reason()).contains("2 channel(s)", "lists 1");
        assertThat(Files.readAllBytes(part)).isEqualTo(partBytes);
        assertThat(takeDir.resolve(SEGMENT_000)).doesNotExist();
        assertThat(manifestOnDisk().segments()).containsExactly(streaming(SEGMENT_000, 0));
    }

    @Test
    void aPartTheManifestDoesNotListIsReportedAndNotSealed() throws IOException {
        manifestFor(takeDir, streaming(SEGMENT_000, 0)).build().write(takeDir);
        abandonedPart(SEGMENT_000, MONO, FRAMES);
        // The manifest write lagged this segment's open.
        Path orphan = abandonedPart(SEGMENT_001, MONO, FRAMES);
        byte[] orphanBytes = Files.readAllBytes(orphan);

        List<RecoveredTake> recovered = TakeRecovery.scan(takesDir);

        assertThat(recovered).hasSize(1);
        RecoveredTake take = recovered.getFirst();
        assertThat(take.sealedSegments()).containsExactly(
                new SegmentEntry(TRACK, 0, 0, SEGMENT_000, FRAMES, SegmentState.SEALED));
        assertThat(take.unrecoverable()).hasSize(1);
        assertThat(take.unrecoverable().getFirst().file()).isEqualTo(orphan);
        assertThat(take.unrecoverable().getFirst().reason()).contains("not listed");
        assertThat(orphan).isRegularFile();
        assertThat(Files.readAllBytes(orphan)).isEqualTo(orphanBytes);
        assertThat(takeDir.resolve(SEGMENT_001)).doesNotExist();
        assertThat(manifestOnDisk().segments()).as("no entry is invented for the orphan").hasSize(1);
    }

    @Test
    void aPartWhoseSealedSiblingExistsIsReportedAndBothAreLeft() throws IOException {
        manifestFor(takeDir, streaming(SEGMENT_000, 0)).build().write(takeDir);
        Path part = abandonedPart(SEGMENT_000, MONO, FRAMES);
        Path wav = takeDir.resolve(SEGMENT_000);
        byte[] foreign = "not a segment".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        Files.write(wav, foreign);
        byte[] partBytes = Files.readAllBytes(part);

        List<RecoveredTake> recovered = TakeRecovery.scan(takesDir);

        assertThat(recovered).hasSize(1);
        assertThat(recovered.getFirst().sealedSegments()).isEmpty();
        assertThat(recovered.getFirst().unrecoverable()).hasSize(1);
        assertThat(recovered.getFirst().unrecoverable().getFirst().reason()).contains("never overwritten");
        assertThat(Files.readAllBytes(part)).isEqualTo(partBytes);
        assertThat(Files.readAllBytes(wav)).isEqualTo(foreign);
    }

    @Test
    void theRecoveredHeaderIsByteIdenticalToTheWritersOwnSeal() throws IOException {
        manifestFor(takeDir, streaming(SEGMENT_000, 0)).build().write(takeDir);
        abandonedPart(SEGMENT_000, MONO, FRAMES);
        Path sealedByWriter = takeDir.resolve(SEGMENT_001);
        try (SegmentWriter writer = SegmentWriter.open(SegmentWriter.partPathFor(sealedByWriter),
                SAMPLE_RATE, MONO, BIT_DEPTH, CADENCE, System::nanoTime)) {
            writer.append(ramp(MONO, FRAMES), MONO, FRAMES);
            writer.seal();
        }

        TakeRecovery.scan(takesDir);

        byte[] recovered = Files.readAllBytes(takeDir.resolve(SEGMENT_000));
        byte[] writers = Files.readAllBytes(sealedByWriter);
        assertThat(Arrays.copyOf(recovered, SegmentFile.HEADER_BYTES))
                .isEqualTo(Arrays.copyOf(writers, SegmentFile.HEADER_BYTES));
        assertThat(recovered).isEqualTo(writers);
    }

    @Test
    void aTakeDirectoryWithoutAManifestIsSkippedAndItsPartLeft() throws IOException {
        Path part = abandonedPart(SEGMENT_000, MONO, FRAMES);
        byte[] partBytes = Files.readAllBytes(part);

        assertThat(TakeRecovery.scan(takesDir)).isEmpty();
        assertThat(TakeRecovery.recover(takeDir)).isEmpty();

        assertThat(Files.readAllBytes(part)).isEqualTo(partBytes);
        assertThat(TakeManifest.manifestPath(takeDir)).doesNotExist();
    }

    @Test
    void aMissingTakesDirectoryYieldsNoTakes() throws IOException {
        assertThat(TakeRecovery.scan(projectDir.resolve("nowhere").resolve("takes"))).isEmpty();
    }

    @Test
    void scanRecoversEveryCandidateInDirectoryNameOrderAndSkipsTheRest() throws IOException {
        Path audioDir = projectDir.resolve("audio");
        Path later = TakeDirectories.allocate(audioDir, Instant.parse("2026-08-03T15:00:00Z"));
        Path sealedTake = TakeDirectories.allocate(audioDir, Instant.parse("2026-08-03T16:00:00Z"));
        manifestFor(takeDir, streaming(SEGMENT_000, 0)).build().write(takeDir);
        abandonedPart(SEGMENT_000, MONO, FRAMES);
        manifestFor(later, streaming(SEGMENT_000, 0)).build().write(later);
        try (SegmentWriter writer = SegmentWriter.open(SegmentWriter.partPathFor(later.resolve(SEGMENT_000)),
                SAMPLE_RATE, MONO, BIT_DEPTH, CADENCE, System::nanoTime)) {
            writer.append(ramp(MONO, 10), MONO, 10);
        }
        manifestFor(sealedTake).sealed(SealedBy.STOP).sealedAt(Instant.now()).build().write(sealedTake);

        List<RecoveredTake> recovered = TakeRecovery.scan(takesDir);

        assertThat(recovered).extracting(RecoveredTake::takeDirectory).containsExactly(takeDir, later);
        assertThat(TakeManifest.read(TakeManifest.manifestPath(later)).segments()).containsExactly(
                new SegmentEntry(TRACK, 0, 0, SEGMENT_000, 10, SegmentState.SEALED));
        assertThat(TakeManifest.read(TakeManifest.manifestPath(sealedTake)).sealedBy()).contains(SealedBy.STOP);
    }

    @Test
    void aLeftoverPartUnderAnAbortedManifestIsSealedAndTheFoundReasonIsKept() throws IOException {
        // A device-loss rescue whose second lane threw at its rename: the flush
        // thread wrote aborted/write-failure and left that lane's .part for recovery.
        Instant flushThreadSeal = Instant.parse("2026-08-03T14:25:05Z");
        sealedByWriter(SEGMENT_000, FRAMES);
        Path part = abandonedPart(SEGMENT_001, MONO, FRAMES);
        manifestFor(takeDir, sealed(SEGMENT_000, 0, FRAMES), streaming(SEGMENT_001, 1))
                .aborted(SealedBy.WRITE_FAILURE).sealedAt(flushThreadSeal).build().write(takeDir);
        Instant before = Instant.now();

        List<RecoveredTake> recovered = TakeRecovery.scan(takesDir);

        assertThat(recovered).hasSize(1);
        assertThat(recovered.getFirst().sealedSegments()).containsExactly(sealed(SEGMENT_001, 1, FRAMES));
        assertThat(recovered.getFirst().unrecoverable()).isEmpty();
        assertThat(part).doesNotExist();
        assertDecodedRamp(SegmentFile.readFrames(takeDir.resolve(SEGMENT_001))[0], 0, FRAMES);
        TakeManifest manifest = manifestOnDisk();
        assertThat(manifest.sealStatus()).isEqualTo(SealStatus.ABORTED);
        assertThat(manifest.sealedBy()).as("the reason the flush thread found is kept; crash-recovery is the"
                + " scan's own reason only for a take that was still streaming").contains(SealedBy.WRITE_FAILURE);
        assertThat(manifest.sealedAt().orElseThrow()).as("sealed-at is kept: the flush thread ended this take, not"
                + " the scan, which refreshes sealed-at only for a take it ended itself").isEqualTo(flushThreadSeal);
        assertThat(before).as("premise: a refreshed sealed-at would be later than the one kept").isAfter(flushThreadSeal);
        assertThat(manifest.segments()).containsExactly(sealed(SEGMENT_000, 0, FRAMES), sealed(SEGMENT_001, 1, FRAMES));
        assertThat(Files.readString(TakeManifest.manifestPath(takeDir))).contains("sealed-by=write-failure\n");

        assertThat(TakeRecovery.scan(takesDir)).as("every entry is sealed now: no longer a candidate").isEmpty();
    }

    @Test
    void aRenameTheManifestNeverRecordedIsRecordedByTheNextScan() throws IOException {
        // The .part was renamed to .wav but the manifest write that should have
        // followed failed, so the aborted manifest still lists the entry streaming.
        Path wav = sealedByWriter(SEGMENT_000, FRAMES);
        manifestFor(takeDir, streaming(SEGMENT_000, 0))
                .aborted(SealedBy.DEVICE_LOSS).sealedAt(Instant.parse("2026-08-03T14:25:05Z")).build().write(takeDir);
        assertThat(SegmentWriter.partPathFor(wav)).doesNotExist();
        byte[] wavBytes = Files.readAllBytes(wav);

        List<RecoveredTake> recovered = TakeRecovery.scan(takesDir);

        assertThat(recovered).hasSize(1);
        assertThat(recovered.getFirst().sealedSegments()).containsExactly(sealed(SEGMENT_000, 0, FRAMES));
        assertThat(recovered.getFirst().unrecoverable()).isEmpty();
        assertThat(Files.readAllBytes(wav)).as("the sealed file is only read").isEqualTo(wavBytes);
        TakeManifest manifest = manifestOnDisk();
        assertThat(manifest.sealedBy()).contains(SealedBy.DEVICE_LOSS);
        assertThat(manifest.segments()).containsExactly(sealed(SEGMENT_000, 0, FRAMES));

        assertThat(TakeRecovery.scan(takesDir)).isEmpty();
        assertThat(TakeRecovery.recover(takeDir)).isEmpty();
    }

    @Test
    void anEmptyStandbyPartIsDeletedAndItsEntryDropped() throws IOException {
        // A crash during loop-record: lane 0's segment has frames, the standby
        // for the next lap holds only the 44-byte header.
        manifestFor(takeDir, streaming(SEGMENT_000, 0), streaming(SEGMENT_001, 1)).build().write(takeDir);
        abandonedPart(SEGMENT_000, MONO, FRAMES);
        Path standby = abandonedPart(SEGMENT_001, MONO, 0);
        assertThat(Files.size(standby)).as("fixture: the standby is a bare header").isEqualTo(SegmentFile.HEADER_BYTES);

        List<RecoveredTake> recovered = TakeRecovery.scan(takesDir);

        assertThat(recovered).hasSize(1);
        RecoveredTake take = recovered.getFirst();
        assertThat(take.sealedSegments()).containsExactly(sealed(SEGMENT_000, 0, FRAMES));
        assertThat(take.playableSegments()).containsExactly(sealed(SEGMENT_000, 0, FRAMES));
        assertThat(take.unrecoverable()).isEmpty();
        assertThat(standby).as("deleted, as the live seal deletes an empty tail").doesNotExist();
        assertThat(takeDir.resolve(SEGMENT_001)).as("never sealed into a zero-frame segment").doesNotExist();
        TakeManifest manifest = manifestOnDisk();
        assertThat(manifest.sealedBy()).contains(SealedBy.CRASH_RECOVERY);
        assertThat(manifest.segments()).as("the entry is removed, as removeSegment does at the live seal")
                .containsExactly(sealed(SEGMENT_000, 0, FRAMES));
        assertThat(TakeRecovery.scan(takesDir)).isEmpty();
    }

    @Test
    void aTakeWhoseOnlyStreamingEntryWasAnEmptyStandbyIsRecoveredWithNoSegments() throws IOException {
        manifestFor(takeDir, streaming(SEGMENT_000, 0)).build().write(takeDir);
        Path standby = abandonedPart(SEGMENT_000, MONO, 0);
        Instant before = Instant.now();

        List<RecoveredTake> recovered = TakeRecovery.scan(takesDir);

        assertThat(recovered).hasSize(1);
        RecoveredTake take = recovered.getFirst();
        assertThat(take.sealedSegments()).isEmpty();
        assertThat(take.playableSegments()).isEmpty();
        assertThat(take.unrecoverable()).isEmpty();
        assertThat(standby).doesNotExist();
        assertThat(takeDir.resolve(SEGMENT_000)).doesNotExist();
        // The manifest reads aborted/crash-recovery with sealed-at and no segment line at all.
        TakeManifest manifest = manifestOnDisk();
        assertThat(manifest.sealStatus()).isEqualTo(SealStatus.ABORTED);
        assertThat(manifest.sealedBy()).contains(SealedBy.CRASH_RECOVERY);
        assertThat(manifest.sealedAt().orElseThrow()).isBetween(before, Instant.now());
        assertThat(manifest.segments()).isEmpty();
        assertThat(Files.readString(TakeManifest.manifestPath(takeDir))).doesNotContain("segment=");
        assertThat(TakeRecovery.scan(takesDir)).isEmpty();
    }

    @Test
    void aZeroFrameOrphanUnderASealedTakeIsDeletedAndTheTakeIsNoCandidate() throws IOException {
        // A take that stopped cleanly, whose standby .part the live seal could not delete.
        Path wav = sealedByWriter(SEGMENT_000, FRAMES);
        manifestFor(takeDir, sealed(SEGMENT_000, 0, FRAMES))
                .sealed(SealedBy.STOP).sealedAt(Instant.parse("2026-08-03T14:25:05Z")).build().write(takeDir);
        Path orphan = abandonedPart(SEGMENT_001, MONO, 0);
        assertThat(Files.size(orphan)).as("fixture: the orphan is a bare header").isEqualTo(SegmentFile.HEADER_BYTES);
        byte[] wavBytes = Files.readAllBytes(wav);
        byte[] manifestBytes = manifestBytesOnDisk();

        List<String> warnings = warningsLoggedBy(() -> assertThat(TakeRecovery.scan(takesDir)).isEmpty());

        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst()).contains(orphan.toString(), "deleted").doesNotContain("untouched");
        assertThat(orphan).as("it holds no frame and no manifest lists it: deleted, as the live seal meant to")
                .doesNotExist();
        assertThat(takeDir.resolve(SEGMENT_001)).as("never sealed into a segment").doesNotExist();
        assertThat(Files.readAllBytes(wav)).isEqualTo(wavBytes);
        assertThat(manifestBytesOnDisk()).as("a sealed take is rewritten by no scan").isEqualTo(manifestBytes);
        assertThat(TakeRecovery.recover(takeDir)).isEmpty();
    }

    @Test
    void anOrphanWithFramesUnderASealedTakeIsLoggedAndLeftAndTheTakeIsNoCandidate() throws IOException {
        sealedByWriter(SEGMENT_000, FRAMES);
        manifestFor(takeDir, sealed(SEGMENT_000, 0, FRAMES))
                .sealed(SealedBy.STOP).sealedAt(Instant.parse("2026-08-03T14:25:05Z")).build().write(takeDir);
        Path orphan = abandonedPart(SEGMENT_001, MONO, FRAMES);
        byte[] orphanBytes = Files.readAllBytes(orphan);
        byte[] manifestBytes = manifestBytesOnDisk();

        List<String> warnings = warningsLoggedBy(() -> assertThat(TakeRecovery.scan(takesDir)).isEmpty());

        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst()).contains(orphan.toString(), "untouched", "does not list it");
        assertThat(TakeRecovery.recover(takeDir)).isEmpty();
        assertThat(Files.readAllBytes(orphan)).as("its frames are real and its lane unknown: left as it is")
                .isEqualTo(orphanBytes);
        assertThat(manifestBytesOnDisk()).isEqualTo(manifestBytes);
    }

    @Test
    void aManifestWriteThatFailsLeavesTheEmptyStandbyOnDiskForTheNextScanToDiscard() throws IOException {
        manifestFor(takeDir, streaming(SEGMENT_000, 0)).build().write(takeDir);
        Path standby = abandonedPart(SEGMENT_000, MONO, 0);
        byte[] standbyBytes = Files.readAllBytes(standby);
        byte[] manifestBytes = manifestBytesOnDisk();
        Path staging = refuseTheManifestWrite();

        assertThatThrownBy(() -> TakeRecovery.recover(takeDir)).isInstanceOf(IOException.class);

        assertThat(standby).as("never deleted before the manifest that drops its entry is on disk").isRegularFile();
        assertThat(Files.readAllBytes(standby)).isEqualTo(standbyBytes);
        assertThat(manifestBytesOnDisk()).as("the failed write left the manifest it found").isEqualTo(manifestBytes);
        assertThat(manifestOnDisk().segments()).as("still listed, still streaming: no later scan reports a"
                + " segment with neither file").containsExactly(streaming(SEGMENT_000, 0));

        allowTheManifestWriteAgain(staging);
        List<RecoveredTake> recovered = TakeRecovery.scan(takesDir);

        assertThat(recovered).hasSize(1);
        assertThat(recovered.getFirst().sealedSegments()).isEmpty();
        assertThat(recovered.getFirst().unrecoverable()).isEmpty();
        assertThat(standby).as("discarded by the scan whose write landed").doesNotExist();
        TakeManifest manifest = manifestOnDisk();
        assertThat(manifest.sealedBy()).contains(SealedBy.CRASH_RECOVERY);
        assertThat(manifest.segments()).isEmpty();
        assertThat(TakeRecovery.scan(takesDir)).isEmpty();
    }

    @Test
    void aZeroFramePartLeftBetweenTheManifestWriteAndItsDeleteIsDeletedByTheNextScan() throws IOException {
        // A scan wrote the manifest without the standby's entry and died
        // before deleting the file: aborted/crash-recovery, every entry
        // sealed, and an unlisted bare header in the track directory.
        Path wav = sealedByWriter(SEGMENT_000, FRAMES);
        manifestFor(takeDir, sealed(SEGMENT_000, 0, FRAMES))
                .aborted(SealedBy.CRASH_RECOVERY).sealedAt(Instant.parse("2026-08-03T14:25:05Z")).build()
                .write(takeDir);
        Path standby = abandonedPart(SEGMENT_001, MONO, 0);
        byte[] wavBytes = Files.readAllBytes(wav);
        byte[] manifestBytes = manifestBytesOnDisk();

        List<String> warnings = warningsLoggedBy(() -> assertThat(TakeRecovery.scan(takesDir)).isEmpty());

        assertThat(standby).as("the delete the crash cut short is done by this scan").doesNotExist();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst()).contains(standby.toString(), "deleted");
        assertThat(TakeRecovery.recover(takeDir)).as("not a candidate: nothing is registered for a cleanup")
                .isEmpty();
        assertThat(Files.readAllBytes(wav)).isEqualTo(wavBytes);
        assertThat(manifestBytesOnDisk()).isEqualTo(manifestBytes);
    }

    @Test
    void anUnlistedZeroFramePartUnderACandidateIsDeletedAndNotReported() throws IOException {
        manifestFor(takeDir, streaming(SEGMENT_000, 0)).build().write(takeDir);
        abandonedPart(SEGMENT_000, MONO, FRAMES);
        // The standby the live seal could not delete, under a take the JVM then died in.
        Path orphan = abandonedPart(SEGMENT_001, MONO, 0);

        AtomicReference<List<RecoveredTake>> recovered = new AtomicReference<>();
        List<String> warnings = warningsLoggedBy(() -> recovered.set(TakeRecovery.scan(takesDir)));

        assertThat(recovered.get()).hasSize(1);
        RecoveredTake take = recovered.get().getFirst();
        assertThat(take.sealedSegments()).containsExactly(sealed(SEGMENT_000, 0, FRAMES));
        assertThat(take.unrecoverable()).as("deleted, not reported").isEmpty();
        assertThat(orphan).doesNotExist();
        assertThat(takeDir.resolve(SEGMENT_001)).doesNotExist();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst()).contains(orphan.toString(), "deleted");
        assertThat(manifestOnDisk().segments()).as("no entry is invented for it")
                .containsExactly(sealed(SEGMENT_000, 0, FRAMES));
        assertThat(TakeRecovery.scan(takesDir)).isEmpty();
    }

    @Test
    void anUnlistedPartShorterThanTheHeaderIsNotZeroFrameAndIsReportedAndLeft() throws IOException {
        manifestFor(takeDir, streaming(SEGMENT_000, 0)).build().write(takeDir);
        abandonedPart(SEGMENT_000, MONO, FRAMES);
        Path stub = SegmentWriter.partPathFor(takeDir.resolve(SEGMENT_001));
        byte[] stubBytes = "RIFF\u0000\u0000\u0000\u0000WAVE".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        Files.write(stub, stubBytes);

        List<RecoveredTake> recovered = TakeRecovery.scan(takesDir);

        assertThat(recovered).hasSize(1);
        assertThat(recovered.getFirst().unrecoverable()).extracting(Unrecoverable::file).containsExactly(stub);
        assertThat(recovered.getFirst().unrecoverable().getFirst().reason()).contains("not listed");
        assertThat(Files.readAllBytes(stub)).as("too short to parse: not provably zero-frame, so left")
                .isEqualTo(stubBytes);
        assertThat(manifestOnDisk().segments()).containsExactly(sealed(SEGMENT_000, 0, FRAMES));
    }

    @Test
    void anUnlistedZeroFramePartOfAnotherFormatIsNotZeroFrameAndIsReportedAndLeft() throws IOException {
        manifestFor(takeDir, streaming(SEGMENT_000, 0)).build().write(takeDir);
        abandonedPart(SEGMENT_000, MONO, FRAMES);
        // A bare header, but at 24 bits where the manifest says 16: not this take's file.
        Path foreign = SegmentWriter.partPathFor(takeDir.resolve(SEGMENT_001));
        try (SegmentWriter _ = SegmentWriter.open(foreign, SAMPLE_RATE, MONO, 24, CADENCE, System::nanoTime)) {
            // The open writes the header; nothing is appended.
        }
        byte[] foreignBytes = Files.readAllBytes(foreign);
        assertThat(foreignBytes).hasSize(SegmentFile.HEADER_BYTES);

        List<RecoveredTake> recovered = TakeRecovery.scan(takesDir);

        assertThat(recovered).hasSize(1);
        assertThat(recovered.getFirst().unrecoverable()).extracting(Unrecoverable::file).containsExactly(foreign);
        assertThat(recovered.getFirst().unrecoverable().getFirst().reason()).contains("not listed");
        assertThat(Files.readAllBytes(foreign)).isEqualTo(foreignBytes);
    }

    @Test
    void anUnlistedPartWhoseHeaderClaimsMoreChannelsThanItsTrackIsNotZeroFrameAndIsLoggedAndLeftUnderASealedTake()
            throws IOException {
        Path wav = sealedByWriter(SEGMENT_000, FRAMES);
        manifestFor(takeDir, sealed(SEGMENT_000, 0, FRAMES))
                .sealed(SealedBy.STOP).sealedAt(Instant.parse("2026-08-03T14:25:05Z")).build().write(takeDir);
        // One whole mono frame behind a header claiming stereo: by the header's
        // frame size the file holds no frame; by the track's, it holds one.
        Path orphan = partWhoseHeaderClaimsChannels(SEGMENT_001, 2, 1);
        byte[] orphanBytes = Files.readAllBytes(orphan);
        byte[] wavBytes = Files.readAllBytes(wav);
        byte[] manifestBytes = manifestBytesOnDisk();

        List<String> warnings = warningsLoggedBy(() -> assertThat(TakeRecovery.scan(takesDir)).isEmpty());

        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst()).contains(orphan.toString(), "untouched", "does not list it",
                "not provably zero-frame", "2 channel(s)", "lists 1");
        assertThat(Files.readAllBytes(orphan)).as("the listed-entry rule refuses this disagreement, and so does"
                + " the unlisted one: never deleted").isEqualTo(orphanBytes);
        assertThat(Files.readAllBytes(wav)).isEqualTo(wavBytes);
        assertThat(manifestBytesOnDisk()).isEqualTo(manifestBytes);
        assertThat(TakeRecovery.recover(takeDir)).isEmpty();
    }

    @Test
    void anUnlistedPartWhoseHeaderClaimsMoreChannelsThanItsTrackIsNotZeroFrameAndIsReportedAndLeftUnderACandidate()
            throws IOException {
        manifestFor(takeDir, streaming(SEGMENT_000, 0)).build().write(takeDir);
        abandonedPart(SEGMENT_000, MONO, FRAMES);
        Path orphan = partWhoseHeaderClaimsChannels(SEGMENT_001, 2, 1);
        byte[] orphanBytes = Files.readAllBytes(orphan);

        AtomicReference<List<RecoveredTake>> recovered = new AtomicReference<>();
        List<String> warnings = warningsLoggedBy(() -> recovered.set(TakeRecovery.scan(takesDir)));

        assertThat(recovered.get()).hasSize(1);
        RecoveredTake take = recovered.get().getFirst();
        assertThat(take.sealedSegments()).containsExactly(sealed(SEGMENT_000, 0, FRAMES));
        assertThat(take.unrecoverable()).extracting(Unrecoverable::file).containsExactly(orphan);
        assertThat(take.unrecoverable().getFirst().reason())
                .contains("not listed", "not provably zero-frame", "2 channel(s)", "lists 1");
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst()).contains(orphan.toString(), "untouched", "2 channel(s)");
        assertThat(Files.readAllBytes(orphan)).isEqualTo(orphanBytes);
        assertThat(takeDir.resolve(SEGMENT_001)).doesNotExist();
        assertThat(manifestOnDisk().segments()).as("no entry is invented for it")
                .containsExactly(sealed(SEGMENT_000, 0, FRAMES));
        // The next scan finds it under a take that is no candidate, and refuses it the same way.
        assertThat(TakeRecovery.scan(takesDir)).isEmpty();
        assertThat(Files.readAllBytes(orphan)).isEqualTo(orphanBytes);
    }

    @Test
    void anUnlistedZeroFramePartUnderADirectoryNamingNoListedTrackIsNotZeroFrameAndIsReportedAndLeft()
            throws IOException {
        manifestFor(takeDir, streaming(SEGMENT_000, 0)).build().write(takeDir);
        abandonedPart(SEGMENT_000, MONO, FRAMES);
        // A bare header of the manifest's own format, under a track the manifest does not list.
        Path stray = SegmentWriter.partPathFor(takeDir.resolve("guitar/segment-000.wav"));
        try (SegmentWriter _ = SegmentWriter.open(stray, SAMPLE_RATE, MONO, BIT_DEPTH, CADENCE, System::nanoTime)) {
            // The open writes the header; nothing is appended.
        }
        byte[] strayBytes = Files.readAllBytes(stray);
        assertThat(strayBytes).hasSize(SegmentFile.HEADER_BYTES);

        AtomicReference<List<RecoveredTake>> recovered = new AtomicReference<>();
        List<String> warnings = warningsLoggedBy(() -> recovered.set(TakeRecovery.scan(takesDir)));

        assertThat(recovered.get()).hasSize(1);
        RecoveredTake take = recovered.get().getFirst();
        assertThat(take.sealedSegments()).containsExactly(sealed(SEGMENT_000, 0, FRAMES));
        assertThat(take.unrecoverable()).extracting(Unrecoverable::file).containsExactly(stray);
        assertThat(take.unrecoverable().getFirst().reason())
                .contains("not listed", "not provably zero-frame", "guitar", "names no track");
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst()).contains(stray.toString(), "untouched", "names no track");
        assertThat(Files.readAllBytes(stray)).as("no track to check its channel count against: not provably this"
                + " take's file, so left").isEqualTo(strayBytes);
        assertThat(manifestOnDisk().segments()).containsExactly(sealed(SEGMENT_000, 0, FRAMES));
    }

    @Test
    void anEmptyStandbyWhoseDeleteIsRefusedAfterTheWriteIsReportedWithTheManifestStandingAndDeletedByTheNextScan()
            throws Exception {
        manifestFor(takeDir, streaming(SEGMENT_000, 0)).build().write(takeDir);
        Path standby = abandonedPart(SEGMENT_000, MONO, 0);
        byte[] standbyBytes = Files.readAllBytes(standby);
        Instant before = Instant.now();

        AtomicReference<List<RecoveredTake>> recovered = new AtomicReference<>();
        List<String> warnings;
        try (AutoCloseable _ = holdUndeletable(standby)) {
            assumeTrue(catchThrowable(() -> Files.delete(standby)) != null,
                    "this platform deletes the held file; the refused delete cannot be staged here");
            warnings = warningsLoggedBy(() -> recovered.set(TakeRecovery.scan(takesDir)));
        }

        assertThat(recovered.get()).as("a refused delete after the write ends no take's recovery").hasSize(1);
        RecoveredTake take = recovered.get().getFirst();
        assertThat(take.sealedSegments()).isEmpty();
        assertThat(take.unrecoverable()).extracting(Unrecoverable::file).containsExactly(standby);
        assertThat(take.unrecoverable().getFirst().reason())
                .contains("holds no frame", "does not list it", "could not be deleted", "next scan");
        assertThat(standby).isRegularFile();
        assertThat(Files.readAllBytes(standby)).isEqualTo(standbyBytes);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst()).contains(standby.toString(), "could not be deleted");
        TakeManifest manifest = manifestOnDisk();
        assertThat(manifest).as("the manifest the scan wrote stands").isEqualTo(take.manifest());
        assertThat(manifest.sealStatus()).isEqualTo(SealStatus.ABORTED);
        assertThat(manifest.sealedBy()).contains(SealedBy.CRASH_RECOVERY);
        assertThat(manifest.sealedAt().orElseThrow()).isBetween(before, Instant.now());
        assertThat(manifest.segments()).as("the entry is dropped: the file holds no frame").isEmpty();
        byte[] manifestBytes = manifestBytesOnDisk();

        // The hold is released: the next scan finds an unlisted zero-frame
        // .part under a take that is no candidate, and deletes it.
        List<String> again = warningsLoggedBy(() -> assertThat(TakeRecovery.scan(takesDir)).isEmpty());

        assertThat(again).hasSize(1);
        assertThat(again.getFirst()).contains(standby.toString(), "deleted");
        assertThat(standby).doesNotExist();
        assertThat(manifestBytesOnDisk()).isEqualTo(manifestBytes);
    }

    @Test
    void aRuntimeFailureInOneTakeDoesNotAbortTheScanOfTheNext() throws IOException {
        Path audioDir = projectDir.resolve("audio");
        Path later = TakeDirectories.allocate(audioDir, Instant.parse("2026-08-03T15:00:00Z"));
        manifestFor(takeDir, streaming(SEGMENT_000, 0)).build().write(takeDir);
        Path part = abandonedPart(SEGMENT_000, MONO, FRAMES);
        manifestFor(later, streaming(SEGMENT_000, 0)).build().write(later);
        try (SegmentWriter writer = SegmentWriter.open(SegmentWriter.partPathFor(later.resolve(SEGMENT_000)),
                SAMPLE_RATE, MONO, BIT_DEPTH, CADENCE, System::nanoTime)) {
            writer.append(ramp(MONO, 10), MONO, 10);
        }
        byte[] partBytes = Files.readAllBytes(part);
        byte[] manifestBytes = Files.readAllBytes(TakeManifest.manifestPath(takeDir));
        TakeRecovery.setRecoverFaultForTest(dir -> {
            if (dir.equals(takeDir)) {
                throw new IllegalStateException("injected");
            }
        });

        AtomicReference<List<RecoveredTake>> recovered = new AtomicReference<>();
        List<String> warnings = warningsLoggedBy(() -> recovered.set(TakeRecovery.scan(takesDir)));

        assertThat(recovered.get()).extracting(RecoveredTake::takeDirectory).containsExactly(later);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst()).contains("skipped", takeDir.toString(), "injected");
        assertThat(Files.readAllBytes(part)).isEqualTo(partBytes);
        assertThat(Files.readAllBytes(TakeManifest.manifestPath(takeDir))).isEqualTo(manifestBytes);

        TakeRecovery.setRecoverFaultForTest(null);
        assertThat(TakeRecovery.scan(takesDir)).extracting(RecoveredTake::takeDirectory).containsExactly(takeDir);
    }

    @Test
    void anExceptionWithoutAMessageIsReportedByItsClassName() {
        assertThat(TakeRecovery.reasonOf(new ClosedChannelException())).isEqualTo("ClosedChannelException");
        assertThat(TakeRecovery.reasonOf(new IOException("   "))).isEqualTo("IOException");
        assertThat(TakeRecovery.reasonOf(new IOException("disk gone"))).isEqualTo("disk gone");
        assertThat(new Unrecoverable(takeDir, TakeRecovery.reasonOf(new ClosedChannelException())).reason())
                .isEqualTo("ClosedChannelException");
    }
}
