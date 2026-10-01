package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.event.RecordingListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 323 proof — the take manifest (book §3.3; context D8): written at
 * take start with the format, start position, compensation and per-track
 * streaming segments; rewritten at every rotation; sealed at stop; never a
 * backslash; and its segment order is the clip's segment order.
 */
class TakeManifestLifecycleContractTest {

    private static final AudioFormat STEREO_16 = new AudioFormat(48_000.0, 2, 16, 512);
    /** Stage-1 capture width is the stream width: 2 channels × 16 bit. */
    private static final long BLOCK_BYTES = 512L * 2 * 2;

    @TempDir
    Path takeDir;

    @Test
    void manifestIsWrittenAtStartRewrittenAtRotationAndSealedAtStop() throws IOException {
        AudioEngine engine = new AudioEngine(STEREO_16);
        Transport transport = new Transport();
        transport.setPositionInBeats(8.0);
        Track left = new Track("Left mic", TrackType.AUDIO);
        left.setArmed(true);
        left.setInputRouting(new InputRouting(0, 1));
        Track right = new Track("Right mic", TrackType.AUDIO);
        right.setArmed(true);
        right.setInputRouting(new InputRouting(1, 1));
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, STEREO_16, takeDir, List.of(left, right));
        pipeline.setSegmentLimits(Duration.ofHours(1), 2 * BLOCK_BYTES);
        pipeline.start();
        CaptureFlushService service = pipeline.getCaptureFlushService();

        // At start.
        Path manifestPath = pipeline.getTakeManifestPath();
        assertThat(manifestPath).isEqualTo(takeDir.resolve("take.manifest")).exists();
        assertThat(takeDir.resolve("take.manifest.tmp")).doesNotExist();
        String initialText = Files.readString(manifestPath, StandardCharsets.UTF_8);
        TakeManifest initial = TakeManifest.read(manifestPath);
        assertThat(service.manifestWrites()).isEqualTo(1);
        assertThat(initial.sealStatus()).isEqualTo(TakeManifest.SealStatus.STREAMING);
        assertThat(initial.sealedBy()).isEmpty();
        assertThat(initial.take()).isEqualTo(takeDir.toAbsolutePath().getFileName().toString());
        assertThat(initial.tracks()).extracting(TakeManifest.TrackEntry::trackId)
                .containsExactly(left.getId(), right.getId());
        assertThat(initial.tracks()).extracting(TakeManifest.TrackEntry::channels).containsOnly(2);
        assertThat(initial.tracks()).extracting(TakeManifest.TrackEntry::compensationFrames).containsOnly(0L);
        assertThat(initial.sampleRate()).isEqualTo(48_000.0);
        assertThat(initial.bitDepth()).isEqualTo(16);
        assertThat(initial.streamChannels()).isEqualTo(2);
        assertThat(initial.startBeat()).isEqualTo(pipeline.getRecordingStartBeat()).isEqualTo(8.0);
        assertThat(initial.startFrame()).isEqualTo(pipeline.getRecordingStartFrame()).isEqualTo(192_000L);
        assertThat(initial.forceCadenceMillis()).isEqualTo(5_000L);
        assertThat(initial.ringSlots()).isEqualTo(pipeline.getCaptureRing().capacity());
        assertThat(initial.ringFrames()).isEqualTo(512);
        assertThat(initial.overflowBlocks()).isZero();
        assertThat(initial.gaps()).isEmpty();
        for (Track track : List.of(left, right)) {
            List<TakeManifest.SegmentEntry> entries = initial.segmentsFor(track.getId());
            assertThat(entries).hasSize(1);
            assertThat(entries.getFirst().lane()).isZero();
            assertThat(entries.getFirst().index()).isZero();
            assertThat(entries.getFirst().state()).isEqualTo(TakeManifest.SegmentState.STREAMING);
            assertThat(entries.getFirst().frames()).isEqualTo(TakeManifest.FRAMES_STREAMING);
            assertThat(entries.getFirst().relativePath()).isEqualTo(track.getId() + "/segment-000.wav");
            assertThat(SegmentWriter.partPathFor(entries.getFirst().resolve(takeDir))).exists();
            assertThat(pipeline.getSession(track).getCurrentWriter().forceCadence())
                    .as("the cadence the manifest records is the one %s's writer runs at", track.getName())
                    .isEqualTo(Duration.ofMillis(initial.forceCadenceMillis()))
                    .isEqualTo(SegmentWriter.DEFAULT_FORCE_CADENCE);
        }

        // Three blocks: the second one rotates both tracks' segments.
        float[][] input = new float[2][512];
        float[][] output = new float[2][512];
        for (int i = 0; i < 512; i++) {
            input[0][i] = 0.25f;
            input[1][i] = -0.25f;
        }
        for (int b = 0; b < 3; b++) {
            engine.processBlock(input, output, 512);
            RampCaptureTestSupport.advanceOneBlock(transport);
            pipeline.awaitFlushed();
        }

        // After the rotation.
        String rotatedText = Files.readString(manifestPath, StandardCharsets.UTF_8);
        assertThat(rotatedText).isNotEqualTo(initialText);
        assertThat(service.manifestWrites()).as("one rewrite for the rotation block").isEqualTo(2);
        TakeManifest rotated = TakeManifest.read(manifestPath);
        assertThat(rotated.sealStatus()).isEqualTo(TakeManifest.SealStatus.STREAMING);
        for (Track track : List.of(left, right)) {
            List<TakeManifest.SegmentEntry> entries = rotated.segmentsFor(track.getId());
            assertThat(entries).extracting(TakeManifest.SegmentEntry::index).containsExactly(0, 1);
            assertThat(entries.get(0).state()).isEqualTo(TakeManifest.SegmentState.SEALED);
            assertThat(entries.get(0).frames()).isEqualTo(1024L);
            assertThat(entries.get(1).state()).isEqualTo(TakeManifest.SegmentState.STREAMING);
            assertThat(entries.get(1).frames()).isEqualTo(TakeManifest.FRAMES_STREAMING);
        }

        // After stop.
        List<AudioClip> clips = pipeline.stop();
        assertThat(clips).hasSize(2);
        String sealedText = Files.readString(manifestPath, StandardCharsets.UTF_8);
        assertThat(sealedText).doesNotContain("\\");
        assertThat(sealedText).contains("seal-status=sealed").contains("sealed-by=stop");
        assertThat(takeDir.resolve("take.manifest.tmp")).doesNotExist();
        TakeManifest sealed = TakeManifest.read(manifestPath);
        assertThat(sealed.sealStatus()).isEqualTo(TakeManifest.SealStatus.SEALED);
        assertThat(sealed.sealedBy()).contains(TakeManifest.SealedBy.STOP);
        assertThat(sealed.segments()).extracting(TakeManifest.SegmentEntry::state)
                .containsOnly(TakeManifest.SegmentState.SEALED);
        assertThat(sealed.segments()).hasSize(4);
        assertThat(sealed.segments()).extracting(TakeManifest.SegmentEntry::trackId)
                .containsExactly(left.getId(), left.getId(), right.getId(), right.getId());
        for (int i = 0; i < 2; i++) {
            Track track = List.of(left, right).get(i);
            AudioClip clip = clips.get(i);
            List<TakeManifest.SegmentEntry> entries = sealed.segmentsFor(track.getId());
            assertThat(entries).extracting(TakeManifest.SegmentEntry::frames).containsExactly(1024L, 512L);
            List<String> fromManifest = new ArrayList<>();
            for (TakeManifest.SegmentEntry entry : entries) {
                assertThat(entry.resolve(takeDir)).exists();
                fromManifest.add(entry.resolve(takeDir).toAbsolutePath().toString());
            }
            assertThat(fromManifest).as("re-read manifest order equals the clip's order for %s", track.getName())
                    .isEqualTo(clip.getSourceSegmentPaths());
        }
        assertThat(sealed.equals(TakeManifest.parse(sealedText, "sealed"))).isTrue();
    }

    @Test
    void theFirstSegmentOpensOnTheCallerThreadAndEveryRotationOnTheFlushThread() {
        AudioEngine engine = new AudioEngine(STEREO_16);
        Transport transport = new Transport();
        Track track = new Track("Overhead", TrackType.AUDIO);
        track.setArmed(true);
        track.setInputRouting(new InputRouting(0, 2));
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, STEREO_16, takeDir, List.of(track));
        Map<Integer, String> openedOn = new ConcurrentHashMap<>();
        List<String> stoppedOn = new CopyOnWriteArrayList<>();
        pipeline.setSessionFactory((t, dir) -> {
            RecordingSession session = new RecordingSession(STEREO_16, dir, Duration.ofHours(1), 2 * BLOCK_BYTES);
            session.addListener(new RecordingListener() {
                @Override
                public void onRecordingStarted() {
                }

                @Override
                public void onRecordingPaused() {
                }

                @Override
                public void onRecordingResumed() {
                }

                @Override
                public void onRecordingStopped() {
                    stoppedOn.add(Thread.currentThread().getName());
                }

                @Override
                public void onNewSegmentCreated(int segmentIndex) {
                    openedOn.put(segmentIndex, Thread.currentThread().getName());
                }
            });
            return session;
        });
        String caller = Thread.currentThread().getName();
        assertThat(caller).as("fixture: the test thread is not the flush thread")
                .isNotEqualTo(CaptureFlushService.THREAD_NAME);

        pipeline.start();
        assertThat(openedOn).as("start() opened segment 0 itself").containsOnly(Map.entry(0, caller));

        float[][] input = new float[2][512];
        float[][] output = new float[2][512];
        for (int b = 0; b < 6; b++) { // two blocks per segment: rotations open segments 1, 2 and 3
            engine.processBlock(input, output, 512);
            RampCaptureTestSupport.advanceOneBlock(transport);
            pipeline.awaitFlushed();
        }
        pipeline.stop();

        assertThat(openedOn.keySet()).containsExactlyInAnyOrder(0, 1, 2, 3);
        assertThat(openedOn.get(0)).isEqualTo(caller);
        for (int index = 1; index <= 3; index++) {
            assertThat(openedOn.get(index)).as("segment %d was opened by a rotation", index)
                    .isEqualTo(CaptureFlushService.THREAD_NAME)
                    .isEqualTo("capture-flush");
        }
        assertThat(stoppedOn).as("the final seal runs on the flush thread too")
                .containsExactly(CaptureFlushService.THREAD_NAME);
    }
}
