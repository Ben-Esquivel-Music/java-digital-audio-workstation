package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.audio.RoundTripLatency;
import com.benesquivelmusic.daw.sdk.audio.SourceRateMetadata;
import com.benesquivelmusic.daw.sdk.transport.PunchRegion;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.startRecording;
import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.stopRecording;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Every number a take derives from its sample rate is derived from the rate
 * of the format the pipeline was given — the rate the engine streams at
 * (Recording Reliability book §2.7) — and from no other: the clip's length
 * and its latency-compensated start in beats, the manifest's anchor frame,
 * the record start a punch region names in frames, each loop lap's clip, the
 * rate a clip declares, the rate in the segment's WAV header and in the
 * manifest, and the rate a peak snapshot carries.
 *
 * <p>Each test runs at 44.1 kHz and at 96 kHz, stereo, 24 bits, in blocks of
 * 480 frames at 90 BPM: no figure here coincides with 48 kHz, 512 frames or
 * 120 BPM, so a rate taken from a constant or from another format shows in
 * every assertion.</p>
 */
@ExtendWith(CaptureFlushThreadLeakGuard.class)
class TakeMathFollowsTheStreamRateContractTest {

    private static final long GIB = 1L << 30;
    private static final long MIB = 1L << 20;
    private static final double BPM = 90.0;
    private static final int BLOCK = 480;

    @TempDir
    Path tempDir;

    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private final List<CapturePeakSnapshot> snapshots = new CopyOnWriteArrayList<>();

    private static AudioFormat stereo24(double rate) {
        return new AudioFormat(rate, 2, 24, BLOCK);
    }

    private static Track armedStereoTrack() {
        Track track = new Track("Keys", TrackType.AUDIO);
        track.setArmed(true);
        track.setInputRouting(new InputRouting(0, 2));
        return track;
    }

    private RecordingPipeline newPipeline(AudioEngine engine, Transport transport, AudioFormat format, Track track) {
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, format, tempDir, List.of(track));
        // Headroom that never warns, whatever the machine's disk holds.
        pipeline.setDiskHeadroomWatch(new DiskHeadroomWatch(tempDir, () -> 10 * GIB, GIB, 64 * MIB,
                Duration.ZERO, System::nanoTime, warnings::add));
        pipeline.setWarningSink(warnings::add);
        pipeline.setPeakSnapshotSink(snapshots::add);
        return pipeline;
    }

    /** Feeds {@code blocks} blocks, each applied by the flush thread before the transport moves on. */
    private static void feed(AudioEngine engine, Transport transport, RecordingPipeline pipeline,
                             AudioFormat format, int blocks) {
        float[][] input = new float[2][BLOCK];
        Arrays.fill(input[0], 0.25f);
        Arrays.fill(input[1], -0.5f);
        float[][] output = new float[2][BLOCK];
        double framesPerBeat = format.sampleRate() * 60.0 / transport.getTempo();
        for (int b = 0; b < blocks; b++) {
            engine.processBlock(input, output, BLOCK);
            pipeline.awaitFlushed();
            transport.advancePosition(BLOCK / framesPerBeat);
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = {44_100.0, 96_000.0})
    void aTakesClipIsSizedPlacedAndDeclaredAtTheRateOfItsStream(double rate) throws Exception {
        AudioFormat format = stereo24(rate);
        AudioEngine engine = new AudioEngine(format);
        Transport transport = new Transport();
        transport.setTempo(BPM);
        double startBeat = 6.0;
        transport.setPositionInBeats(startBeat);
        Track track = armedStereoTrack();
        RecordingPipeline pipeline = newPipeline(engine, transport, format, track);
        pipeline.setReportedLatency(RoundTripLatency.of(300, 420));
        pipeline.setApplyLatencyCompensation(true);
        int blocks = 7;

        startRecording(pipeline);
        feed(engine, transport, pipeline, format, blocks);
        List<AudioClip> clips = stopRecording(pipeline);

        long frames = (long) blocks * BLOCK;
        long compensation = pipeline.getResolvedCompensationFrames();
        assertThat(compensation).as("fixture: the driver latency is compensated").isEqualTo(720L);
        assertThat(clips).hasSize(1);
        AudioClip clip = clips.getFirst();
        assertThat(clip.getDurationBeats()).as("%d frames at %.0f Hz and %.0f BPM", frames, rate, BPM)
                .isCloseTo(frames / rate * (BPM / 60.0), within(1e-9));
        assertThat(clip.getStartBeat()).as("pulled back by %d frames at %.0f Hz", compensation, rate)
                .isCloseTo(startBeat - compensation / rate * (BPM / 60.0), within(1e-9));
        assertThat(clip.getSourceRateMetadata()).as("the clip declares the stream's rate, width and frames")
                .isEqualTo(new SourceRateMetadata((int) rate, 2, frames));

        TakeManifest manifest = TakeManifest.read(pipeline.getTakeManifestPath());
        long anchorFrame = Math.round(startBeat * 60.0 / BPM * rate);
        assertThat(pipeline.getRecordingStartFrame()).as("beat %.1f at %.0f Hz", startBeat, rate)
                .isEqualTo(anchorFrame);
        assertThat(manifest.startFrame()).as("the manifest's anchor frame").isEqualTo(anchorFrame);
        assertThat(manifest.sampleRate()).isEqualTo(rate);
        assertThat(clip.getSourceSegmentPaths()).as("the take's one sealed segment").hasSize(1);
        SegmentFile.Description header = SegmentFile.describe(Path.of(clip.getSourceSegmentPaths().getFirst()));
        assertThat(header.sampleRate()).as("the segment's WAV header carries the stream's rate").isEqualTo(rate);
        assertThat(header.channels()).isEqualTo(2);
        assertThat(header.bitDepth()).isEqualTo(24);

        assertThat(snapshots).as("the lane's peaks were published").isNotEmpty();
        assertThat(snapshots).allSatisfy(snapshot ->
                assertThat(snapshot.sampleRate()).as("a peak snapshot carries the stream's rate").isEqualTo(rate));
        assertThat(snapshots.getLast().totalFrames()).isEqualTo(frames);
        assertThat(warnings).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(doubles = {44_100.0, 96_000.0})
    void aPunchRegionAnchorsTheTakeAtItsFirstFrameConvertedAtTheRateOfTheStream(double rate) throws Exception {
        AudioFormat format = stereo24(rate);
        AudioEngine engine = new AudioEngine(format);
        Transport transport = new Transport();
        transport.setTempo(BPM);
        long punchIn = 2L * BLOCK;
        long punchOut = 5L * BLOCK;
        transport.setPunchRegion(new PunchRegion(punchIn, punchOut, true));
        Track track = armedStereoTrack();
        RecordingPipeline pipeline = newPipeline(engine, transport, format, track);

        startRecording(pipeline);
        feed(engine, transport, pipeline, format, 6);
        List<AudioClip> clips = stopRecording(pipeline);

        double punchInBeat = punchIn / rate * (BPM / 60.0);
        assertThat(pipeline.getRecordingStartBeat()).as("frame %d at %.0f Hz, in beats", punchIn, rate)
                .isCloseTo(punchInBeat, within(1e-9));
        assertThat(TakeManifest.read(pipeline.getTakeManifestPath()).startFrame())
                .as("the anchor frame is the punch-in frame").isEqualTo(punchIn);
        assertThat(clips).hasSize(1);
        AudioClip clip = clips.getFirst();
        long frames = punchOut - punchIn;
        assertThat(clip.getSourceRateMetadata()).as("only the frames inside the punch region were captured")
                .isEqualTo(new SourceRateMetadata((int) rate, 2, frames));
        assertThat(clip.getStartBeat()).isCloseTo(punchInBeat, within(1e-9));
        assertThat(clip.getDurationBeats()).isCloseTo(frames / rate * (BPM / 60.0), within(1e-9));
    }

    @ParameterizedTest
    @ValueSource(doubles = {44_100.0, 96_000.0})
    void everyLoopLapsClipIsSizedAndDeclaredAtTheRateOfItsStream(double rate) throws Exception {
        AudioFormat format = stereo24(rate);
        AudioEngine engine = new AudioEngine(format);
        Transport transport = new Transport();
        transport.setTempo(BPM);
        int blocksPerLap = 4;
        double framesPerBeat = rate * 60.0 / BPM;
        transport.setLoopRegion(0.0, blocksPerLap * (double) BLOCK / framesPerBeat);
        transport.setLoopEnabled(true);
        Track track = armedStereoTrack();
        RecordingPipeline pipeline = newPipeline(engine, transport, format, track);
        pipeline.setLoopRecord(true);

        startRecording(pipeline);
        // Two laps, and the first block of a third so the second lap's wrap is seen.
        feed(engine, transport, pipeline, format, 2 * blocksPerLap + 1);
        stopRecording(pipeline);

        TakeGroup group = pipeline.getTakeGroups().get(track);
        assertThat(group).isNotNull();
        assertThat(group.size()).as("two whole laps and the lap the stop ended").isEqualTo(3);
        long lapFrames = (long) blocksPerLap * BLOCK;
        for (int lap = 0; lap < 2; lap++) {
            AudioClip clip = group.takes().get(lap).clip();
            assertThat(clip.getSourceRateMetadata()).as("lap %d declares the stream's rate", lap)
                    .isEqualTo(new SourceRateMetadata((int) rate, 2, lapFrames));
            assertThat(clip.getDurationBeats()).as("lap %d: %d frames at %.0f Hz", lap, lapFrames, rate)
                    .isCloseTo(lapFrames / rate * (BPM / 60.0), within(1e-9));
        }
        AudioClip last = group.takes().get(2).clip();
        assertThat(last.getSourceRateMetadata()).isEqualTo(new SourceRateMetadata((int) rate, 2, BLOCK));
        assertThat(last.getDurationBeats()).isCloseTo(BLOCK / rate * (BPM / 60.0), within(1e-9));
        assertThat(snapshots).allSatisfy(snapshot ->
                assertThat(snapshot.sampleRate()).as("a peak snapshot carries the stream's rate").isEqualTo(rate));
    }
}
