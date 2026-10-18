package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.comping.CompRegion;
import com.benesquivelmusic.daw.core.comping.TakeComping;
import com.benesquivelmusic.daw.core.comping.TakeLane;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.audio.SourceRateMetadata;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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
        assertThat(comping.getCompClips()).containsExactlyElementsOf(clips);
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
        assertThat(comping.getCompClips()).containsExactly(restored);
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
        comping.getTakeLane(1).setSoloed(true);
        assertThat(comping.compile()).as("premise: the audition plays lane 1 whole").hasSize(1);

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
        assertThat(comping.getCompClips()).containsExactlyElementsOf(track.getClips());
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
