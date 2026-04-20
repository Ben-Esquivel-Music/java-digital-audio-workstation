package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for loop-record behavior on {@link RecordingPipeline} (story 132):
 * each loop lap must finalize a distinct take under a {@link TakeGroup},
 * with contiguous sample counts across laps and sample-accurate loop
 * boundaries.
 */
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

    private void processOneBlock() {
        float[][] input = new float[2][BUFFER_SIZE];
        float[][] output = new float[2][BUFFER_SIZE];
        audioEngine.processBlock(input, output, BUFFER_SIZE);
        advanceTransportByBufferSize();
    }

    private void advanceTransportByBufferSize() {
        double samplesPerBeat = format.sampleRate() * 60.0 / transport.getTempo();
        transport.advancePosition(BUFFER_SIZE / samplesPerBeat);
    }

    @Test
    void tenConsecutiveLoopsProduceTenDistinctTakes() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);

        int blocksPerLoop = 4;
        setUpLoopOfExactBlocks(blocksPerLoop);

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        pipeline.setLoopRecord(true);
        pipeline.start();

        // Drive exactly 10 complete loop laps. Each lap is `blocksPerLoop`
        // buffer-sized blocks, so 10 laps need 40 blocks total. We also
        // drive one extra block to ensure the wrap from lap 10 is detected.
        int laps = 10;
        for (int i = 0; i < laps * blocksPerLoop; i++) {
            processOneBlock();
        }
        // One more block to trigger the wrap detection for lap 10.
        processOneBlock();

        List<AudioClip> clips = pipeline.stop();

        Map<Track, TakeGroup> groups = pipeline.getTakeGroups();
        assertThat(groups).containsKey(track);
        TakeGroup group = groups.get(track);

        // Expect at least ten takes (one per complete lap). The last lap is
        // finalized either by the wrap-detection on the 41st block or by
        // stop() — we tolerate one extra short take from the trailing frames.
        assertThat(group.size()).isGreaterThanOrEqualTo(10);

        // Every take references a distinct AudioClip.
        List<AudioClip> takeClips = group.takes().stream().map(Take::clip).toList();
        assertThat(takeClips).doesNotHaveDuplicates();

        // The active (first by default) take's clip is placed on the track.
        assertThat(clips).isNotEmpty();
        assertThat(track.getClips()).contains(group.activeClip());

        // The track itself exposes the take group (for UI / persistence).
        assertThat(track.getTakeGroup(group.id())).isNotNull();
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
        pipeline.start();

        // Drive three full loops.
        for (int i = 0; i < 3 * blocksPerLoop; i++) {
            processOneBlock();
        }
        // Trigger wrap detection for the 3rd lap.
        processOneBlock();

        pipeline.stop();

        TakeGroup group = pipeline.getTakeGroups().get(track);
        assertThat(group).isNotNull();
        assertThat(group.size()).isGreaterThanOrEqualTo(3);

        // First three takes should each have exactly `blocksPerLoop *
        // BUFFER_SIZE` sample frames — sample-accurate loop boundaries.
        int expectedFrames = blocksPerLoop * BUFFER_SIZE;
        for (int i = 0; i < 3; i++) {
            AudioClip clip = group.takes().get(i).clip();
            float[][] data = clip.getAudioData();
            assertThat(data).as("take %d has audio data", i).isNotNull();
            assertThat(data[0].length).as("take %d frame count", i).isEqualTo(expectedFrames);
        }
    }

    @Test
    void takesAreContiguousAcrossLoopWrap() {
        // Feed a ramp so we can verify that no input frames are dropped at
        // the loop seam: concatenating take N's tail with take N+1's head
        // should preserve a monotonically-increasing ramp.
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);

        int blocksPerLoop = 2;
        setUpLoopOfExactBlocks(blocksPerLoop);

        // Install a recording callback wrapper that feeds a ramp instead of
        // zeros. To do this we prepare our own input buffer each block.
        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        pipeline.setLoopRecord(true);
        pipeline.start();

        float[][] output = new float[2][BUFFER_SIZE];
        int totalBlocks = blocksPerLoop * 3 + 1; // 3 full laps + 1 trigger block
        int nextSample = 0;
        for (int b = 0; b < totalBlocks; b++) {
            float[][] input = new float[2][BUFFER_SIZE];
            for (int f = 0; f < BUFFER_SIZE; f++) {
                float v = (float) nextSample++;
                input[0][f] = v;
                input[1][f] = v;
            }
            audioEngine.processBlock(input, output, BUFFER_SIZE);
            advanceTransportByBufferSize();
        }

        pipeline.stop();

        TakeGroup group = pipeline.getTakeGroups().get(track);
        assertThat(group).isNotNull();
        assertThat(group.size()).isGreaterThanOrEqualTo(3);

        // Concatenate the first three takes and verify the samples form a
        // contiguous ramp (0, 1, 2, ...). This proves no frames were dropped
        // at loop wrap boundaries.
        int cursor = 0;
        for (int i = 0; i < 3; i++) {
            float[][] data = group.takes().get(i).clip().getAudioData();
            assertThat(data).isNotNull();
            for (int f = 0; f < data[0].length; f++) {
                assertThat(data[0][f])
                        .as("sample %d of take %d", f, i)
                        .isEqualTo((float) cursor);
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
        pipeline.start();

        for (int i = 0; i < 5; i++) {
            processOneBlock();
        }

        List<AudioClip> clips = pipeline.stop();

        // No take group should be created when loopRecord is off, even
        // though the transport is looping.
        assertThat(pipeline.getTakeGroups()).isEmpty();
        assertThat(track.getTakeGroups()).isEmpty();
        // Exactly one "normal" recorded clip.
        assertThat(clips).hasSize(1);
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
