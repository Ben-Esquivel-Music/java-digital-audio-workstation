package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SealStatus;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SegmentEntry;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SegmentState;
import com.benesquivelmusic.daw.core.recording.TakeManifest.TrackEntry;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.BLOCK_FRAMES;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.MONO_16;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.SAMPLE_RATE;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.decodedRampValue;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.feedRamp;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.hasProvisionalSizes;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.sizeFields;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recovery of a take its writer never sealed because the recording JVM was
 * killed (story 323; Recording Reliability book §2.5, §3.4, §8 Stage 1
 * proof (1)).
 *
 * <p>The first test starts {@link EndlessRampRecorder} in a child JVM — the
 * parent's own JDK ({@code java.home}), the build's classes on the class path
 * — which records the deterministic ramp through a real
 * {@link RecordingPipeline} into {@code <project>/audio/takes/<take>/} under
 * 3 MiB segments and reports the frames confirmed flushed after every fence
 * on its standard output, which the test sends to a file: on Linux
 * {@link Process#destroyForcibly()} closes the parent's end of a stdout pipe
 * right after the kill, and a line still unread in the pipe would be lost
 * with it; the file keeps every line the child wrote. The test follows the
 * file, and once it reports two minutes of audio (three sealed rotations and
 * part of a fourth segment), kills the child with
 * {@link Process#destroyForcibly()} — {@code TerminateProcess} on Windows,
 * {@code SIGKILL} on Linux — while it is still feeding blocks, so no seal, no
 * final manifest write and no close from Java runs in the child. With the
 * child dead, the test reads the file whole, and the take is recovered with
 * nothing but {@link SegmentFile} and {@link TakeManifest#read}: the recovered
 * frames, in segment-index order, are the ramp, at least every frame the
 * child reported flushed and at most one fence interval more.</p>
 *
 * <p>What this proves is durability across the death of the process: bytes
 * the child had handed to the operating system sit in its page cache, and
 * the files hold them once the process is gone. It proves nothing about a
 * power loss or an operating-system crash; that exposure is bounded by the
 * force cadence (book §4.3), which the force-cadence tests pin.</p>
 *
 * <p>The other two tests build, without a kill, a manifest that lags its
 * files, by failing every attempt of the first rotation's manifest rewrite
 * under a take clock that never moves, so that no retry is ever due, and
 * recover the take with the same reader. One rotation behind is what a kill
 * between a seal's rename and the next manifest rewrite leaves — a short
 * window the first test's kill can fall into but seldom does; here the
 * segment the manifest does not name yet already holds frames. Two rotations
 * behind is what a manifest whose writes keep failing through a second
 * rotation leaves.</p>
 */
class TakeRecoveryAfterJvmKillContractTest {

    /** Two minutes at 48 kHz: the recorder is killed once it has reported this many frames flushed. */
    private static final long KILL_AFTER_FRAMES = (long) (2 * 60 * SAMPLE_RATE);

    /** The most frames the recorder can feed past its last reported fence: one fence interval. */
    private static final long FENCE_INTERVAL_FRAMES = (long) EndlessRampRecorder.FENCE_EVERY_BLOCKS * BLOCK_FRAMES;

    private static final int BLOCKS_PER_SEGMENT = (int) (EndlessRampRecorder.SEGMENT_FRAMES / BLOCK_FRAMES);

    /** Bound on the child's start and its two minutes of recording. */
    private static final Duration RECORDING_BOUND = Duration.ofMinutes(3);

    /** Bound on the child's exit after the kill. */
    private static final Duration EXIT_BOUND = Duration.ofSeconds(60);

    /** How often the test rereads the recorder's output file while it follows it. */
    private static final Duration FOLLOW_POLL = Duration.ofMillis(10);

    /** {@code segment-NNN.wav} or {@code segment-NNN.wav.part}: the index is the recovery order. */
    private static final Pattern SEGMENT_FILE_NAME = Pattern.compile("segment-(\\d{3,})\\.wav(\\.part)?");

    @TempDir
    Path projectDir;

    /** The child's working directory and its stdout and stderr files, outside the project. */
    @TempDir
    Path childDir;

    @Test
    @Timeout(value = 6, unit = TimeUnit.MINUTES)
    void killingTheRecordingJvmMidTakeLeavesEveryFlushedFrameRecoverableInOrder(TestReporter reporter)
            throws Exception {
        Path stdout = childDir.resolve("recorder-stdout.txt");
        Path stderr = childDir.resolve("recorder-stderr.txt");
        Process recorder = startRecorder(projectDir, childDir, stdout, stderr);
        // From here on, every path kills the recorder and waits for it to
        // exit before JUnit deletes the temporary directories.
        try {
            int exitStatus = killAtTheKillPoint(recorder, stdout);
            // destroyForcibly() is TerminateProcess(handle, 1) on Windows, and
            // SIGKILL elsewhere, which Process reports as 128 + 9.
            assertThat(exitStatus).as("exit status of the killed recorder").isEqualTo(isWindows() ? 1 : 128 + 9);

            // Everything below runs with the child dead: the file holds every
            // line it wrote, so its last complete progress line is its last report.
            RecorderReport report = RecorderReport.read(stdout);
            assertThat(report.overflowBlocks()).as("the fenced feed never overflowed the ring").isZero();
            Path takeDir = report.takeDirectory();
            assertThat(takeDir).as("the recorder reported its take directory").isNotNull();
            assertThat(takeDir.getParent()).isEqualTo(projectDir.resolve("audio").resolve("takes"));
            assertThat(TakeDirectories.isTakeDirectoryName(takeDir.getFileName().toString())).isTrue();
            assertThat(report.trackId()).as("the recorder reported its track id").isNotNull();

            Recovery recovery = recover(takeDir, report.trackId(), report.manifestWriteFailed());

            long flushed = report.flushedFrames();
            assertThat(recovery.firstMismatch()).as("first recovered frame that differs from the ramp").isEqualTo(-1);
            assertThat(recovery.frames())
                    .as("every frame reported flushed before the kill is recovered, and at most one fence"
                            + " interval more: the recorder feeds the next block only once its report is written")
                    .isGreaterThanOrEqualTo(flushed)
                    .isLessThanOrEqualTo(flushed + FENCE_INTERVAL_FRAMES);
            assertThat(recovery.sealedFiles()).as("at least three rotations sealed before the kill").isGreaterThanOrEqualTo(3);
            reporter.publishEntry("jvm-kill", String.format(Locale.ROOT,
                    "exit=%d reportedFlushed=%d recovered=%d segments=%d sealed=%d part=%s manifestSegments=%d"
                            + " manifestWriteFailed=%b",
                    exitStatus, flushed, recovery.frames(), recovery.files().size(), recovery.sealedFiles(),
                    recovery.part(), recovery.listed().size(), report.manifestWriteFailed()));
        } catch (AssertionError | IOException | RuntimeException failure) {
            stopForcibly(recorder);
            String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
            throw new AssertionError(message + System.lineSeparator() + transcript(recorder, stdout, stderr), failure);
        } finally {
            stopForcibly(recorder);
        }
    }

    @Test
    void aManifestOneRotationBehindItsSegmentsIsRecoveredInSegmentIndexOrder() throws IOException {
        int tailBlocks = 10;
        AbandonedTake take = recordPastAFailedRotationRewrite(BLOCKS_PER_SEGMENT + tailBlocks);

        Recovery recovery = recover(take.directory(), take.trackId(), take.manifestWriteFailed());
        assertThat(recovery.listed()).as("the manifest names only the first segment, still streaming")
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.index()).isZero();
                    assertThat(entry.state()).isEqualTo(SegmentState.STREAMING);
                });
        assertThat(recovery.files().keySet()).containsExactly(0, 1);
        assertThat(SegmentFile.isStreamingName(recovery.files().get(0))).as("the first segment is sealed").isFalse();
        assertThat(recovery.part()).as("the segment the manifest does not name yet").isEqualTo(PartState.STREAMING);
        assertThat(recovery.firstMismatch()).as("first recovered frame that differs from the ramp").isEqualTo(-1);
        assertThat(recovery.frames()).as("every fed frame, the tail the manifest does not name included")
                .isEqualTo(take.fed())
                .isEqualTo(EndlessRampRecorder.SEGMENT_FRAMES + (long) tailBlocks * BLOCK_FRAMES);
    }

    @Test
    void aManifestTwoRotationsBehindItsSegmentsIsRecoveredInSegmentIndexOrder() throws IOException {
        int tailBlocks = 10;
        AbandonedTake take = recordPastAFailedRotationRewrite(2 * BLOCKS_PER_SEGMENT + tailBlocks);

        assertThat(take.warnings()).as("fixture: the manifest failure was reported once")
                .filteredOn(TakeRecoveryAfterJvmKillContractTest::reportsAFailedManifestWrite)
                .hasSize(1);
        Recovery recovery = recover(take.directory(), take.trackId(), take.manifestWriteFailed());
        assertThat(recovery.listed()).as("the manifest names only the first segment, still streaming")
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.index()).isZero();
                    assertThat(entry.state()).isEqualTo(SegmentState.STREAMING);
                });
        assertThat(recovery.files().keySet()).as("two rotations ahead of the manifest").containsExactly(0, 1, 2);
        assertThat(recovery.sealedFiles()).as("the segments both rotations sealed").isEqualTo(2);
        assertThat(recovery.part()).as("the newest segment").isEqualTo(PartState.STREAMING);
        assertThat(recovery.firstMismatch()).as("first recovered frame that differs from the ramp").isEqualTo(-1);
        assertThat(recovery.frames()).as("every fed frame, the two segments the manifest does not name included")
                .isEqualTo(take.fed())
                .isEqualTo(2 * EndlessRampRecorder.SEGMENT_FRAMES + (long) tailBlocks * BLOCK_FRAMES);
    }

    /**
     * A take abandoned without a seal, its manifest left behind its files.
     *
     * @param directory the take directory
     * @param trackId   the one track's id
     * @param fed       the frames fed through the recording callback
     * @param warnings  every warning the pipeline gave its sink
     */
    private record AbandonedTake(Path directory, String trackId, long fed, List<String> warnings) {

        /** Whether a warning reports a manifest write that failed on every attempt. */
        boolean manifestWriteFailed() {
            return warnings.stream().anyMatch(TakeRecoveryAfterJvmKillContractTest::reportsAFailedManifestWrite);
        }
    }

    /**
     * Records {@code blocks} ramp blocks under 3 MiB segments with every
     * attempt of the first rotation's manifest rewrite failing, on a take
     * clock that never moves — a manifest write that failed is retried only
     * once {@link CaptureFlushService#MANIFEST_RETRY_INTERVAL} of that clock
     * has passed, so no later write is attempted — then stops the flush
     * thread and abandons the writers without a seal.
     */
    private AbandonedTake recordPastAFailedRotationRewrite(int blocks) throws IOException {
        AudioEngine engine = new AudioEngine(MONO_16);
        Transport transport = new Transport();
        Track track = RampCaptureTestSupport.armedMonoTrack("Vocal");
        Path takeDir = TakeDirectories.allocate(ProjectManager.audioDirectory(projectDir), Instant.now());
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, MONO_16, takeDir, List.of(track));
        pipeline.setSegmentLimits(Duration.ofHours(1), EndlessRampRecorder.SEGMENT_BYTES);
        pipeline.setRingSlots(EndlessRampRecorder.RING_SLOTS);
        pipeline.setNanoClock(() -> 0L);
        List<String> warnings = new CopyOnWriteArrayList<>();
        pipeline.setWarningSink(warnings::add);
        pipeline.start();
        CaptureFlushService service = pipeline.getCaptureFlushService();
        // The first manifest write after the start is the rotation's
        // rewrite: every one of its attempts fails.
        service.failNextManifestWrites(CaptureFlushService.MANIFEST_WRITE_ATTEMPTS);

        long fed = feedRamp(engine, transport, pipeline, 0, blocks, EndlessRampRecorder.FENCE_EVERY_BLOCKS);
        service.stopAndAbandon();

        AbandonedTake take = new AbandonedTake(takeDir, track.getId(), fed, List.copyOf(warnings));
        assertThat(take.manifestWriteFailed()).as("fixture: the rotation's manifest rewrite failed").isTrue();
        return take;
    }

    /** Whether {@code warning} is the flush thread's report of a manifest write that failed on every attempt. */
    private static boolean reportsAFailedManifestWrite(String warning) {
        return warning.contains("take manifest") && warning.contains("could not be written");
    }

    /** The state of the one {@code .part} a track directory may hold. */
    private enum PartState {
        /** No {@code .part}: the last seal's rename has happened and the next segment has not been created. */
        NONE,
        /** Created, but shorter than the 44-byte header: no frame was ever appended to it. */
        HEADER_INCOMPLETE,
        /** Both size fields hold the streaming sentinel. */
        STREAMING,
        /** A seal had begun patching the size fields, exactly, and had not renamed the file yet. */
        PATCHED_BEFORE_RENAME
    }

    /**
     * One track of a take, as {@link #recover} read it back.
     *
     * @param listed        the manifest's segment entries for the track, in its order
     * @param files         the track directory's segment files by segment index
     * @param sealedFiles   how many of them are sealed {@code .wav} files
     * @param part          the state of the {@code .part}, or {@link PartState#NONE}
     * @param frames        the frames read from the files, in segment-index order
     * @param firstMismatch the first of those frames that is not the ramp, or {@code -1}
     */
    private record Recovery(List<SegmentEntry> listed, NavigableMap<Integer, Path> files, int sealedFiles,
                            PartState part, long frames, long firstMismatch) {
    }

    /**
     * Recovers one track of a take its writer never sealed — the manifest
     * through {@link TakeManifest#read}, the segment files the track
     * directory lists through {@link SegmentFile}; the headers' size fields
     * are also read raw, only to check them — and asserts what holds for
     * every take in this class: the manifest parses and still says
     * {@code streaming}; it names segments {@code 0..last} with every one but
     * the last sealed; the files on disk are {@code 0..newest} with
     * {@code newest} at least {@code last}; every one but the newest is a
     * sealed, full {@code .wav}, and the newest is one too or the only
     * {@code .part}. After a rotation the flush thread attempts the manifest
     * rewrite before it applies the next block, up to
     * {@link CaptureFlushService#MANIFEST_WRITE_ATTEMPTS} times; when every
     * attempt fails it warns its sink, once for a run of such failures, and
     * goes on applying blocks, retrying one attempt at most once per
     * {@link CaptureFlushService#MANIFEST_RETRY_INTERVAL} of the take's
     * clock, so the files can then run more than one rotation ahead. Unless
     * {@code manifestWriteFailed}, {@code newest} is therefore {@code last}
     * or one more, and a segment the manifest does not name yet is the
     * {@code .part}. Recovery takes the files in segment-index order — the
     * order the manifest gives the segments it names — so a segment the
     * manifest does not name yet is read after the one it names last.
     *
     * @param manifestWriteFailed whether the take's flush thread warned that
     *                            a manifest write failed on every attempt
     */
    private static Recovery recover(Path takeDir, String trackId, boolean manifestWriteFailed) throws IOException {
        // Each manifest write goes to a staging file, is forced, and is moved
        // over take.manifest: wherever a kill lands, take.manifest is whole.
        TakeManifest manifest = TakeManifest.read(TakeManifest.manifestPath(takeDir));
        assertThat(manifest.sealStatus()).as("the take was never sealed").isEqualTo(SealStatus.STREAMING);
        assertThat(manifest.sealedBy()).isEmpty();
        assertThat(manifest.tracks()).extracting(TrackEntry::trackId).containsExactly(trackId);
        assertThat(manifest.sampleRate()).isEqualTo(SAMPLE_RATE);
        assertThat(manifest.bitDepth()).isEqualTo(16);
        assertThat(manifest.streamChannels()).isEqualTo(1);
        List<String> takeEntries = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(takeDir)) {
            entries.forEach(entry -> takeEntries.add(entry.getFileName().toString()));
        }
        assertThat(takeEntries).as("the manifest, the track directory and at most the staging file of a"
                        + " manifest write that did not finish")
                .contains(TakeManifest.FILE_NAME, trackId)
                .isSubsetOf(TakeManifest.FILE_NAME, TakeManifest.FILE_NAME + TakeManifest.TMP_SUFFIX, trackId);

        List<SegmentEntry> listed = manifest.segmentsFor(trackId);
        assertThat(listed).as("the manifest names at least the first segment").isNotEmpty();
        int last = listed.size() - 1;
        for (int i = 0; i <= last; i++) {
            SegmentEntry entry = listed.get(i);
            assertThat(entry.lane()).isZero();
            assertThat(entry.index()).as("manifest entry %d", i).isEqualTo(i);
            assertThat(entry.state()).as("manifest entry %d", i)
                    .isEqualTo(i < last ? SegmentState.SEALED : SegmentState.STREAMING);
        }
        assertThat(listed.get(last).frames()).isEqualTo(TakeManifest.FRAMES_STREAMING);

        NavigableMap<Integer, Path> files = segmentFiles(takeDir.resolve(trackId));
        int newest = files.lastKey();
        assertThat(files.keySet()).as("segment indices without a gap")
                .containsExactlyElementsOf(IntStream.rangeClosed(0, newest).boxed().toList());
        // The manifest names a segment only once its file has been created.
        assertThat(newest).as("the files are never behind the manifest").isGreaterThanOrEqualTo(last);
        if (!manifestWriteFailed) {
            assertThat(newest).as("the files are at most one rotation ahead of a manifest whose every write landed")
                    .isLessThanOrEqualTo(last + 1);
            if (newest == last + 1) {
                assertThat(SegmentFile.isStreamingName(files.get(newest)))
                        .as("the segment the manifest does not name yet is streaming").isTrue();
            }
        }
        // RecordingSession rotates seal-then-open: the next .part is created
        // only after the previous one has been renamed to .wav. So every
        // segment but the newest is a .wav, and the newest is a .wav too —
        // a writer killed between a rename and the next open leaves no
        // .part, hence "at most one", not "exactly one" — or the only .part.
        for (int index = 0; index < newest; index++) {
            assertThat(SegmentFile.isStreamingName(files.get(index)))
                    .as("segment %d, which a later segment follows, has been sealed", index).isFalse();
        }
        for (SegmentEntry entry : listed) {
            Path file = files.get(entry.index());
            assertThat(sealedIdentityOf(file)).isEqualTo(entry.resolve(takeDir));
        }

        int sealedFiles = 0;
        for (Path file : files.values()) {
            if (SegmentFile.isStreamingName(file)) {
                continue;
            }
            sealedFiles++;
            // A file named as sealed is sealed; here every seal is a
            // rotation, which runs once the segment is full.
            assertThat(hasProvisionalSizes(file)).as("%s carries no streaming sentinel", file).isFalse();
            SegmentFile.Description description = SegmentFile.describe(file);
            assertThat(description.sealed()).isTrue();
            assertThat(description.frameCount()).as("%s is a full segment", file)
                    .isEqualTo(EndlessRampRecorder.SEGMENT_FRAMES);
        }
        for (SegmentEntry entry : listed) {
            if (entry.state() == SegmentState.SEALED) {
                assertThat(SegmentFile.describe(files.get(entry.index())).frameCount())
                        .as("segment %d holds exactly the frames its manifest entry lists", entry.index())
                        .isEqualTo(entry.frames());
            }
        }
        Path newestFile = files.get(newest);
        PartState part = SegmentFile.isStreamingName(newestFile) ? checkPart(newestFile) : PartState.NONE;

        long frames = 0;
        long firstMismatch = -1;
        for (Path file : files.values()) {
            if (Files.size(file) < SegmentFile.HEADER_BYTES) {
                continue; // only a HEADER_INCOMPLETE .part, checked above: it holds no frame
            }
            SegmentFile.Description description = SegmentFile.describe(file);
            assertThat(description.channels()).isEqualTo(1);
            assertThat(description.bitDepth()).isEqualTo(16);
            assertThat(description.sampleRate()).isEqualTo(SAMPLE_RATE);
            float[] samples = SegmentFile.readFrames(file)[0];
            for (int i = 0; i < samples.length && firstMismatch < 0; i++) {
                if (samples[i] != decodedRampValue(frames + i)) {
                    firstMismatch = frames + i;
                }
            }
            frames += samples.length;
        }
        return new Recovery(listed, files, sealedFiles, part, frames, firstMismatch);
    }

    /** The track directory's segment files by index; anything else there, or an index twice, fails. */
    private static NavigableMap<Integer, Path> segmentFiles(Path trackDir) throws IOException {
        NavigableMap<Integer, Path> byIndex = new TreeMap<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(trackDir)) {
            for (Path entry : entries) {
                Matcher name = SEGMENT_FILE_NAME.matcher(entry.getFileName().toString());
                assertThat(name.matches()).as("%s is a segment file", entry).isTrue();
                Path previous = byIndex.put(Integer.parseInt(name.group(1)), entry);
                assertThat(previous).as("segment %s is on disk once, as .wav or as .part", name.group(1)).isNull();
            }
        }
        assertThat(byIndex).as("the track directory holds segments").isNotEmpty();
        return byIndex;
    }

    /**
     * Checks the header of the one {@code .part}. Its frame count comes from
     * its length whatever the header says (the §3.4 grammar), so this only
     * pins which states a writer can leave behind. A seal forces the data,
     * writes the RIFF size, then the data size, forces again, closes and
     * renames: a kill between the RIFF-size write and the rename leaves a
     * {@code .part} whose patched fields are exact.
     */
    private static PartState checkPart(Path part) throws IOException {
        long length = Files.size(part);
        if (length < SegmentFile.HEADER_BYTES) {
            return PartState.HEADER_INCOMPLETE;
        }
        int[] fields = sizeFields(part);
        if (fields[0] == SegmentWriter.PROVISIONAL_SIZE && fields[1] == SegmentWriter.PROVISIONAL_SIZE) {
            return PartState.STREAMING;
        }
        assertThat(fields[0]).as("a patched RIFF size is exact").isEqualTo((int) (length - 8));
        assertThat(fields[1]).as("the data size is patched after the RIFF size, and exactly")
                .isIn(SegmentWriter.PROVISIONAL_SIZE, (int) (length - SegmentFile.HEADER_BYTES));
        assertThat(length - SegmentFile.HEADER_BYTES).as("only a rotation seals here, once the segment is full")
                .isEqualTo(EndlessRampRecorder.SEGMENT_BYTES);
        return PartState.PATCHED_BEFORE_RENAME;
    }

    private static Path sealedIdentityOf(Path segmentFile) {
        return SegmentFile.isStreamingName(segmentFile) ? SegmentWriter.sealedPathFor(segmentFile) : segmentFile;
    }

    /**
     * Starts {@link EndlessRampRecorder} on the parent's own JDK, with the
     * build's class directories and every module-path and class-path entry
     * of this JVM on its class path (the surefire fork runs daw-core on the
     * module path with the test classes patched in; the child needs none of
     * that and runs in the unnamed module), native access enabled for it and
     * this JVM's {@code java.library.path}. Its standard output goes to
     * {@code stdout} and its standard error to {@code stderr}, both files;
     * its standard input stays a pipe this JVM holds open, and never writes
     * to, until it has killed the recorder. The recorder needs no native
     * library: starting the engine only probes for FluidSynth, and a probe
     * that finds none reports it unavailable.
     */
    private static Process startRecorder(Path projectDir, Path workingDir, Path stdout, Path stderr)
            throws IOException, URISyntaxException {
        Set<String> classPath = new LinkedHashSet<>();
        classPath.add(classDirectoryOf(EndlessRampRecorder.class).toString());
        classPath.add(classDirectoryOf(RecordingPipeline.class).toString());
        addPathEntries(classPath, System.getProperty("jdk.module.path"));
        addPathEntries(classPath, System.getProperty("java.class.path"));
        List<String> command = List.of(
                javaLauncher().toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-Djava.library.path=" + System.getProperty("java.library.path", ""),
                "-Dstdout.encoding=UTF-8",
                "-Dstderr.encoding=UTF-8",
                "-cp", String.join(File.pathSeparator, classPath),
                EndlessRampRecorder.class.getName(),
                projectDir.toAbsolutePath().toString());
        return new ProcessBuilder(command)
                .directory(workingDir.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .start();
    }

    /** The {@code java} launcher of the JDK this JVM runs on — never one found on the PATH. */
    private static Path javaLauncher() {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        Path windows = bin.resolve("java.exe");
        Path launcher = Files.isRegularFile(windows) ? windows : bin.resolve("java");
        assertThat(launcher).as("the launcher of this JVM's own JDK").isRegularFile();
        return launcher;
    }

    /** The class directory {@code type} was loaded from; fails clearly unless it is one that holds it. */
    private static Path classDirectoryOf(Class<?> type) throws URISyntaxException {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        assertThat(source).as("%s was loaded from a code source", type.getName()).isNotNull();
        Path location = Path.of(source.getLocation().toURI());
        assertThat(location.resolve(type.getName().replace('.', '/') + ".class"))
                .as("%s was loaded from %s, which must be a class directory holding it", type.getName(), location)
                .isRegularFile();
        return location;
    }

    private static void addPathEntries(Set<String> classPath, String pathList) {
        if (pathList == null) {
            return;
        }
        for (String entry : pathList.split(Pattern.quote(File.pathSeparator))) {
            if (!entry.isBlank()) {
                classPath.add(entry);
            }
        }
    }

    /**
     * Follows the recorder's output file until it reports
     * {@link #KILL_AFTER_FRAMES} flushed, kills the recorder while it is
     * still feeding and waits for it to exit; every wait bounded.
     *
     * @return the killed recorder's exit status
     */
    private static int killAtTheKillPoint(Process recorder, Path stdout) throws IOException, InterruptedException {
        RecorderReport atTheKillPoint = RecorderReport.follow(recorder, stdout, KILL_AFTER_FRAMES, RECORDING_BOUND);
        assertThat(atTheKillPoint.flushedFrames())
                .as("frames the recorder reported flushed within %s, or before it ended", RECORDING_BOUND)
                .isGreaterThanOrEqualTo(KILL_AFTER_FRAMES);
        assertThat(recorder.isAlive()).as("the recorder is still streaming when it is killed").isTrue();
        recorder.destroyForcibly();
        // The files are touched only once the process is gone: Windows
        // releases the child's handles when it has exited.
        assertThat(recorder.waitFor(EXIT_BOUND.toMillis(), TimeUnit.MILLISECONDS))
                .as("the killed recorder exited within %s", EXIT_BOUND).isTrue();
        return recorder.exitValue();
    }

    /** Kills the recorder if it is still alive and waits, bounded, for it to exit. */
    private static void stopForcibly(Process recorder) throws InterruptedException {
        if (recorder.isAlive()) {
            recorder.destroyForcibly();
            recorder.waitFor(EXIT_BOUND.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /** The recorder's exit status and its stdout and stderr files, for a failure message. */
    private static String transcript(Process recorder, Path stdout, Path stderr) {
        StringBuilder text = new StringBuilder("recorder exit status: ")
                .append(recorder.isAlive() ? "none, still running" : String.valueOf(recorder.exitValue()))
                .append(System.lineSeparator());
        appendFile(text, "recorder stdout", stdout);
        appendFile(text, "recorder stderr", stderr);
        return text.toString();
    }

    private static void appendFile(StringBuilder text, String title, Path file) {
        text.append(title).append(':').append(System.lineSeparator());
        try {
            text.append(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (IOException e) {
            text.append("(not readable: ").append(e).append(')');
        }
        text.append(System.lineSeparator());
    }

    /**
     * What the recorder's standard output file says, read from its complete
     * lines only — those whose line break is in the file — so a line the
     * kill cut short reports nothing; a progress line must also end in
     * {@link EndlessRampRecorder#LINE_END}.
     *
     * @param takeDirectory       the reported take directory, or {@code null}
     * @param trackId             the reported track id, or {@code null}
     * @param flushedFrames       the frames of the last progress line; {@code -1} before the first
     * @param overflowBlocks      the overflow count of the last progress line; {@code -1} before the first
     * @param manifestWriteFailed whether a warning line reports a manifest write that failed on every
     *                            attempt. The flush thread writes that line, whole, before it applies
     *                            another block. So files more than one rotation ahead of the manifest —
     *                            all the flag excuses — come with the whole line; if the kill cut the
     *                            line short, the thread applied no block after the failed write, and the
     *                            files are at most one rotation ahead.
     */
    private record RecorderReport(Path takeDirectory, String trackId, long flushedFrames, long overflowBlocks,
                                  boolean manifestWriteFailed) {

        private static final Pattern PROGRESS = Pattern.compile(
                Pattern.quote(EndlessRampRecorder.FLUSHED_FRAMES) + "(\\d+) "
                        + Pattern.quote(EndlessRampRecorder.OVERFLOW_BLOCKS) + "(\\d+)"
                        + Pattern.quote(EndlessRampRecorder.LINE_END));

        /** Reads the file as it is now. */
        static RecorderReport read(Path stdout) throws IOException {
            String text = new String(Files.readAllBytes(stdout), StandardCharsets.UTF_8);
            List<String> lines = text.substring(0, text.lastIndexOf('\n') + 1).lines().toList();
            Path takeDirectory = null;
            String trackId = null;
            long flushedFrames = -1;
            long overflowBlocks = -1;
            boolean manifestWriteFailed = false;
            for (String line : lines) {
                if (line.startsWith(EndlessRampRecorder.TAKE_DIRECTORY)) {
                    takeDirectory = Path.of(line.substring(EndlessRampRecorder.TAKE_DIRECTORY.length()));
                } else if (line.startsWith(EndlessRampRecorder.TRACK_ID)) {
                    trackId = line.substring(EndlessRampRecorder.TRACK_ID.length());
                } else if (line.startsWith(EndlessRampRecorder.WARNING)) {
                    manifestWriteFailed |= reportsAFailedManifestWrite(
                            line.substring(EndlessRampRecorder.WARNING.length()));
                } else {
                    Matcher progress = PROGRESS.matcher(line);
                    if (progress.matches()) {
                        flushedFrames = Long.parseLong(progress.group(1));
                        overflowBlocks = Long.parseLong(progress.group(2));
                    }
                }
            }
            return new RecorderReport(takeDirectory, trackId, flushedFrames, overflowBlocks, manifestWriteFailed);
        }

        /**
         * Rereads the file every {@link #FOLLOW_POLL} until it reports
         * {@code killPoint} frames flushed, the recorder has exited, or
         * {@code bound} has run out, and returns what it read last. It never
         * waits on the recorder itself.
         */
        static RecorderReport follow(Process recorder, Path stdout, long killPoint, Duration bound)
                throws IOException, InterruptedException {
            long deadline = System.nanoTime() + bound.toNanos();
            while (true) {
                // Asked before the read: a recorder that had exited by then
                // has written everything it ever will.
                boolean exited = !recorder.isAlive();
                RecorderReport report = read(stdout);
                if (report.flushedFrames() >= killPoint || exited || System.nanoTime() - deadline >= 0) {
                    return report;
                }
                Thread.sleep(FOLLOW_POLL);
            }
        }
    }
}
