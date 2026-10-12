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

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
 * tail; a sealed take with an unlisted orphan is no candidate; one take's
 * failure never hides the next.
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
    void aSealedTakeWithAnUnlistedOrphanPartIsNotACandidate() throws IOException {
        // A take that stopped cleanly, whose standby .part the live seal could not delete.
        sealedByWriter(SEGMENT_000, FRAMES);
        manifestFor(takeDir, sealed(SEGMENT_000, 0, FRAMES))
                .sealed(SealedBy.STOP).sealedAt(Instant.parse("2026-08-03T14:25:05Z")).build().write(takeDir);
        Path orphan = abandonedPart(SEGMENT_001, MONO, 0);
        byte[] orphanBytes = Files.readAllBytes(orphan);
        byte[] manifestBytes = Files.readAllBytes(TakeManifest.manifestPath(takeDir));

        List<String> warnings = warningsLoggedBy(() -> assertThat(TakeRecovery.scan(takesDir)).isEmpty());

        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst()).contains(orphan.toString(), "does not list it");
        assertThat(TakeRecovery.recover(takeDir)).isEmpty();
        assertThat(Files.readAllBytes(orphan)).as("left as it is").isEqualTo(orphanBytes);
        assertThat(Files.readAllBytes(TakeManifest.manifestPath(takeDir))).isEqualTo(manifestBytes);
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
