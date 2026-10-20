package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.comping.CompRegion;
import com.benesquivelmusic.daw.core.comping.CompileCompAction;
import com.benesquivelmusic.daw.core.comping.TakeComping;
import com.benesquivelmusic.daw.core.comping.TakeLane;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.audio.RoundTripLatency;
import com.benesquivelmusic.daw.sdk.audio.SourceRateMetadata;
import com.benesquivelmusic.daw.sdk.transport.PunchRegion;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.startRecording;
import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.stopRecording;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * Tests for loop-record behavior on {@link RecordingPipeline} (story 132;
 * story 328, book §3.5/§4.6): each loop lap must finalize a distinct lap
 * that becomes a {@link TakeLane} of the armed track's one take model, with
 * contiguous sample counts across laps, sample-accurate loop boundaries,
 * the last whole lap selected and its comp clip on the main lane.
 */
@ExtendWith(CaptureFlushThreadLeakGuard.class)
class RecordingPipelineLoopRecordTest {

    @TempDir
    Path tempDir;

    private AudioEngine audioEngine;
    private Transport transport;
    private AudioFormat format;

    // Buffer size chosen so an integer number of blocks covers the loop
    // region exactly — guarantees sample-accurate wrap boundaries in the
    // test harness.
    private static final int BUFFER_SIZE = 512;
    private static final double BPM = 120.0;

    @BeforeEach
    void setUp() {
        format = new AudioFormat(44_100.0, 2, 16, BUFFER_SIZE);
        audioEngine = new AudioEngine(format);
        transport = new Transport();
        transport.setTempo(BPM);
    }

    /**
     * Configures the transport with a loop region of exactly {@code blocks}
     * buffer-lengths, so {@code blocks} calls to {@code processBlock} fill
     * exactly one loop lap.
     */
    private void setUpLoopOfExactBlocks(int blocks) {
        double samplesPerBeat = format.sampleRate() * 60.0 / BPM;
        double loopLenBeats = (blocks * (double) BUFFER_SIZE) / samplesPerBeat;
        transport.setLoopRegion(0.0, loopLenBeats);
        transport.setLoopEnabled(true);
    }

    /**
     * Feeds one block and waits for the flush thread to apply it (story
     * 323): capture is asynchronous, and the wrap detection, lane seals and
     * sample counts these tests assert are all flush-thread facts.
     */
    private void processOneBlock(RecordingPipeline pipeline) {
        float[][] input = new float[2][BUFFER_SIZE];
        float[][] output = new float[2][BUFFER_SIZE];
        audioEngine.processBlock(input, output, BUFFER_SIZE);
        pipeline.awaitFlushed();
        advanceTransportByBufferSize();
    }

    private void advanceTransportByBufferSize() {
        double samplesPerBeat = format.sampleRate() * 60.0 / transport.getTempo();
        transport.advancePosition(BUFFER_SIZE / samplesPerBeat);
    }

    @Test
    void tenConsecutiveLoopsProduceTenDistinctTakeLanes() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);

        int blocksPerLoop = 4;
        setUpLoopOfExactBlocks(blocksPerLoop);

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        pipeline.setLoopRecord(true);
        startRecording(pipeline);

        // Drive exactly 10 complete loop laps. Each lap is `blocksPerLoop`
        // buffer-sized blocks, so 10 laps need 40 blocks total. We also
        // drive one extra block to ensure the wrap from lap 10 is detected.
        int laps = 10;
        for (int i = 0; i < laps * blocksPerLoop; i++) {
            processOneBlock(pipeline);
        }
        // One more block to trigger the wrap detection for lap 10.
        processOneBlock(pipeline);

        List<AudioClip> clips = stopRecording(pipeline);

        TakeComping comping = track.getTakeComping();
        assertThat(comping.isActive()).as("the laps landed in the track's one take model").isTrue();

        // Expect at least ten lanes (one per complete lap). The last lap is
        // finalized by the stop — one extra short lane from the trailing block.
        assertThat(comping.getTakeLaneCount()).isGreaterThanOrEqualTo(10);
        assertThat(pipeline.getRecordedTakeLanes().get(track)).containsExactlyElementsOf(comping.getTakeLanes());

        // Lanes are in lap order, named "Take N", each holding one distinct lap clip.
        List<AudioClip> laneClips = comping.getTakeLanes().stream().map(lane -> lane.getClips().getFirst()).toList();
        assertThat(laneClips).doesNotHaveDuplicates();
        assertThat(laneClips).containsExactlyElementsOf(pipeline.getLapClips().get(track));
        for (int i = 0; i < comping.getTakeLaneCount(); i++) {
            assertThat(comping.getTakeLane(i).getName()).isEqualTo("Take " + (i + 1));
        }

        // The compiled comp clip of the last whole lap (lap 10) is what plays.
        assertThat(clips).hasSize(1);
        assertThat(track.getClips()).containsExactlyElementsOf(clips);
        assertThat(comping.getCompRegions()).containsExactly(
                new CompRegion(9, laneClips.get(9).getStartBeat(), laneClips.get(9).getDurationBeats()));
        assertThat(comping.compOutputOn(track)).containsExactlyElementsOf(clips);
    }

    @Test
    void fourLapsThenAStopMidLapFiveKeepFiveLanesWithLapFourSelectedAndCompiled() {
        // Story 328 (book §3.5, consult §4): the partial final lap is kept as
        // a lane but is not the default selection; the last whole lap is,
        // over its whole extent, and its comp clip on the main lane shares
        // the lap's audio.
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        int blocksPerLoop = 4;
        setUpLoopOfExactBlocks(blocksPerLoop);
        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        pipeline.setLoopRecord(true);
        startRecording(pipeline);

        for (int i = 0; i < 4 * blocksPerLoop + 1; i++) { // 4 whole laps + 1 block of lap 5
            processOneBlock(pipeline);
        }

        List<AudioClip> clips = stopRecording(pipeline);

        TakeComping comping = track.getTakeComping();
        assertThat(comping.getTakeLaneCount()).as("4 whole laps + the partial lap 5").isEqualTo(5);
        assertThat(comping.getTakeLanes()).extracting(TakeLane::getName)
                .containsExactly("Take 1", "Take 2", "Take 3", "Take 4", "Take 5");
        AudioClip lapFour = comping.getTakeLane(3).getClips().getFirst();
        AudioClip lapFive = comping.getTakeLane(4).getClips().getFirst();
        assertThat(lapFive.getDurationBeats()).as("lap 5 holds one block").isLessThan(lapFour.getDurationBeats());
        assertThat(comping.getCompRegions())
                .containsExactly(new CompRegion(3, lapFour.getStartBeat(), lapFour.getDurationBeats()));

        assertThat(clips).hasSize(1);
        AudioClip comp = clips.getFirst();
        assertThat(track.getClips()).containsExactly(comp);
        assertThat(comp.getStartBeat()).isEqualTo(lapFour.getStartBeat());
        assertThat(comp.getDurationBeats()).isEqualTo(lapFour.getDurationBeats());
        assertThat(comp.getSourceSegmentPaths()).isEqualTo(lapFour.getSourceSegmentPaths());
        assertThat(RecordedAudioTestSupport.audioOnDisk(comp)[0])
                .as("the comp clip plays lap 4's audio")
                .containsExactly(RecordedAudioTestSupport.audioOnDisk(lapFour)[0]);
        assertThat(pipeline.getRecordedClips()).containsEntry(track, comp);
    }

    @Test
    void aStopInsideTheOnlyLapSelectsThatPartialLap() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        setUpLoopOfExactBlocks(4);
        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        pipeline.setLoopRecord(true);
        startRecording(pipeline);

        processOneBlock(pipeline);
        processOneBlock(pipeline);

        List<AudioClip> clips = stopRecording(pipeline);

        TakeComping comping = track.getTakeComping();
        assertThat(comping.getTakeLaneCount()).isEqualTo(1);
        AudioClip lap = comping.getTakeLane(0).getClips().getFirst();
        assertThat(comping.getCompRegions())
                .containsExactly(new CompRegion(0, lap.getStartBeat(), lap.getDurationBeats()));
        assertThat(clips).hasSize(1);
        assertThat(track.getClips()).containsExactlyElementsOf(clips);
    }

    @Test
    void loopBoundariesAreSampleAccurate() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);

        // One loop = 4 blocks of BUFFER_SIZE frames each.
        int blocksPerLoop = 4;
        setUpLoopOfExactBlocks(blocksPerLoop);

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        pipeline.setLoopRecord(true);
        startRecording(pipeline);

        // Drive three full loops.
        for (int i = 0; i < 3 * blocksPerLoop; i++) {
            processOneBlock(pipeline);
        }
        // Trigger wrap detection for the 3rd lap.
        processOneBlock(pipeline);

        stopRecording(pipeline);

        TakeComping comping = track.getTakeComping();
        assertThat(comping.getTakeLaneCount()).isGreaterThanOrEqualTo(3);

        // First three lanes should each have exactly `blocksPerLoop *
        // BUFFER_SIZE` sample frames — sample-accurate loop boundaries.
        int expectedFrames = blocksPerLoop * BUFFER_SIZE;
        for (int i = 0; i < 3; i++) {
            AudioClip clip = comping.getTakeLane(i).getClips().getFirst();
            float[][] data = RecordedAudioTestSupport.audioOnDisk(clip);
            assertThat(data[0].length).as("take %d frame count", i).isEqualTo(expectedFrames);
            assertThat(clip.getSourceRateMetadata())
                    .as("take %d declares what its lap captured", i)
                    .isEqualTo(new SourceRateMetadata(44_100, 2, expectedFrames));
        }
    }

    @Test
    void takesAreContiguousAcrossLoopWrap() {
        // Feed a ramp so we can verify that no input frames are dropped at
        // the loop seam: concatenating take N's tail with take N+1's head
        // should preserve a monotonically-increasing ramp. The ramp steps by
        // one 16-bit code per frame, so each frame's value survives the
        // segment files exactly: frame n is written as code n and decodes
        // to n / 32768.
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);

        int blocksPerLoop = 2;
        setUpLoopOfExactBlocks(blocksPerLoop);

        // Install a recording callback wrapper that feeds a ramp instead of
        // zeros. To do this we prepare our own input buffer each block.
        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        pipeline.setLoopRecord(true);
        startRecording(pipeline);

        float[][] output = new float[2][BUFFER_SIZE];
        int totalBlocks = blocksPerLoop * 3 + 1; // 3 full laps + 1 trigger block
        int nextSample = 0;
        for (int b = 0; b < totalBlocks; b++) {
            float[][] input = new float[2][BUFFER_SIZE];
            for (int f = 0; f < BUFFER_SIZE; f++) {
                float v = nextSample++ / 32767f;
                input[0][f] = v;
                input[1][f] = v;
            }
            audioEngine.processBlock(input, output, BUFFER_SIZE);
            pipeline.awaitFlushed();
            advanceTransportByBufferSize();
        }

        stopRecording(pipeline);

        TakeComping comping = track.getTakeComping();
        assertThat(comping.getTakeLaneCount()).isGreaterThanOrEqualTo(3);

        // Concatenate the first three lanes and verify the samples form a
        // contiguous ramp (codes 0, 1, 2, ...). This proves no frames were
        // dropped at loop wrap boundaries.
        assertThat(nextSample).as("fixture: the ramp stays inside 16 bits").isLessThan(32_768);
        int cursor = 0;
        for (int i = 0; i < 3; i++) {
            float[][] data = RecordedAudioTestSupport.audioOnDisk(comping.getTakeLane(i).getClips().getFirst());
            assertThat(data[0]).as("take %d holds one lap", i).hasSize(blocksPerLoop * BUFFER_SIZE);
            for (int f = 0; f < data[0].length; f++) {
                assertThat(data[0][f])
                        .as("sample %d of take %d", f, i)
                        .isEqualTo(cursor / 32768f);
                cursor++;
            }
        }
    }

    @Test
    void loopRecordDisabledBehavesLikeStandardRecording() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);

        setUpLoopOfExactBlocks(2);

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        // Deliberately leave loopRecord = false.
        startRecording(pipeline);

        for (int i = 0; i < 5; i++) {
            processOneBlock(pipeline);
        }

        List<AudioClip> clips = stopRecording(pipeline);

        // No lap and no take lane should be created when loopRecord is off,
        // even though the transport is looping: the take is one session.
        assertThat(pipeline.getLapClips()).isEmpty();
        assertThat(pipeline.getRecordedTakeLanes()).isEmpty();
        assertThat(track.getTakeComping().isActive()).isFalse();
        // Exactly one "normal" recorded clip.
        assertThat(clips).hasSize(1);
        assertThat(track.getClips()).containsExactlyElementsOf(clips);
    }

    /** The beats {@code blocks} buffer-lengths span at the fixture's tempo and rate. */
    private double beatsOfBlocks(int blocks) {
        double samplesPerBeat = format.sampleRate() * 60.0 / BPM;
        return (blocks * (double) BUFFER_SIZE) / samplesPerBeat;
    }

    /**
     * Feeds one block whose frames carry a ramp of 16-bit codes continuing
     * from {@code firstCode} (frame n of the block is code
     * {@code firstCode + n}), waits for the flush thread, and advances the
     * transport. Returns the next code.
     */
    private int processOneRampBlock(RecordingPipeline pipeline, int firstCode) {
        float[][] input = new float[2][BUFFER_SIZE];
        float[][] output = new float[2][BUFFER_SIZE];
        int code = firstCode;
        for (int f = 0; f < BUFFER_SIZE; f++) {
            float v = code++ / 32767f;
            input[0][f] = v;
            input[1][f] = v;
        }
        audioEngine.processBlock(input, output, BUFFER_SIZE);
        pipeline.awaitFlushed();
        advanceTransportByBufferSize();
        return code;
    }

    @Test
    void lapsAfterAWrapArePlacedAtTheLoopStartAndTheCompPlaysTheSelectedLapAtItsTimelineBeat() {
        // Story 328 review: Record pressed INSIDE the loop. Lap 1 runs from
        // the record position P to the loop end; every later lap begins at
        // the loop start and must be placed there — not at P — so the comp
        // of the selected lap plays each beat's audio at that beat.
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        int blocksPerLoop = 4;
        double loopStart = beatsOfBlocks(4);
        double loopEnd = loopStart + beatsOfBlocks(blocksPerLoop);
        double recordPosition = loopStart + beatsOfBlocks(2);
        transport.setLoopRegion(loopStart, loopEnd);
        transport.setLoopEnabled(true);
        transport.setPositionInBeats(recordPosition);

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        pipeline.setLoopRecord(true);
        startRecording(pipeline);

        // Lap 1: 2 blocks (P → loop end); laps 2 and 3: 4 blocks each; one
        // more block wraps lap 3 and opens the partial lap 4.
        int code = 0;
        for (int b = 0; b < 2 + 2 * blocksPerLoop + 1; b++) {
            code = processOneRampBlock(pipeline, code);
        }
        assertThat(code).as("fixture: the ramp stays inside 16 bits").isLessThan(32_768);

        List<AudioClip> clips = stopRecording(pipeline);

        TakeComping comping = track.getTakeComping();
        assertThat(comping.getTakeLaneCount()).as("laps 1-3 whole, lap 4 partial").isEqualTo(4);
        AudioClip lapOne = comping.getTakeLane(0).getClips().getFirst();
        AudioClip lapTwo = comping.getTakeLane(1).getClips().getFirst();
        AudioClip lapThree = comping.getTakeLane(2).getClips().getFirst();
        AudioClip lapFour = comping.getTakeLane(3).getClips().getFirst();
        assertThat(lapOne.getStartBeat()).as("lap 1 starts at the record position").isCloseTo(recordPosition, offset(1e-9));
        assertThat(lapOne.getDurationBeats()).isCloseTo(beatsOfBlocks(2), offset(1e-9));
        assertThat(lapTwo.getStartBeat()).as("lap 2 starts at the loop start").isCloseTo(loopStart, offset(1e-9));
        assertThat(lapThree.getStartBeat()).as("lap 3 starts at the loop start").isCloseTo(loopStart, offset(1e-9));
        assertThat(lapFour.getStartBeat()).as("the partial lap 4 starts at the loop start").isCloseTo(loopStart, offset(1e-9));
        assertThat(lapTwo.getDurationBeats()).isCloseTo(beatsOfBlocks(blocksPerLoop), offset(1e-9));
        assertThat(lapThree.getDurationBeats()).isCloseTo(beatsOfBlocks(blocksPerLoop), offset(1e-9));

        // The last whole lap (lap 3) is selected over its own extent, at the loop start.
        assertThat(comping.getCompRegions())
                .containsExactly(new CompRegion(2, lapThree.getStartBeat(), lapThree.getDurationBeats()));
        assertThat(clips).hasSize(1);
        AudioClip comp = clips.getFirst();
        assertThat(comp.getStartBeat()).isCloseTo(loopStart, offset(1e-9));
        assertThat(comp.getDurationBeats()).isCloseTo(beatsOfBlocks(blocksPerLoop), offset(1e-9));
        assertThat(comp.getSourceOffsetBeats()).isCloseTo(0.0, offset(1e-9));

        // At timeline beat loopStart + 1 block the comp plays lap 3's sample
        // at that beat: lap 3 is blocks 6-9 of the ramp, so one block into
        // it the code is 7 × BUFFER_SIZE.
        double probeBeat = loopStart + beatsOfBlocks(1);
        int offsetInComp = (int) Math.round((probeBeat - comp.getStartBeat()) * format.sampleRate() * 60.0 / BPM);
        int offsetInLap = (int) Math.round((probeBeat - lapThree.getStartBeat()) * format.sampleRate() * 60.0 / BPM);
        float[][] compAudio = RecordedAudioTestSupport.audioOnDisk(comp);
        float[][] lapAudio = RecordedAudioTestSupport.audioOnDisk(lapThree);
        assertThat(offsetInComp).isEqualTo(BUFFER_SIZE);
        assertThat(compAudio[0][offsetInComp]).isEqualTo(lapAudio[0][offsetInLap]);
        // Code n decodes from the 16-bit segment file as n / 32768.
        assertThat(compAudio[0][offsetInComp]).as("the sample recorded at that beat").isEqualTo((7 * BUFFER_SIZE) / 32768f);
        assertThat(lapAudio[0][0]).as("lap 3 begins with block 6").isEqualTo((6 * BUFFER_SIZE) / 32768f);
    }

    @Test
    void undoOfALaterTakeOverTheSameLoopRestoresTheEarlierTakesSelectionAndCompClip() {
        // Story 328 review: ingesting take B's default region deselects take
        // A's overlapping region. The published take's undo entry restores
        // the selection A had and recompiles, so A's comp plays again.
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        int blocksPerLoop = 4;
        setUpLoopOfExactBlocks(blocksPerLoop);
        TakeComping comping = track.getTakeComping();

        RecordingPipeline takeA = new RecordingPipeline(audioEngine, transport, format, tempDir.resolve("a"), List.of(track));
        takeA.setLoopRecord(true);
        startRecording(takeA);
        for (int i = 0; i < 2 * blocksPerLoop + 1; i++) {
            processOneBlock(takeA);
        }
        List<AudioClip> clipsA = stopRecording(takeA);
        List<TakeLane> lanesA = List.copyOf(comping.getTakeLanes());
        List<CompRegion> regionsA = List.copyOf(comping.getCompRegions());
        assertThat(lanesA).hasSize(3);
        assertThat(regionsA).containsExactly(new CompRegion(1, lanesA.get(1).getClips().getFirst().getStartBeat(),
                lanesA.get(1).getClips().getFirst().getDurationBeats()));
        assertThat(clipsA).hasSize(1);
        AudioClip compA = clipsA.getFirst();
        assertThat(track.getClips()).containsExactly(compA);

        transport.setPositionInBeats(0.0);
        RecordingPipeline takeB = new RecordingPipeline(audioEngine, transport, format, tempDir.resolve("b"), List.of(track));
        takeB.setLoopRecord(true);
        startRecording(takeB);
        for (int i = 0; i < blocksPerLoop + 1; i++) {
            processOneBlock(takeB);
        }
        List<AudioClip> clipsB = stopRecording(takeB);
        assertThat(comping.getTakeLaneCount()).isEqualTo(5);
        assertThat(comping.getCompRegions()).as("take A's overlapping region was deselected")
                .containsExactly(new CompRegion(3, comping.getTakeLane(3).getClips().getFirst().getStartBeat(),
                        comping.getTakeLane(3).getClips().getFirst().getDurationBeats()));
        assertThat(takeB.getCompSelectionBeforeIngestion()).containsEntry(track, regionsA);
        assertThat(clipsB).hasSize(1);
        assertThat(track.getClips()).containsExactlyElementsOf(clipsB);

        UndoManager history = new UndoManager();
        RecordedTakeAction entryB = new RecordedTakeAction(takeB);
        history.execute(entryB);
        assertThat(comping.getTakeLaneCount()).as("the first execute finds the take applied and changes nothing").isEqualTo(5);
        assertThat(track.getClips()).containsExactlyElementsOf(clipsB);

        history.undo();
        assertThat(comping.getTakeLanes()).containsExactlyElementsOf(lanesA);
        assertThat(comping.getCompRegions()).isEqualTo(regionsA);
        assertThat(track.getClips()).hasSize(1);
        AudioClip restored = track.getClips().getFirst();
        assertThat(comping.compOutputOn(track)).containsExactly(restored);
        assertThat(restored.getStartBeat()).isEqualTo(compA.getStartBeat());
        assertThat(restored.getDurationBeats()).isEqualTo(compA.getDurationBeats());
        assertThat(restored.getSourceOffsetBeats()).isEqualTo(compA.getSourceOffsetBeats());
        assertThat(restored.getSourceSegmentPaths()).isEqualTo(compA.getSourceSegmentPaths());
        assertThat(restored.getSourceRateMetadata()).isEqualTo(compA.getSourceRateMetadata());
        assertThat(RecordedAudioTestSupport.audioOnDisk(restored)[0])
                .containsExactly(RecordedAudioTestSupport.audioOnDisk(compA)[0]);

        history.redo();
        assertThat(comping.getTakeLaneCount()).isEqualTo(5);
        assertThat(comping.getCompRegions()).containsExactly(new CompRegion(3,
                comping.getTakeLane(3).getClips().getFirst().getStartBeat(),
                comping.getTakeLane(3).getClips().getFirst().getDurationBeats()));
        assertThat(track.getClips()).hasSize(1);
        assertThat(track.getClips().getFirst().getSourceSegmentPaths())
                .isEqualTo(comping.getTakeLane(3).getClips().getFirst().getSourceSegmentPaths());
    }

    @Test
    void aTakeRecordedDuringALaneAuditionEndsTheAuditionAndPublishesItsOwnCompClip() {
        // Story 328 review round 2: an Alt-click audition (lane solo) makes
        // compile() play that lane whole and slice no region, so a take
        // recorded meanwhile would own no clip — no undo entry, project not
        // dirty. The audition is transient: the new take clears it before
        // ingesting its laps and publishes its comp clip; the undo restores
        // take A's selection (the solo stays cleared).
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        int blocksPerLoop = 4;
        setUpLoopOfExactBlocks(blocksPerLoop);
        TakeComping comping = track.getTakeComping();

        RecordingPipeline takeA = new RecordingPipeline(audioEngine, transport, format, tempDir.resolve("a"), List.of(track));
        takeA.setLoopRecord(true);
        startRecording(takeA);
        for (int i = 0; i < 2 * blocksPerLoop + 1; i++) {
            processOneBlock(takeA);
        }
        stopRecording(takeA);
        List<TakeLane> lanesA = List.copyOf(comping.getTakeLanes());
        List<CompRegion> regionsA = List.copyOf(comping.getCompRegions());
        assertThat(lanesA).hasSize(3);
        // The audition as the COMP tool's Alt-click runs it (CompToolHandler.altClickLane).
        comping.getTakeLane(1).setSoloed(true);
        new CompileCompAction(track).execute();
        AudioClip laneOne = comping.getTakeLane(1).getClips().getFirst();
        assertThat(track.getClips()).as("premise: the audition plays lane 1 whole").singleElement()
                .satisfies(audition -> {
                    assertThat(audition.getSourceSegmentPaths()).isEqualTo(laneOne.getSourceSegmentPaths());
                    assertThat(audition.getStartBeat()).isEqualTo(laneOne.getStartBeat());
                    assertThat(audition.getDurationBeats()).isEqualTo(laneOne.getDurationBeats());
                });

        transport.setPositionInBeats(0.0);
        RecordingPipeline takeB = new RecordingPipeline(audioEngine, transport, format, tempDir.resolve("b"), List.of(track));
        takeB.setLoopRecord(true);
        startRecording(takeB);
        for (int i = 0; i < blocksPerLoop + 1; i++) {
            processOneBlock(takeB);
        }
        List<AudioClip> clipsB = stopRecording(takeB);
        assertThat(comping.getTakeLanes()).noneMatch(TakeLane::isSoloed);
        assertThat(clipsB).as("take B owns its comp clip").hasSize(1);
        assertThat(clipsB.getFirst().getSourceSegmentPaths())
                .isEqualTo(comping.getTakeLane(3).getClips().getFirst().getSourceSegmentPaths());
        assertThat(track.getClips()).containsExactlyElementsOf(clipsB);
        assertThat(takeB.getCompSelectionBeforeIngestion()).containsEntry(track, regionsA);

        UndoManager history = new UndoManager();
        history.execute(new RecordedTakeAction(takeB));
        history.undo();
        assertThat(comping.getTakeLanes()).containsExactlyElementsOf(lanesA);
        assertThat(comping.getCompRegions()).isEqualTo(regionsA);
        assertThat(comping.getTakeLanes()).as("the audition is not restored").noneMatch(TakeLane::isSoloed);
        assertThat(track.getClips()).hasSize(1);
    }

    @Test
    void aLaterTakeOverAnotherLoopRangeCountsOnlyItsOwnCompClipAndItsUndoLeavesTheEarlierCompInPlace() {
        // Story 328 review: a recompile re-slices the earlier take's
        // surviving region too; that slice is not a clip this take produced.
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        int blocksPerLoop = 4;
        setUpLoopOfExactBlocks(blocksPerLoop);
        TakeComping comping = track.getTakeComping();

        RecordingPipeline takeA = new RecordingPipeline(audioEngine, transport, format, tempDir.resolve("a"), List.of(track));
        takeA.setLoopRecord(true);
        startRecording(takeA);
        for (int i = 0; i < blocksPerLoop + 1; i++) {
            processOneBlock(takeA);
        }
        List<AudioClip> clipsA = stopRecording(takeA);
        assertThat(clipsA).hasSize(1);
        List<CompRegion> regionsA = List.copyOf(comping.getCompRegions());

        // Take B over a loop that does not overlap take A's.
        double laterStart = beatsOfBlocks(8);
        transport.setLoopRegion(laterStart, laterStart + beatsOfBlocks(blocksPerLoop));
        transport.setPositionInBeats(laterStart);
        RecordingPipeline takeB = new RecordingPipeline(audioEngine, transport, format, tempDir.resolve("b"), List.of(track));
        takeB.setLoopRecord(true);
        startRecording(takeB);
        for (int i = 0; i < blocksPerLoop + 1; i++) {
            processOneBlock(takeB);
        }
        List<AudioClip> clipsB = stopRecording(takeB);

        assertThat(comping.getCompRegions()).as("take A's region survives beside take B's").hasSize(2)
                .containsAll(regionsA);
        assertThat(track.getClips()).as("both comps play").hasSize(2);
        assertThat(clipsB).as("take B produced one clip: its own lap's slice").hasSize(1);
        assertThat(clipsB.getFirst().getStartBeat()).isCloseTo(laterStart, offset(1e-9));
        assertThat(takeB.getRecordedClips()).containsEntry(track, clipsB.getFirst());
        assertThat(clipsB.getFirst().getSourceSegmentPaths())
                .isEqualTo(comping.getTakeLane(2).getClips().getFirst().getSourceSegmentPaths());

        UndoManager history = new UndoManager();
        history.execute(new RecordedTakeAction(takeB));
        history.undo();
        assertThat(comping.getTakeLaneCount()).isEqualTo(2);
        assertThat(comping.getCompRegions()).isEqualTo(regionsA);
        assertThat(track.getClips()).hasSize(1);
        assertThat(track.getClips().getFirst().getSourceSegmentPaths())
                .isEqualTo(clipsA.getFirst().getSourceSegmentPaths());
        assertThat(comping.compOutputOn(track)).containsExactlyElementsOf(track.getClips());
    }

    // ─────────────────────────────────────────────────────────────────────
    // Loop-record under punch (PR #984 review): a lane starts at the first
    // frame it actually wrote, less the compensation — not at the header
    // beat of the block a wrap routed into it.
    // ─────────────────────────────────────────────────────────────────────

    /** 256-frame blocks at 48 kHz: 24 000 frames per beat at the fixture's 120 BPM. */
    private static final int SMALL_BLOCK = 256;
    private static final double FRAMES_PER_BEAT = 24_000.0;
    /** {@code RoundTripLatency(128, 128, 0)}: 256 frames, in beats. */
    private static final double COMPENSATION_BEATS = 256 / FRAMES_PER_BEAT;

    /** Switches the fixture to 48 kHz and 256-frame blocks. */
    private void useFortyEightKilohertzBlocksOf256() {
        format = new AudioFormat(48_000.0, 2, 16, SMALL_BLOCK);
        audioEngine = new AudioEngine(format);
    }

    /** A loop-record pipeline for {@code track} whose take is compensated by 256 frames. */
    private RecordingPipeline compensatedLoopRecordPipeline(Track track) {
        RecordingPipeline pipeline = new RecordingPipeline(audioEngine, transport, format, tempDir, List.of(track));
        pipeline.setLoopRecord(true);
        pipeline.setReportedLatency(new RoundTripLatency(128, 128, 0));
        pipeline.setApplyLatencyCompensation(true);
        return pipeline;
    }

    /**
     * Feeds one 256-frame block whose every frame carries the 16-bit code
     * {@code code}, waits for the flush thread to apply it, and advances the
     * transport by the block.
     */
    private void processOneCodedBlock(RecordingPipeline pipeline, int code) {
        float[][] input = new float[2][SMALL_BLOCK];
        for (float[] channel : input) {
            Arrays.fill(channel, code / 32767f);
        }
        audioEngine.processBlock(input, new float[2][SMALL_BLOCK], SMALL_BLOCK);
        pipeline.awaitFlushed();
        transport.advancePosition(SMALL_BLOCK / FRAMES_PER_BEAT);
    }

    /** The code a block carries: its lap (from 0) and its block index within the loop. */
    private static int codeOf(int lap, int blockInLoop) {
        return lap * 1000 + blockInLoop;
    }

    /** An armed audio track. */
    private static Track armedTrack() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        return track;
    }

    @Test
    void lapsOfAPunchedLoopStartWhereTheirFirstSliceWasWrittenLessTheCompensation() {
        // Loop [0, 8), punch [4, 6), Record from 0: nothing is written before
        // beat 4 in any lap, so every lap — the wrapped ones included — starts
        // at beat 4 less the compensation, not at the loop start.
        useFortyEightKilohertzBlocksOf256();
        Track track = armedTrack();
        transport.setLoopRegion(0.0, 8.0);
        transport.setLoopEnabled(true);
        transport.setPunchRegion(new PunchRegion(96_000L, 144_000L, true));
        RecordingPipeline pipeline = compensatedLoopRecordPipeline(track);
        startRecording(pipeline);
        assertThat(pipeline.getSession(track).getCompensationFrames()).as("fixture: 256 frames").isEqualTo(256L);

        // Three whole laps, then lap 4 up to just past beat 5.
        int lap = 0;
        double previous = -1.0;
        while (true) {
            double position = transport.getPositionInBeats();
            if (position < previous) {
                lap++;
            }
            if (lap == 3 && position > 5.0) {
                break;
            }
            previous = position;
            processOneCodedBlock(pipeline, codeOf(lap, (int) Math.round(position * FRAMES_PER_BEAT / SMALL_BLOCK)));
        }
        long lapFourEndFrame = Math.round(transport.getPositionInBeats() * FRAMES_PER_BEAT);

        List<AudioClip> clips = stopRecording(pipeline);

        TakeComping comping = track.getTakeComping();
        assertThat(comping.getTakeLaneCount()).as("laps 1-3 whole, lap 4 partial").isEqualTo(4);
        double punchedStart = 4.0 - COMPENSATION_BEATS;
        for (int i = 0; i < 3; i++) {
            AudioClip lapClip = comping.getTakeLane(i).getClips().getFirst();
            assertThat(lapClip.getStartBeat()).as("lap %d starts at the punch-in less the compensation", i + 1)
                    .isCloseTo(punchedStart, offset(1e-9));
            assertThat(lapClip.getDurationBeats()).as("lap %d holds the punch region", i + 1)
                    .isCloseTo(2.0, offset(1e-9));
            assertThat(lapClip.getSourceRateMetadata().framesPerChannel()).isEqualTo(48_000L);
        }
        AudioClip lapFour = comping.getTakeLane(3).getClips().getFirst();
        assertThat(lapFour.getStartBeat()).isCloseTo(punchedStart, offset(1e-9));
        assertThat(lapFour.getDurationBeats()).as("lap 4 from the punch-in to the stop")
                .isCloseTo((lapFourEndFrame - 96_000) / FRAMES_PER_BEAT, offset(1e-9))
                .isCloseTo(1.0, offset(SMALL_BLOCK / FRAMES_PER_BEAT));

        // The last whole lap (lap 3) is selected over its own extent and plays.
        AudioClip lapThree = comping.getTakeLane(2).getClips().getFirst();
        assertThat(comping.getCompRegions())
                .containsExactly(new CompRegion(2, lapThree.getStartBeat(), lapThree.getDurationBeats()));
        assertThat(clips).hasSize(1);
        AudioClip comp = clips.getFirst();
        assertThat(track.getClips()).containsExactly(comp);
        assertThat(comp.getStartBeat()).isCloseTo(punchedStart, offset(1e-9));
        assertThat(comp.getDurationBeats()).isCloseTo(2.0, offset(1e-9));
        assertThat(comp.getEndBeat()).isCloseTo(6.0 - COMPENSATION_BEATS, offset(1e-9));

        // At timeline beat 5 the comp plays what lap 3 captured 256 frames
        // later (the round trip): transport frame 120 256, inside block 469.
        int offsetInComp = (int) Math.round((5.0 - comp.getStartBeat()) * FRAMES_PER_BEAT);
        assertThat(offsetInComp).isEqualTo(24_000 + 256);
        float[][] compAudio = RecordedAudioTestSupport.audioOnDisk(comp);
        assertThat(compAudio[0][offsetInComp]).as("the sample lap 3 recorded for beat 5")
                .isEqualTo(codeOf(2, 469) / 32768f);
    }

    @Test
    void aLoopRecordStartedInsideThePunchStartsLapOneAtThePlayheadAndLaterLapsAtThePunchIn() {
        // Record from beat 5 inside punch [4, 6) on loop [0, 8): lap 1 is
        // written from 5, every later lap from the punch-in at 4.
        useFortyEightKilohertzBlocksOf256();
        Track track = armedTrack();
        transport.setLoopRegion(0.0, 8.0);
        transport.setLoopEnabled(true);
        transport.setPunchRegion(new PunchRegion(96_000L, 144_000L, true));
        transport.setPositionInBeats(5.0);
        RecordingPipeline pipeline = compensatedLoopRecordPipeline(track);
        startRecording(pipeline);

        // Lap 1 to the loop end, then lap 2 through its punch region.
        int lap = 0;
        double previous = -1.0;
        while (true) {
            double position = transport.getPositionInBeats();
            if (position < previous) {
                lap++;
            }
            if (lap == 1 && position > 6.1) {
                break;
            }
            previous = position;
            processOneCodedBlock(pipeline, codeOf(lap, (int) Math.round(position * FRAMES_PER_BEAT / SMALL_BLOCK)));
        }

        List<AudioClip> clips = stopRecording(pipeline);

        TakeComping comping = track.getTakeComping();
        assertThat(comping.getTakeLaneCount()).isEqualTo(2);
        AudioClip lapOne = comping.getTakeLane(0).getClips().getFirst();
        AudioClip lapTwo = comping.getTakeLane(1).getClips().getFirst();
        assertThat(lapOne.getStartBeat()).as("lap 1 starts at the record position less the compensation")
                .isCloseTo(5.0 - COMPENSATION_BEATS, offset(1e-9));
        assertThat(lapOne.getDurationBeats()).as("from the playhead to the punch-out").isCloseTo(1.0, offset(1e-9));
        assertThat(lapTwo.getStartBeat()).as("lap 2 starts at the punch-in less the compensation")
                .isCloseTo(4.0 - COMPENSATION_BEATS, offset(1e-9));
        assertThat(lapTwo.getDurationBeats()).isCloseTo(2.0, offset(1e-9));
        assertThat(clips).as("the whole lap 1 is the selection").singleElement()
                .matches(comp -> Math.abs(comp.getStartBeat() - lapOne.getStartBeat()) < 1e-9
                        && Math.abs(comp.getDurationBeats() - 1.0) < 1e-9);
    }

    @Test
    void aPunchInThatIsNotBlockAlignedPlacesEachLapAtItsPunchInFrame() {
        // Loop [3.9, 4.6) and punch-in at frame 96 100 — 4.0041667 beats,
        // inside a block both at the record start (blocks from frame 93 600)
        // and after every wrap: the lap starts at that frame, not at the
        // header beat of the block it lies in.
        useFortyEightKilohertzBlocksOf256();
        Track track = armedTrack();
        transport.setLoopRegion(3.9, 4.6);
        transport.setLoopEnabled(true);
        transport.setPunchRegion(new PunchRegion(96_100L, 105_600L, true));
        transport.setPositionInBeats(3.9);
        RecordingPipeline pipeline = compensatedLoopRecordPipeline(track);
        startRecording(pipeline);

        int lap = 0;
        double previous = -1.0;
        while (true) {
            double position = transport.getPositionInBeats();
            if (position < previous) {
                lap++;
            }
            if (lap == 2 && position > 4.5) {
                break;
            }
            previous = position;
            processOneLapCodedBlock(pipeline, lap);
        }

        stopRecording(pipeline);

        TakeComping comping = track.getTakeComping();
        assertThat(comping.getTakeLaneCount()).isEqualTo(3);
        double punchedStart = 96_100 / FRAMES_PER_BEAT - COMPENSATION_BEATS;
        AudioClip lapOne = comping.getTakeLane(0).getClips().getFirst();
        assertThat(lapOne.getStartBeat()).as("lap 1: frame 96 100 lies 196 frames into a block")
                .isCloseTo(punchedStart, offset(1e-9));
        for (int i = 1; i < 3; i++) {
            // A wrapped block's header carries a beat its start frame is
            // rounded from, so the slice is placed to within half a frame.
            assertThat(comping.getTakeLane(i).getClips().getFirst().getStartBeat()).as("lap %d", i + 1)
                    .isCloseTo(punchedStart, offset(0.5 / FRAMES_PER_BEAT));
        }
        // Lap 1's blocks run from frame 93 600, so its punch-in block keeps
        // exactly 60 frames, all under the punch-in fade: those, not the
        // block's first 60, open its file, on both channels.
        float[][] lapOneOnDisk = RecordedAudioTestSupport.audioOnDisk(lapOne);
        for (int channel = 0; channel < 2; channel++) {
            assertThat(Arrays.copyOf(lapOneOnDisk[channel], 60))
                    .as("lap 1, channel %d: the punch-in block's slice, faded in", channel)
                    .containsExactly(fadedLapRamp(0, channel, 96_100L, 60, 60, 0));
        }
        // Every lap's file holds the punch-in to the punch-out:
        // 105 600 − 96 100 = 9 500 frames. The loop [3.9, 4.6) is frames
        // [93 600, 110 400), 16 800 frames, which 256-frame blocks overrun by
        // 66 × 256 − 16 800 = 96 frames on lap 1 and, from 93 696, by
        // 66 × 256 − (110 400 − 93 696) = 192 on lap 2; so laps 1-3 run their
        // blocks from frames 93 600, 93 696 and 93 792. The punch-out lies
        // (105 600 − first block frame) mod 256 frames into its block:
        // 12 000 − 46 × 256 = 224 for lap 1, 11 904 − 11 776 = 128 for lap 2
        // and 11 808 − 11 776 = 32 for lap 3. That block keeps those frames,
        // all under the punch-out fade (the 5 ms, 240-frame fade cut to the
        // slice), and they close the file; the frame before them ends a
        // block wholly inside the punch region, unfaded.
        int[] punchOutSliceFrames = {224, 128, 32};
        long punchOutFrame = 105_600L;
        int lapFrames = (int) (punchOutFrame - 96_100L);
        for (int i = 0; i < 3; i++) {
            AudioClip lapClip = comping.getTakeLane(i).getClips().getFirst();
            assertThat(lapClip.getSourceOffsetBeats()).isZero();
            float[][] onDisk = RecordedAudioTestSupport.audioOnDisk(lapClip);
            assertThat(onDisk).as("lap %d: a stereo file", i + 1).hasNumberOfRows(2);
            int frameForBeat = (int) Math.round((4.3 - lapClip.getStartBeat()) * FRAMES_PER_BEAT);
            int tail = punchOutSliceFrames[i];
            for (int channel = 0; channel < 2; channel++) {
                assertThat(onDisk[channel]).as("lap %d, channel %d: the punch-in to the punch-out", i + 1, channel)
                        .hasSize(lapFrames);
                assertThat(Arrays.copyOfRange(onDisk[channel], lapFrames - tail, lapFrames))
                        .as("lap %d, channel %d: the punch-out block's %d frames, faded out", i + 1, channel, tail)
                        .containsExactly(fadedLapRamp(i, channel, punchOutFrame - tail, tail, 0, tail));
                assertThat(onDisk[channel][lapFrames - tail - 1])
                        .as("lap %d, channel %d: the last sample before the fade-out", i + 1, channel)
                        .isEqualTo(RecordedAudioTestSupport.decoded16(
                                lapSample(i, channel, punchOutFrame - tail - 1)));
                // Frame 300 lies past the punch-in block, whose fade covers
                // only that block's slice — 60, 156 and 240 frames for laps
                // 1-3, whose blocks run from frames 93 600, 93 696 and
                // 93 792 — so each lane's file holds its own lap's frames
                // from the punch-in on, not from the start of the block.
                assertThat(onDisk[channel][300])
                        .as("lane %d, channel %d: the sample its lap captured 300 frames after the punch-in", i, channel)
                        .isEqualTo(RecordedAudioTestSupport.decoded16(lapSample(i, channel, 96_100L + 300)));
                // Timeline beat 4.3 plays what the lap captured 256 frames
                // later; a wrapped lap's half-frame placement may land on the
                // neighbour.
                assertThat(onDisk[channel][frameForBeat])
                        .as("lane %d, channel %d: the sample it plays at beat 4.3", i, channel)
                        .isCloseTo(RecordedAudioTestSupport.decoded16(lapSample(i, channel, 103_200L + 256)),
                                offset(1.5f / 32768f));
            }
        }
    }

    /** Transport frame the lap-coded ramp counts from: code 0 at frame 96 000, beat 4. */
    private static final long RAMP_ORIGIN_FRAME = 96_000L;

    /**
     * Code distance between laps: more than the 9 500 frames a lap of the
     * non-block-aligned test writes, so no code is shared by two laps, and
     * small enough that every code fed stays within 16 bits: the highest is
     * that of lap 3's last frame, 2 × 10 000 + (108 127 − 96 000) = 32 127.
     */
    private static final int LAP_CODE_STRIDE = 10_000;

    /**
     * Feeds one 256-frame block whose every frame carries its own 16-bit
     * code ({@link #lapSample}): channel 0 names the lap and the transport
     * frame it was captured at, channel 1 the same code negated. Then waits
     * for the flush thread and advances the transport by the block.
     */
    private void processOneLapCodedBlock(RecordingPipeline pipeline, int lap) {
        // As the capture callback stamps a block's start frame (120 BPM, 48 kHz).
        long blockStartFrame = Math.round(transport.getPositionInBeats() * 60.0 / 120.0 * 48_000.0);
        float[][] input = new float[2][SMALL_BLOCK];
        for (int channel = 0; channel < 2; channel++) {
            for (int i = 0; i < SMALL_BLOCK; i++) {
                input[channel][i] = lapSample(lap, channel, blockStartFrame + i);
            }
        }
        audioEngine.processBlock(input, new float[2][SMALL_BLOCK], SMALL_BLOCK);
        pipeline.awaitFlushed();
        transport.advancePosition(SMALL_BLOCK / FRAMES_PER_BEAT);
    }

    /**
     * The sample lap {@code lap} (from 0) carries at transport frame
     * {@code frame} on {@code channel}: the code
     * {@code lap × LAP_CODE_STRIDE + (frame − RAMP_ORIGIN_FRAME)}, negated on
     * channel 1.
     */
    private static float lapSample(int lap, int channel, long frame) {
        int code = lap * LAP_CODE_STRIDE + (int) (frame - RAMP_ORIGIN_FRAME);
        return (channel == 0 ? code : -code) / 32767f;
    }

    /**
     * What a file holds on {@code channel} for the {@code frames}-frame slice
     * a block keeps from {@code firstFrame}: the lap's codes captured from
     * that frame, under a cosine fade-in over its first {@code fadeIn} frames
     * and a fade-out over its last {@code fadeOut}, decoded.
     */
    private static float[] fadedLapRamp(int lap, int channel, long firstFrame, int frames,
                                        int fadeIn, int fadeOut) {
        float[][] slice = new float[1][frames];
        for (int i = 0; i < frames; i++) {
            slice[0][i] = lapSample(lap, channel, firstFrame + i);
        }
        CaptureFlushService.applyCosineFades(slice, 1, frames, fadeIn, fadeOut);
        float[] decoded = new float[frames];
        for (int i = 0; i < frames; i++) {
            decoded[i] = RecordedAudioTestSupport.decoded16(slice[0][i]);
        }
        return decoded;
    }

    @Test
    void aLapThatCapturedNothingAddsNoLaneAndNoClip() {
        // Punch [10, 12) outside loop [0, 0.5): no lap writes a frame.
        useFortyEightKilohertzBlocksOf256();
        Track track = armedTrack();
        transport.setLoopRegion(0.0, 0.5);
        transport.setLoopEnabled(true);
        transport.setPunchRegion(new PunchRegion(240_000L, 288_000L, true));
        RecordingPipeline pipeline = compensatedLoopRecordPipeline(track);
        startRecording(pipeline);

        for (int block = 0; block < 100; block++) { // two wraps and more
            processOneCodedBlock(pipeline, 1);
        }

        List<AudioClip> clips = stopRecording(pipeline);

        assertThat(clips).isEmpty();
        assertThat(track.getClips()).isEmpty();
        assertThat(pipeline.getLapClips()).isEmpty();
        assertThat(pipeline.getRecordedTakeLanes()).isEmpty();
        assertThat(track.getTakeComping().getTakeLaneCount()).as("no lane, so no Take number is used").isZero();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Undo and redo never replay a lane audition (PR #984 review).
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void redoAfterALaneAuditionPlaysTheSavedSelection() {
        Track track = armedTrack();
        int blocksPerLoop = 4;
        setUpLoopOfExactBlocks(blocksPerLoop);
        TakeComping comping = track.getTakeComping();
        RecordingPipeline take = new RecordingPipeline(audioEngine, transport, format, tempDir, List.of(track));
        take.setLoopRecord(true);
        startRecording(take);
        for (int i = 0; i < 2 * blocksPerLoop + 1; i++) {
            processOneBlock(take);
        }
        stopRecording(take);
        UndoManager history = new UndoManager();
        history.execute(new RecordedTakeAction(take));
        AudioClip selectedLap = comping.getTakeLane(1).getClips().getFirst();
        List<CompRegion> selection = List.copyOf(comping.getCompRegions());
        assertThat(selection).as("fixture: lap 2 is the selection")
                .containsExactly(new CompRegion(1, selectedLap.getStartBeat(), selectedLap.getDurationBeats()));

        // Alt-click lane 1 (not the selected lap), as CompToolHandler.altClickLane does it.
        auditionLane(track, 0);
        history.undo();
        // The redo ends an audition whatever lane holds it — even one of
        // the take's own lanes that kept its solo while it was off the comping.
        List<TakeLane> takeLanes = take.getRecordedTakeLanes().get(track);
        takeLanes.getFirst().setSoloed(true);
        history.redo();

        assertThat(comping.getTakeLanes()).noneMatch(TakeLane::isSoloed);
        assertThat(comping.getCompRegions()).isEqualTo(selection);
        assertThat(track.getClips()).as("the saved selection plays, not the audition").singleElement()
                .satisfies(comp -> {
                    assertThat(comp.getStartBeat()).isEqualTo(selectedLap.getStartBeat());
                    assertThat(comp.getDurationBeats()).isEqualTo(selectedLap.getDurationBeats());
                    assertThat(comp.getSourceSegmentPaths()).isEqualTo(selectedLap.getSourceSegmentPaths());
                });
    }

    @Test
    void undoAfterALaneAuditionPlaysThePreTakeSelection() {
        Track track = armedTrack();
        int blocksPerLoop = 4;
        setUpLoopOfExactBlocks(blocksPerLoop);
        TakeComping comping = track.getTakeComping();
        RecordingPipeline takeA = new RecordingPipeline(audioEngine, transport, format, tempDir.resolve("a"), List.of(track));
        takeA.setLoopRecord(true);
        startRecording(takeA);
        for (int i = 0; i < 2 * blocksPerLoop + 1; i++) {
            processOneBlock(takeA);
        }
        stopRecording(takeA);
        List<CompRegion> selectionA = List.copyOf(comping.getCompRegions());
        AudioClip selectedLapOfA = comping.getTakeLane(1).getClips().getFirst();

        transport.setPositionInBeats(0.0);
        RecordingPipeline takeB = new RecordingPipeline(audioEngine, transport, format, tempDir.resolve("b"), List.of(track));
        takeB.setLoopRecord(true);
        startRecording(takeB);
        for (int i = 0; i < blocksPerLoop + 1; i++) {
            processOneBlock(takeB);
        }
        stopRecording(takeB);
        UndoManager history = new UndoManager();
        history.execute(new RecordedTakeAction(takeB));

        // Alt-click an earlier lane of take A after take B.
        auditionLane(track, 0);
        history.undo();

        assertThat(comping.getTakeLanes()).noneMatch(TakeLane::isSoloed);
        assertThat(comping.getTakeLaneCount()).isEqualTo(3);
        assertThat(comping.getCompRegions()).isEqualTo(selectionA);
        assertThat(track.getClips()).as("take A's selection plays, not the audition").singleElement()
                .satisfies(comp -> {
                    assertThat(comp.getStartBeat()).isEqualTo(selectedLapOfA.getStartBeat());
                    assertThat(comp.getDurationBeats()).isEqualTo(selectedLapOfA.getDurationBeats());
                    assertThat(comp.getSourceSegmentPaths()).isEqualTo(selectedLapOfA.getSourceSegmentPaths());
                });
    }

    /** Solos lane {@code laneIndex} alone and recompiles — the Alt-click audition, outside the history. */
    private static void auditionLane(Track track, int laneIndex) {
        TakeComping comping = track.getTakeComping();
        for (int i = 0; i < comping.getTakeLaneCount(); i++) {
            comping.getTakeLane(i).setSoloed(i == laneIndex);
        }
        new CompileCompAction(track).execute();
        assertThat(track.getClips()).as("premise: the audition plays lane %d whole", laneIndex).singleElement()
                .matches(clip -> clip.getSourceSegmentPaths()
                        .equals(comping.getTakeLane(laneIndex).getClips().getFirst().getSourceSegmentPaths()));
    }

    @Test
    void loopRecordWithLoopPlaybackDisabledIsAPlainTake() {
        // Story 328 review: laps are loop passes; with loop playback off
        // nothing wraps, so the take is one session, not a one-lane stack.
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        setUpLoopOfExactBlocks(2);
        transport.setLoopEnabled(false);

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        pipeline.setLoopRecord(true);
        assertThat(pipeline.isLoopRecord()).as("the flag itself is kept").isTrue();
        startRecording(pipeline);

        for (int i = 0; i < 5; i++) {
            processOneBlock(pipeline);
        }

        List<AudioClip> clips = stopRecording(pipeline);

        assertThat(pipeline.getLapClips()).isEmpty();
        assertThat(pipeline.getRecordedTakeLanes()).isEmpty();
        assertThat(track.getTakeComping().isActive()).isFalse();
        assertThat(clips).hasSize(1);
        assertThat(clips.getFirst().getName()).startsWith("Recording — ");
        assertThat(track.getClips()).containsExactlyElementsOf(clips);
    }

    @Test
    void loopRecordFlagToggleBeforeStartTakesEffect() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));

        assertThat(pipeline.isLoopRecord()).isFalse();
        pipeline.setLoopRecord(true);
        assertThat(pipeline.isLoopRecord()).isTrue();
        pipeline.setLoopRecord(false);
        assertThat(pipeline.isLoopRecord()).isFalse();
    }
}
