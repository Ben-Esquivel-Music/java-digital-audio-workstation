package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.awaitWithinTheGuard;
import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.startRecording;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.BLOCK_FRAMES;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.MONO_16;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.SAMPLE_RATE;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.advanceOneBlock;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.rampBlock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A caller that has read a take's audio back before it completes the stop
 * gets every clip published with that audio already attached
 * ({@link RecordingPipeline#recordedSegmentPaths()}, then
 * {@link RecordingPipeline#completeStop(Function)}): the plain take's clip
 * of each armed track and, in loop-record, the clip of every lap — the very
 * arrays the lookup returned, attached before the clip or its take group is
 * on the track. A lookup that has nothing for a clip leaves it without
 * audio, and the lookup is never asked about a clip that lists no segment.
 *
 * <p>"Before it is on the track" is observed from inside the lookup, which
 * the pipeline calls on the completing thread right before it attaches what
 * the lookup returns: at each call the armed tracks hold no clip and no take
 * group of the take yet.</p>
 */
@ExtendWith(CaptureFlushThreadLeakGuard.class)
class RecordedAudioAttachedBeforePublicationContractTest {

    @TempDir
    Path takeDir;

    private AudioEngine engine;
    private Transport transport;
    private long nextFrame;

    @BeforeEach
    void setUp() {
        engine = new AudioEngine(MONO_16);
        transport = new Transport();
        nextFrame = 0;
    }

    private void feedOneBlock(RecordingPipeline pipeline) {
        engine.processBlock(rampBlock(nextFrame), new float[1][BLOCK_FRAMES], BLOCK_FRAMES);
        advanceOneBlock(transport);
        nextFrame += BLOCK_FRAMES;
        pipeline.awaitFlushed();
    }

    /** One call of the lookup: the list it was asked about and what the tracks held at that moment. */
    private record Asked(List<String> segmentPaths, int clipsOnTheTracks, int takeGroupsOnTheTracks) {
    }

    /** A lookup over arrays "read" beforehand: one distinct array per segment-path list. */
    private static final class Loaded implements Function<List<String>, float[][]> {

        private final Map<List<String>, float[][]> audio = new LinkedHashMap<>();
        private final List<Asked> asked = new ArrayList<>();
        private final List<Track> tracks;

        Loaded(List<List<String>> segmentPathLists, List<Track> tracks) {
            this.tracks = tracks;
            for (List<String> segmentPaths : segmentPathLists) {
                audio.put(segmentPaths, new float[1][3]);
            }
        }

        @Override
        public float[][] apply(List<String> segmentPaths) {
            int clips = 0;
            int groups = 0;
            for (Track track : tracks) {
                clips += track.getClips().size();
                groups += track.getTakeGroups().size();
            }
            asked.add(new Asked(List.copyOf(segmentPaths), clips, groups));
            return audio.get(segmentPaths);
        }
    }

    @Test
    void everyClipOfAPlainTakeIsPublishedWithTheArrayTheLookupReturnedForItsSegments() {
        Track vocal = RampCaptureTestSupport.armedMonoTrack("Vocal");
        Track guitar = RampCaptureTestSupport.armedMonoTrack("Guitar");
        List<Track> tracks = List.of(vocal, guitar);
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, MONO_16, takeDir, tracks);
        assertThat(pipeline.recordedSegmentPaths()).as("nothing recorded yet").isEmpty();
        startRecording(pipeline);
        feedOneBlock(pipeline);
        feedOneBlock(pipeline);

        CompletionStage<Void> termination = pipeline.requestStop();
        awaitWithinTheGuard(termination, "the capture-flush thread's termination");
        List<List<String>> segmentPathLists = pipeline.recordedSegmentPaths();

        assertThat(segmentPathLists).as("one list per clip: each armed track's sealed segment").hasSize(2);
        assertThat(segmentPathLists.get(0)).singleElement().satisfies(path ->
                assertThat(Path.of(path)).isEqualTo(takeDir.resolve(vocal.getId()).resolve("segment-000.wav")
                        .toAbsolutePath()));
        assertThat(segmentPathLists.get(1)).singleElement().satisfies(path ->
                assertThat(Path.of(path).getParent().getFileName()).hasToString(guitar.getId()));
        assertThat(pipeline.isFinalizationPending()).as("asking for the lists completes nothing").isTrue();
        assertThat(vocal.getClips()).isEmpty();
        Loaded loaded = new Loaded(segmentPathLists, tracks);

        List<AudioClip> clips = pipeline.completeStop(loaded);

        assertThat(clips).hasSize(2);
        for (int i = 0; i < 2; i++) {
            AudioClip clip = clips.get(i);
            assertThat(clip.getSourceSegmentPaths()).as("clip %d is the clip its list stands for", i)
                    .isEqualTo(segmentPathLists.get(i));
            assertThat(clip.getAudioData()).as("clip %d carries the very array the lookup returned", i)
                    .isSameAs(loaded.audio.get(segmentPathLists.get(i)));
            assertThat(tracks.get(i).getClips()).containsExactly(clip);
        }
        assertThat(loaded.asked).as("the lookup was asked once per clip, in publication order, each time before "
                        + "that clip was on its track")
                .containsExactly(new Asked(segmentPathLists.get(0), 0, 0), new Asked(segmentPathLists.get(1), 1, 0));
        assertThat(pipeline.isFinalizationPending()).isFalse();
        assertThat(pipeline.recordedSegmentPaths()).as("once the stop is complete there is nothing left to load")
                .isEmpty();
    }

    @Test
    void everyLapOfALoopTakeHasItsAudioBeforeTheTakeGroupOrItsActiveClipIsOnTheTrack() {
        Track vocal = RampCaptureTestSupport.armedMonoTrack("Vocal");
        List<Track> tracks = List.of(vocal);
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, MONO_16, takeDir, tracks);
        double samplesPerBeat = SAMPLE_RATE * 60.0 / transport.getTempo();
        transport.setLoopRegion(0.0, 2 * BLOCK_FRAMES / samplesPerBeat);
        transport.setLoopEnabled(true);
        pipeline.setLoopRecord(true);
        startRecording(pipeline);
        for (int block = 0; block < 5; block++) { // laps of two blocks: two whole laps and one block of a third
            feedOneBlock(pipeline);
        }

        awaitWithinTheGuard(pipeline.requestStop(), "the capture-flush thread's termination");
        List<List<String>> segmentPathLists = pipeline.recordedSegmentPaths();

        assertThat(segmentPathLists).as("one list per lap, lap by lap").hasSize(3);
        assertThat(segmentPathLists).extracting(list -> Path.of(list.getFirst()).getFileName().toString())
                .containsExactly("segment-000.wav", "segment-001.wav", "segment-002.wav");
        Loaded loaded = new Loaded(segmentPathLists, tracks);

        List<AudioClip> clips = pipeline.completeStop(loaded);

        TakeGroup group = pipeline.getTakeGroups().get(vocal);
        assertThat(group.size()).isEqualTo(3);
        Map<AudioClip, Boolean> seen = new IdentityHashMap<>();
        for (int lap = 0; lap < 3; lap++) {
            AudioClip clip = group.takes().get(lap).clip();
            seen.put(clip, true);
            assertThat(clip.getSourceSegmentPaths()).isEqualTo(segmentPathLists.get(lap));
            assertThat(clip.getAudioData()).as("lap %d — not only the active take — carries its own array", lap)
                    .isSameAs(loaded.audio.get(segmentPathLists.get(lap)));
        }
        assertThat(seen).hasSize(3);
        assertThat(clips).as("the active take's clip is the one on the track").containsExactly(group.activeClip());
        assertThat(vocal.getClips()).containsExactly(group.activeClip());
        assertThat(vocal.getTakeGroups()).hasSize(1);
        assertThat(loaded.asked).as("every lap was asked about before the track held the clip or the take group")
                .containsExactly(new Asked(segmentPathLists.get(0), 0, 0), new Asked(segmentPathLists.get(1), 0, 0),
                        new Asked(segmentPathLists.get(2), 0, 0));
    }

    @Test
    void aClipTheLookupHasNothingForIsPublishedWithoutAudioAndThePlainCompleteStopAttachesNone() {
        Track vocal = RampCaptureTestSupport.armedMonoTrack("Vocal");
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, MONO_16, takeDir, List.of(vocal));
        startRecording(pipeline);
        feedOneBlock(pipeline);
        awaitWithinTheGuard(pipeline.requestStop(), "the capture-flush thread's termination");
        List<List<String>> asked = new ArrayList<>();

        List<AudioClip> clips = pipeline.completeStop(segmentPaths -> {
            asked.add(segmentPaths);
            return null;
        });

        assertThat(asked).as("the lookup was asked about the clip").hasSize(1);
        assertThat(clips).singleElement().satisfies(clip -> {
            assertThat(clip.getAudioData()).as("null means no audio: the clip is published without").isNull();
            assertThat(clip.getSourceSegmentPaths()).isEqualTo(asked.getFirst());
        });
        assertThat(vocal.getClips()).containsExactly(clips.getFirst());
        assertThatThrownBy(() -> pipeline.completeStop(null)).isInstanceOf(NullPointerException.class);

        // The plain completeStop() of a second take: as before, no audio.
        Track second = RampCaptureTestSupport.armedMonoTrack("Second");
        RecordingPipeline plain = new RecordingPipeline(engine, transport, MONO_16, takeDir.resolve("plain"),
                List.of(second));
        startRecording(plain);
        feedOneBlock(plain);
        awaitWithinTheGuard(plain.requestStop(), "the capture-flush thread's termination");
        assertThat(plain.recordedSegmentPaths()).hasSize(1);
        assertThat(plain.completeStop()).singleElement()
                .satisfies(clip -> assertThat(clip.getAudioData()).isNull());
    }

    @Test
    void aClipWhoseOnlySegmentDidNotSealHasNoListAndIsNeverLookedUp() {
        Track vocal = RampCaptureTestSupport.armedMonoTrack("Vocal");
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, MONO_16, takeDir, List.of(vocal));
        pipeline.setWarningSink(warning -> { });
        startRecording(pipeline);
        feedOneBlock(pipeline);
        pipeline.getSession(vocal).getCurrentWriter().failNextRename();
        awaitWithinTheGuard(pipeline.requestStop(), "the capture-flush thread's termination");

        assertThat(pipeline.stopSealFailure()).as("fixture: the only segment did not seal").isPresent();
        assertThat(pipeline.recordedSegmentPaths()).as("a clip with no sealed segment has nothing to read: no list")
                .isEmpty();
        List<List<String>> asked = new ArrayList<>();
        List<AudioClip> clips = pipeline.completeStop(segmentPaths -> {
            asked.add(segmentPaths);
            return new float[1][1];
        });

        assertThat(asked).as("the lookup is not asked about a clip that lists no segment").isEmpty();
        assertThat(clips).singleElement().satisfies(clip -> {
            assertThat(clip.getSourceSegmentPaths()).isEmpty();
            assertThat(clip.getAudioData()).isNull();
            assertThat(clip.getSourceRateMetadata().framesPerChannel()).isEqualTo(BLOCK_FRAMES);
        });
    }

    @Test
    void theListsAreRefusedWhileTheTakeIsStillBeingWrittenAndWhileOneIsBeingPrepared() {
        Track vocal = RampCaptureTestSupport.armedMonoTrack("Vocal");
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, MONO_16, takeDir, List.of(vocal));
        awaitWithinTheGuard(pipeline.prepare(), "the take's readiness");
        assertThatThrownBy(pipeline::recordedSegmentPaths).as("while a take is being prepared")
                .isInstanceOf(IllegalStateException.class);
        pipeline.beginCapture();
        assertThat(pipeline.recordedSegmentPaths()).as("while recording, no stop is pending: nothing").isEmpty();
        feedOneBlock(pipeline);

        // The flush thread is held between passes: the stop cannot finish.
        CaptureFlushService service = pipeline.getCaptureFlushService();
        service.setProducerQuiescenceBound(RampCaptureTestSupport.HANG_GUARD.multipliedBy(2));
        CaptureCallback callback = (CaptureCallback) engine.getRecordingCallback();
        assertThat(callback.fillBlock(rampBlock(nextFrame), BLOCK_FRAMES)).isTrue(); // inside the gate
        CompletionStage<Void> termination = pipeline.requestStop();
        try {
            assertThat(service.isTerminated()).as("fixture: the flush thread waits for the callback").isFalse();
            assertThatThrownBy(pipeline::recordedSegmentPaths).as("while the take is still being written")
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("still being written");
        } finally {
            callback.publishAndLeave(true);
        }
        awaitWithinTheGuard(termination, "the capture-flush thread's termination");
        assertThat(pipeline.recordedSegmentPaths()).hasSize(1);
        pipeline.completeStop();
    }
}
