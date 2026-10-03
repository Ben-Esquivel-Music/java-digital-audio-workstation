package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.startRecording;
import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.stopRecording;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.BLOCK_FRAMES;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.MONO_16;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.SAMPLE_RATE;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.feedRamp;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 323 proof (2) — reference completeness (book §5.3): a take that
 * rotated past segment boundaries yields a clip referencing <em>every</em>
 * segment in manifest order, never only {@code segment-000.wav}; in
 * loop-record each lap's take references its own lane's segments only.
 */
class RotationReferencesEverySegmentContractTest {

    private static final long BLOCK_BYTES = (long) BLOCK_FRAMES * RampCaptureTestSupport.BYTES_PER_FRAME_MONO_16;

    @TempDir
    Path takeDir;

    private AudioEngine engine;
    private Transport transport;
    private Track track;

    @BeforeEach
    void setUp() {
        engine = new AudioEngine(MONO_16);
        transport = new Transport();
        transport.setTempo(120.0);
        track = RampCaptureTestSupport.armedMonoTrack("Guitar");
    }

    @Test
    void theClipReferencesEverySealedSegmentInManifestOrder() throws IOException {
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, MONO_16, takeDir, List.of(track));
        pipeline.setSegmentLimits(Duration.ofHours(1), 4 * BLOCK_BYTES); // 4 blocks per segment
        startRecording(pipeline);
        feedRamp(engine, transport, pipeline, 0, 10, 4); // 4 + 4 + 2 → 3 segments

        List<AudioClip> clips = stopRecording(pipeline);

        assertThat(clips).hasSize(1);
        AudioClip clip = clips.getFirst();
        Path trackDir = takeDir.resolve(track.getId()).toAbsolutePath();
        List<String> expected = List.of(
                trackDir.resolve("segment-000.wav").toString(),
                trackDir.resolve("segment-001.wav").toString(),
                trackDir.resolve("segment-002.wav").toString());
        assertThat(clip.getSourceSegmentPaths()).containsExactlyElementsOf(expected);
        assertThat(clip.getSourceFilePath()).isEqualTo(expected.getFirst());
        assertThat(Path.of(clip.getSourceFilePath()).isAbsolute()).isTrue();

        long framesOnDisk = 0;
        for (String path : clip.getSourceSegmentPaths()) {
            assertThat(Path.of(path)).exists();
            SegmentFile.Description description = SegmentFile.describe(Path.of(path));
            assertThat(description.sealed()).isTrue();
            framesOnDisk += description.frameCount();
        }
        assertThat(framesOnDisk).isEqualTo(10L * BLOCK_FRAMES);
        assertThat(clip.getAudioData()[0]).hasSize(10 * BLOCK_FRAMES);

        RecordingSession session = pipeline.getSession(track);
        assertThat(session.getSegments()).hasSize(3);
        assertThat(session.getSegments()).extracting(RecordingSegment::isInProgress).containsOnly(false);
        assertThat(session.getSegments()).extracting(RecordingSegment::sampleCount)
                .containsExactly(4L * BLOCK_FRAMES, 4L * BLOCK_FRAMES, 2L * BLOCK_FRAMES);
        assertThat(session.getSegments()).extracting(RecordingSegment::sizeBytes)
                .containsExactly(4 * BLOCK_BYTES, 4 * BLOCK_BYTES, 2 * BLOCK_BYTES);
        try (Stream<Path> files = Files.list(trackDir)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .as("the track directory holds the three sealed segments and nothing else: no stray .part")
                    .containsExactlyInAnyOrder("segment-000.wav", "segment-001.wav", "segment-002.wav");
        }

        TakeManifest manifest = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(manifest.sealStatus()).isEqualTo(TakeManifest.SealStatus.SEALED);
        assertThat(manifest.sealedBy()).contains(TakeManifest.SealedBy.STOP);
        List<TakeManifest.SegmentEntry> entries = manifest.segmentsFor(track.getId());
        assertThat(entries).hasSize(3);
        assertThat(entries).extracting(TakeManifest.SegmentEntry::state)
                .containsOnly(TakeManifest.SegmentState.SEALED);
        assertThat(entries).extracting(TakeManifest.SegmentEntry::frames)
                .containsExactly(4L * BLOCK_FRAMES, 4L * BLOCK_FRAMES, 2L * BLOCK_FRAMES);
        List<String> fromManifest = new ArrayList<>();
        for (TakeManifest.SegmentEntry entry : entries) {
            fromManifest.add(entry.resolve(takeDir).toAbsolutePath().toString());
        }
        assertThat(fromManifest).as("manifest order IS the clip's order").isEqualTo(clip.getSourceSegmentPaths());
    }

    @Test
    void eachLoopTakeReferencesOnlyItsOwnLaneSegments() throws IOException {
        int blocksPerLoop = 4;
        double samplesPerBeat = SAMPLE_RATE * 60.0 / transport.getTempo();
        transport.setLoopRegion(0.0, blocksPerLoop * (double) BLOCK_FRAMES / samplesPerBeat);
        transport.setLoopEnabled(true);
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, MONO_16, takeDir, List.of(track));
        pipeline.setSegmentLimits(Duration.ofHours(1), 2 * BLOCK_BYTES); // 2 segments per lap
        pipeline.setLoopRecord(true);
        startRecording(pipeline);
        feedRamp(engine, transport, pipeline, 0, 3 * blocksPerLoop + 1, 1); // 3 laps + wrap trigger

        stopRecording(pipeline);

        TakeGroup group = pipeline.getTakeGroups().get(track);
        assertThat(group).isNotNull();
        assertThat(group.size()).isGreaterThanOrEqualTo(3);
        TakeManifest manifest = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(manifest.sealStatus()).isEqualTo(TakeManifest.SealStatus.SEALED);
        Set<String> seen = new HashSet<>();
        for (int lane = 0; lane < 3; lane++) {
            AudioClip clip = group.takes().get(lane).clip();
            List<String> paths = clip.getSourceSegmentPaths();
            assertThat(paths).as("lane %d has two segments", lane).hasSize(2);
            assertThat(clip.getSourceFilePath()).isEqualTo(paths.getFirst());
            long frames = 0;
            for (String path : paths) {
                assertThat(seen.add(path)).as("%s belongs to one lane only", path).isTrue();
                assertThat(Path.of(path)).exists();
                frames += SegmentFile.describe(Path.of(path)).frameCount();
            }
            assertThat(frames).as("a lap's segments hold exactly the lap").isEqualTo((long) blocksPerLoop * BLOCK_FRAMES);
            assertThat(clip.getAudioData()[0]).hasSize(blocksPerLoop * BLOCK_FRAMES);

            List<String> laneFromManifest = new ArrayList<>();
            for (TakeManifest.SegmentEntry entry : manifest.segmentsFor(track.getId())) {
                if (entry.lane() == lane) {
                    assertThat(entry.state()).isEqualTo(TakeManifest.SegmentState.SEALED);
                    laneFromManifest.add(entry.resolve(takeDir).toAbsolutePath().toString());
                }
            }
            assertThat(laneFromManifest).as("manifest lane %d", lane).isEqualTo(paths);
        }
        // Segment numbering continues across lanes inside <trackId>/.
        assertThat(group.takes().get(0).clip().getSourceSegmentPaths().getFirst()).endsWith("segment-000.wav");
        assertThat(group.takes().get(0).clip().getSourceSegmentPaths().get(1)).endsWith("segment-001.wav");
        assertThat(group.takes().get(1).clip().getSourceSegmentPaths().getFirst()).endsWith("segment-002.wav");
        assertThat(group.takes().get(2).clip().getSourceSegmentPaths().getFirst()).endsWith("segment-004.wav");
    }
}
