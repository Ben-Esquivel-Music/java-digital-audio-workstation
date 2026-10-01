package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.BLOCK_FRAMES;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.MONO_16;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.decodedRampValue;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.feedRamp;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.hasProvisionalSizes;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exact frame accounting for a take whose writers are abandoned without a
 * seal (story 323; book §2.5, §3.4): record about two minutes, stop the flush
 * thread at a pass boundary once the ring has been drained — no final sweep,
 * no seal — then close every writer from Java with no size patch and no
 * rename ({@link CaptureFlushService#stopAndAbandon()}), and recover every
 * written frame, exactly, from the sealed segments and the one streaming
 * {@code .part} using nothing but the §3.4 grammar ({@link SegmentFile}) and
 * the manifest's segment order. An orderly stop, not a process kill: the JVM
 * that wrote the files goes on running and closes them itself.
 * {@link TakeRecoveryAfterJvmKillContractTest} kills the writing JVM instead,
 * where the recovered count can only be bounded.
 */
class TakeRecoveryAfterAbandonedWritersContractTest {

    /** ≈ 2 minutes at 48 kHz in 512-frame blocks: 11,250 blocks = 5,760,000 frames = 11.5 MB of 16-bit mono. */
    private static final int BLOCKS = 11_250;
    /** 3 MiB cap → 3 sealed segments of 3072 blocks each plus a 2034-block streaming tail. */
    private static final long SEGMENT_BYTES = 3L << 20;
    private static final int BLOCKS_PER_SEGMENT = (int) (SEGMENT_BYTES / (BLOCK_FRAMES * 2L));

    @TempDir
    Path projectDir;

    @Test
    void abandoningTheWritersAfterAStopAtAPassBoundaryLeavesSealedSegmentsAndOnePartHoldingEveryWrittenFrame()
            throws IOException {
        AudioEngine engine = new AudioEngine(MONO_16);
        Transport transport = new Transport();
        Track track = RampCaptureTestSupport.armedMonoTrack("Vocal");
        // The take lives where the app puts it: <project>/audio/takes/<stamp>_take-NNNN.
        Path takeDir = TakeDirectories.allocate(ProjectManager.audioDirectory(projectDir), Instant.now());
        assertThat(takeDir.getParent()).isEqualTo(projectDir.resolve("audio").resolve("takes"));
        assertThat(TakeDirectories.isTakeDirectoryName(takeDir.getFileName().toString())).isTrue();
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, MONO_16, takeDir, List.of(track));
        pipeline.setSegmentLimits(Duration.ofHours(1), SEGMENT_BYTES);
        pipeline.setRingSlots(256);
        pipeline.start();

        feedRamp(engine, transport, pipeline, 0, BLOCKS, 64);
        RecordingSession session = pipeline.getSession(track);
        long written = session.getTotalSamplesRecorded();
        assertThat(written).isEqualTo((long) BLOCKS * BLOCK_FRAMES);
        assertThat(pipeline.getOverflowCount()).as("paced feeding never overflowed").isZero();

        // Stop at a pass boundary with no final sweep and no seal, then close
        // every writer with no size patch and no rename; this JVM goes on.
        CaptureFlushService service = pipeline.getCaptureFlushService();
        service.stopAndAbandon();
        assertThat(service.isRunning()).isFalse();
        assertThat(service.isSealed()).isFalse();

        // What is on disk under <take>/<trackId>/: three sealed .wav and exactly one .part.
        Path trackDir = takeDir.resolve(track.getId());
        List<Path> sealed = new ArrayList<>();
        List<Path> parts = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(trackDir)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (name.endsWith(".wav.part")) {
                    parts.add(entry);
                } else if (name.endsWith(".wav")) {
                    sealed.add(entry);
                }
            }
        }
        assertThat(sealed).extracting(p -> p.getFileName().toString())
                .containsExactlyInAnyOrder("segment-000.wav", "segment-001.wav", "segment-002.wav");
        assertThat(parts).extracting(p -> p.getFileName().toString())
                .containsExactly("segment-003.wav.part");
        assertThat(hasProvisionalSizes(parts.getFirst())).as("the .part still carries the streaming sentinel").isTrue();
        for (Path wav : sealed) {
            assertThat(hasProvisionalSizes(wav)).as("%s is sealed", wav).isFalse();
        }

        // The manifest still says streaming: sealed segments exact, the tail -1.
        TakeManifest manifest = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(manifest.sealStatus()).isEqualTo(TakeManifest.SealStatus.STREAMING);
        assertThat(manifest.sealedBy()).isEmpty();
        List<TakeManifest.SegmentEntry> entries = manifest.segmentsFor(track.getId());
        assertThat(entries).hasSize(4);
        for (int i = 0; i < 3; i++) {
            TakeManifest.SegmentEntry entry = entries.get(i);
            assertThat(entry.index()).isEqualTo(i);
            assertThat(entry.state()).isEqualTo(TakeManifest.SegmentState.SEALED);
            assertThat(entry.frames()).isEqualTo((long) BLOCKS_PER_SEGMENT * BLOCK_FRAMES);
            assertThat(entry.relativePath()).isEqualTo(track.getId() + "/segment-00" + i + ".wav");
        }
        TakeManifest.SegmentEntry tail = entries.get(3);
        assertThat(tail.index()).isEqualTo(3);
        assertThat(tail.state()).isEqualTo(TakeManifest.SegmentState.STREAMING);
        assertThat(tail.frames()).isEqualTo(TakeManifest.FRAMES_STREAMING);

        // Recover with the §3.4 grammar only, in manifest order, and compare
        // every frame with the ramp up to the last frame written.
        long recovered = 0;
        long firstMismatch = -1;
        for (TakeManifest.SegmentEntry entry : entries) {
            Path identity = entry.resolve(takeDir);
            Path file = entry.state() == TakeManifest.SegmentState.STREAMING
                    ? SegmentWriter.partPathFor(identity)
                    : identity;
            SegmentFile.Description description = SegmentFile.describe(file);
            assertThat(description.sealed()).isEqualTo(entry.state() == TakeManifest.SegmentState.SEALED);
            assertThat(description.channels()).isEqualTo(1);
            assertThat(description.bitDepth()).isEqualTo(16);
            if (entry.state() == TakeManifest.SegmentState.SEALED) {
                assertThat(description.frameCount()).isEqualTo(entry.frames());
            }
            float[][] frames = SegmentFile.readFrames(file);
            assertThat(frames[0]).hasSize((int) description.frameCount());
            for (int i = 0; i < frames[0].length && firstMismatch < 0; i++) {
                if (frames[0][i] != decodedRampValue(recovered + i)) {
                    firstMismatch = recovered + i;
                }
            }
            recovered += frames[0].length;
        }
        assertThat(firstMismatch).as("first frame that differs from the ramp").isEqualTo(-1);
        assertThat(recovered).as("every written frame is recoverable").isEqualTo(written);
        assertThat(recovered).isGreaterThan(3L * BLOCKS_PER_SEGMENT * BLOCK_FRAMES);
    }
}
