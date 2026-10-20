package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
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
import static com.benesquivelmusic.daw.core.recording.RecordedAudioTestSupport.audioOnDisk;
import static com.benesquivelmusic.daw.core.recording.RecordedAudioTestSupport.decoded16;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

@ExtendWith(CaptureFlushThreadLeakGuard.class)
class RecordingPipelineTest {

    @TempDir
    Path tempDir;

    private AudioEngine audioEngine;
    private Transport transport;
    private AudioFormat format;

    @BeforeEach
    void setUp() {
        format = new AudioFormat(44_100.0, 2, 16, 512);
        audioEngine = new AudioEngine(format);
        transport = new Transport();
    }

    /**
     * Advances the transport position by the given number of frames, matching
     * what {@code RenderPipeline} does after the recording callback fires.
     */
    private void advanceTransportByFrames(int numFrames) {
        double samplesPerBeat = format.sampleRate() * 60.0 / transport.getTempo();
        double deltaBeats = numFrames / samplesPerBeat;
        transport.advancePosition(deltaBeats);
    }

    @Test
    void shouldRejectEmptyArmedTracks() {
        assertThatThrownBy(() -> new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("armed");
    }

    @Test
    void shouldRejectNullArguments() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        List<Track> armed = List.of(track);

        assertThatThrownBy(() -> new RecordingPipeline(null, transport, format, tempDir, armed))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RecordingPipeline(audioEngine, null, format, tempDir, armed))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RecordingPipeline(audioEngine, transport, null, tempDir, armed))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RecordingPipeline(audioEngine, transport, format, null, armed))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldStartRecordingPipeline() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));

        startRecording(pipeline);

        assertThat(pipeline.isActive()).isTrue();
        assertThat(transport.getState()).isEqualTo(TransportState.RECORDING);
        assertThat(audioEngine.isRunning()).isTrue();
        assertThat(audioEngine.getRecordingCallback()).isNotNull();
        assertThat(pipeline.getSession(track)).isNotNull();
        assertThat(pipeline.getSession(track).isActive()).isTrue();
    }

    @Test
    void shouldRejectDoubleStart() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        startRecording(pipeline);

        assertThatThrownBy(() -> pipeline.prepare(audioEngine.getCaptureHaltGeneration()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldStopRecordingPipeline() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        startRecording(pipeline);

        List<AudioClip> clips = stopRecording(pipeline);

        assertThat(pipeline.isActive()).isFalse();
        assertThat(transport.getState()).isEqualTo(TransportState.STOPPED);
        assertThat(audioEngine.getRecordingCallback()).isNull();
        // No audio was captured, so no clips should be created
        assertThat(clips).isEmpty();
    }

    @Test
    void shouldReturnEmptyClipsWhenStoppedWhileInactive() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));

        List<AudioClip> clips = stopRecording(pipeline);

        assertThat(clips).isEmpty();
    }

    @Test
    void shouldRecordAndCreateClipsOnStop() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        startRecording(pipeline);

        // Simulate audio capture by calling processBlock
        float[][] input = new float[2][512];
        float[][] output = new float[2][512];
        for (int i = 0; i < 10; i++) {
            audioEngine.processBlock(input, output, 512);
        }

        List<AudioClip> clips = stopRecording(pipeline);

        assertThat(clips).hasSize(1);
        assertThat(clips.getFirst().getName()).contains("Audio 1");
        assertThat(track.getClips()).hasSize(1);
        assertThat(track.getClips().getFirst()).isEqualTo(clips.getFirst());
    }

    @Test
    void shouldRecordMultipleArmedTracks() {
        Track track1 = new Track("Audio 1", TrackType.AUDIO);
        track1.setArmed(true);
        Track track2 = new Track("Audio 2", TrackType.AUDIO);
        track2.setArmed(true);
        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track1, track2));
        startRecording(pipeline);

        // Simulate audio capture
        float[][] input = new float[2][512];
        float[][] output = new float[2][512];
        audioEngine.processBlock(input, output, 512);

        List<AudioClip> clips = stopRecording(pipeline);

        assertThat(clips).hasSize(2);
        assertThat(track1.getClips()).hasSize(1);
        assertThat(track2.getClips()).hasSize(1);
    }

    @Test
    void shouldReturnArmedTracks() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));

        assertThat(pipeline.getArmedTracks()).containsExactly(track);
    }

    @Test
    void shouldFindArmedTracks() {
        Track armed1 = new Track("Armed", TrackType.AUDIO);
        armed1.setArmed(true);
        Track unarmed = new Track("Unarmed", TrackType.AUDIO);
        Track armed2 = new Track("Armed 2", TrackType.MIDI);
        armed2.setArmed(true);

        List<Track> found = RecordingPipeline.findArmedTracks(List.of(armed1, unarmed, armed2));

        assertThat(found).containsExactly(armed1, armed2);
    }

    @Test
    void shouldReturnEmptyListWhenNoTracksArmed() {
        Track track = new Track("Track", TrackType.AUDIO);

        List<Track> found = RecordingPipeline.findArmedTracks(List.of(track));

        assertThat(found).isEmpty();
    }

    @Test
    void shouldCreateRecordingSessionsWithSegments() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        startRecording(pipeline);

        RecordingSession session = pipeline.getSession(track);
        assertThat(session).isNotNull();
        assertThat(session.getSegmentCount()).isEqualTo(1);
        assertThat(session.isActive()).isTrue();

        // Simulate some audio capture
        float[][] input = new float[2][512];
        float[][] output = new float[2][512];
        audioEngine.processBlock(input, output, 512);
        pipeline.awaitFlushed();

        assertThat(session.getTotalSamplesRecorded()).isGreaterThan(0);

        stopRecording(pipeline);

        assertThat(session.isActive()).isFalse();
    }

    @Test
    void shouldReturnRecordedClipsMap() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        startRecording(pipeline);

        float[][] input = new float[2][512];
        float[][] output = new float[2][512];
        audioEngine.processBlock(input, output, 512);

        stopRecording(pipeline);

        assertThat(pipeline.getRecordedClips()).containsKey(track);
        assertThat(pipeline.getRecordedClips().get(track)).isNotNull();
    }

    @Test
    void shouldCaptureRecordingStartBeat() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        transport.setPositionInBeats(8.0);

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        startRecording(pipeline);

        assertThat(pipeline.getRecordingStartBeat()).isEqualTo(8.0);

        // Simulate audio capture
        float[][] input = new float[2][512];
        float[][] output = new float[2][512];
        audioEngine.processBlock(input, output, 512);

        List<AudioClip> clips = stopRecording(pipeline);

        // Clip should be placed at the original start position, not at 0
        assertThat(clips).hasSize(1);
        assertThat(clips.getFirst().getStartBeat()).isEqualTo(8.0);
    }

    @Test
    void shouldSetRecordingIndicatorOnArmedTracks() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        assertThat(track.isRecording()).isFalse();

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        startRecording(pipeline);

        assertThat(track.isRecording()).isTrue();

        stopRecording(pipeline);

        assertThat(track.isRecording()).isFalse();
    }

    @Test
    void shouldDefaultToNoCountIn() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));

        assertThat(pipeline.getCountInMode()).isEqualTo(CountInMode.OFF);
    }

    @Test
    void shouldDefaultToMonitoringOff() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));

        assertThat(pipeline.getMonitoringMode()).isEqualTo(InputMonitoringMode.OFF);
    }

    @Test
    void shouldDefaultToNoPunchRange() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));

        assertThat(pipeline.getPunchRange()).isNull();
    }

    @Test
    void shouldAcceptCountInMode() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track),
                CountInMode.TWO_BARS, InputMonitoringMode.OFF, null);

        assertThat(pipeline.getCountInMode()).isEqualTo(CountInMode.TWO_BARS);
    }

    @Test
    void shouldAcceptMonitoringMode() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track),
                CountInMode.OFF, InputMonitoringMode.ALWAYS, null);

        assertThat(pipeline.getMonitoringMode()).isEqualTo(InputMonitoringMode.ALWAYS);
    }

    @Test
    void shouldAcceptPunchRange() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        PunchRange punch = new PunchRange(4.0, 12.0);

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track),
                CountInMode.OFF, InputMonitoringMode.OFF, punch);

        assertThat(pipeline.getPunchRange()).isEqualTo(punch);
    }

    @Test
    void shouldUsePunchInBeatAsStartPosition() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        PunchRange punch = new PunchRange(4.0, 12.0);

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track),
                CountInMode.OFF, InputMonitoringMode.OFF, punch);
        startRecording(pipeline);

        assertThat(pipeline.getRecordingStartBeat()).isEqualTo(4.0);
    }

    @Test
    void monitoringShouldBeActiveWhenAlwaysAndRecording() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track),
                CountInMode.OFF, InputMonitoringMode.ALWAYS, null);
        startRecording(pipeline);

        assertThat(pipeline.isInputMonitoringActive()).isTrue();
    }

    @Test
    void monitoringShouldBeActiveWhenAlwaysAndNotRecording() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track),
                CountInMode.OFF, InputMonitoringMode.ALWAYS, null);

        // Not started yet
        assertThat(pipeline.isInputMonitoringActive()).isTrue();
    }

    @Test
    void monitoringShouldBeActiveWhenAutoAndRecording() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track),
                CountInMode.OFF, InputMonitoringMode.AUTO, null);
        startRecording(pipeline);

        assertThat(pipeline.isInputMonitoringActive()).isTrue();
    }

    @Test
    void monitoringShouldBeInactiveWhenAutoAndNotRecording() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track),
                CountInMode.OFF, InputMonitoringMode.AUTO, null);

        // Not started yet
        assertThat(pipeline.isInputMonitoringActive()).isFalse();
    }

    @Test
    void monitoringShouldBeInactiveWhenOff() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track),
                CountInMode.OFF, InputMonitoringMode.OFF, null);
        startRecording(pipeline);

        assertThat(pipeline.isInputMonitoringActive()).isFalse();
    }

    @Test
    void shouldRejectNullCountInMode() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);

        assertThatThrownBy(() -> new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track),
                null, InputMonitoringMode.OFF, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullMonitoringMode() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);

        assertThatThrownBy(() -> new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track),
                CountInMode.OFF, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aRecordedClipListsTheSegmentThatHoldsItsAudioAndDeclaresWhatWasCaptured() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        startRecording(pipeline);

        // Simulate audio capture with non-zero data
        float[][] input = new float[2][512];
        float[][] output = new float[2][512];
        for (int i = 0; i < 512; i++) {
            input[0][i] = 0.25f;
            input[1][i] = -0.25f;
        }
        audioEngine.processBlock(input, output, 512);

        List<AudioClip> clips = stopRecording(pipeline);

        assertThat(clips).hasSize(1);
        AudioClip clip = clips.getFirst();
        assertThat(clip.getAudioData()).as("the pipeline keeps no copy of the take to attach").isNull();
        assertThat(clip.getSourceRateMetadata()).isEqualTo(new SourceRateMetadata(44_100, 2, 512L));
        assertThat(clip.getSourceRateMetadata().requiresConversion(44_100))
                .as("a take played back at the rate it was captured at is not resampled").isFalse();
        float[][] audio = audioOnDisk(clip);
        assertThat(audio).hasNumberOfRows(2);
        // ±0.25 is exact at 16 bits: round(0.25 x 32767) / 32768 == 0.25.
        assertThat(audio[0]).hasSize(512).containsOnly(0.25f);
        assertThat(audio[1]).hasSize(512).containsOnly(-0.25f);
    }

    @Test
    void shouldAccumulateAudioAcrossMultipleProcessBlocks() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        startRecording(pipeline);

        float[][] input = new float[2][512];
        float[][] output = new float[2][512];
        for (int i = 0; i < 512; i++) {
            input[0][i] = 0.5f;
        }
        for (int block = 0; block < 5; block++) {
            audioEngine.processBlock(input, output, 512);
        }

        List<AudioClip> clips = stopRecording(pipeline);

        assertThat(clips).hasSize(1);
        AudioClip clip = clips.getFirst();
        float[][] audio = audioOnDisk(clip);
        assertThat(audio[0]).hasSize(512 * 5);
        // Verify data from first and last blocks (0.5 is exact at 16 bits)
        assertThat(audio[0][0]).isEqualTo(0.5f);
        assertThat(audio[0][512 * 4]).isEqualTo(0.5f);
        assertThat(audio[0]).containsOnly(0.5f);
    }

    @Test
    void shouldAttachAudioDataToMultipleArmedTracks() {
        Track track1 = new Track("Audio 1", TrackType.AUDIO);
        track1.setArmed(true);
        Track track2 = new Track("Audio 2", TrackType.AUDIO);
        track2.setArmed(true);
        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track1, track2));
        startRecording(pipeline);

        float[][] input = new float[2][512];
        float[][] output = new float[2][512];
        input[0][0] = 0.75f;
        audioEngine.processBlock(input, output, 512);

        List<AudioClip> clips = stopRecording(pipeline);

        assertThat(clips).hasSize(2);
        assertThat(clips.get(0).getSourceSegmentPaths())
                .as("each track's clip lists its own track's segment")
                .doesNotContainAnyElementsOf(clips.get(1).getSourceSegmentPaths());
        for (AudioClip clip : clips) {
            float[][] audio = audioOnDisk(clip);
            assertThat(audio[0]).hasSize(512);
            assertThat(audio[0][0]).isEqualTo(decoded16(0.75f));
            assertThat(audio[0][1]).isZero();
        }
    }

    // ---------------------------------------------------------------------
    // Frame-based PunchRegion on Transport (sample-accurate auto-punch).
    // ---------------------------------------------------------------------

    @Test
    void shouldCaptureOnlyWithinTransportPunchRegion() {
        // Punch region spans frames [512, 1536) — exactly blocks 1 and 2 out
        // of a 4-block run. Blocks 0 and 3 must not be captured.
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        transport.setPunchRegion(new PunchRegion(512L, 1536L, true));

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        startRecording(pipeline);

        float[][] input = new float[2][512];
        for (int ch = 0; ch < 2; ch++) {
            java.util.Arrays.fill(input[ch], 1.0f);
        }
        float[][] output = new float[2][512];
        for (int i = 0; i < 4; i++) {
            audioEngine.processBlock(input, output, 512);
            advanceTransportByFrames(512);
        }

        pipeline.awaitFlushed();
        RecordingSession session = pipeline.getSession(track);
        // Exactly two blocks (512 * 2 = 1024 frames) fall inside the region.
        assertThat(session.getTotalSamplesRecorded()).isEqualTo(1024L);

        stopRecording(pipeline);
    }

    @Test
    void shouldNotCaptureWhenPunchRegionDisabled() {
        // Punch region is installed but the enabled flag is false — behave
        // as if no punch region were set (capture entire transport output).
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        transport.setPunchRegion(new PunchRegion(512L, 1536L, false));

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        startRecording(pipeline);

        float[][] input = new float[2][512];
        float[][] output = new float[2][512];
        for (int i = 0; i < 4; i++) {
            audioEngine.processBlock(input, output, 512);
        }

        // All 4 blocks should be captured because punch is disabled.
        pipeline.awaitFlushed();
        assertThat(pipeline.getSession(track).getTotalSamplesRecorded()).isEqualTo(2048L);

        stopRecording(pipeline);
    }

    @Test
    void shouldApplySampleAccuratePunchBoundariesWithinOneBlock() {
        // Punch region strictly inside a single 512-frame block: capture
        // only 100 frames out of the middle block.
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        transport.setPunchRegion(new PunchRegion(100L, 200L, true));

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        startRecording(pipeline);

        float[][] input = new float[2][512];
        for (int ch = 0; ch < 2; ch++) {
            java.util.Arrays.fill(input[ch], 1.0f);
        }
        float[][] output = new float[2][512];
        audioEngine.processBlock(input, output, 512);
        advanceTransportByFrames(512);

        pipeline.awaitFlushed();
        assertThat(pipeline.getSession(track).getTotalSamplesRecorded()).isEqualTo(100L);

        stopRecording(pipeline);
    }

    @Test
    void shouldApplyCosineCrossfadeAtPunchBoundaries() {
        // Verify a 5 ms (≈ 220 frames at 44.1 kHz) cosine ramp at both the
        // punch-in and punch-out boundaries. The captured audio's first
        // sample must be ~0 and the final sample (at punch-out) must be ~0.
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        // Punch range = 1024 frames; comfortably longer than a 5 ms fade.
        transport.setPunchRegion(new PunchRegion(512L, 1536L, true));

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        startRecording(pipeline);

        float[][] input = new float[2][512];
        for (int ch = 0; ch < 2; ch++) {
            java.util.Arrays.fill(input[ch], 1.0f);
        }
        float[][] output = new float[2][512];
        for (int i = 0; i < 4; i++) {
            audioEngine.processBlock(input, output, 512);
            advanceTransportByFrames(512);
        }

        pipeline.awaitFlushed();
        // Behind the fence: the streaming segment holds every block published so far.
        float[][] captured = audioOnDisk(pipeline.getSession(track));
        assertThat(captured[0].length).isGreaterThanOrEqualTo(1024);

        int fadeFrames = (int) Math.round(0.005 * format.sampleRate());

        // Fade-in: sample at index 0 should be 0 (silence ramping up).
        assertThat(captured[0][0]).isEqualTo(0.0f);
        // Half-way through the ramp the gain is 0.5.
        float midFadeIn = captured[0][fadeFrames / 2];
        assertThat(midFadeIn).isBetween(0.3f, 0.7f);
        // After the ramp the signal should be at full gain (full scale as 16 bits decode it).
        assertThat(captured[0][fadeFrames + 10]).isEqualTo(decoded16(1.0f));

        // Fade-out: final sample should be near zero; the middle of the
        // tail ramp should be around 0.5.
        int lastIdx = 1024 - 1;
        assertThat(Math.abs(captured[0][lastIdx])).isLessThan(0.05f);
        float midFadeOut = captured[0][lastIdx - fadeFrames / 2];
        assertThat(midFadeOut).isBetween(0.3f, 0.7f);

        stopRecording(pipeline);
    }

    @Test
    void shouldAutoPunchAcrossMultiplePassesWhileArmed() {
        // Auto-punch: transport loops back into the region while the pipeline
        // remains active — the pipeline must resume capturing on re-entry
        // without being re-armed.
        //
        // At 120 BPM / 44100 Hz: 22050 frames/beat.
        // Punch region = [512, 1024) frames.
        // We simulate a loop by setting transport position back to beat 0.
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        transport.setPunchRegion(new PunchRegion(512L, 1024L, true));

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        startRecording(pipeline);

        float[][] input = new float[2][512];
        float[][] output = new float[2][512];

        // Pass 1: blocks starting at frames 0, 512, 1024 —
        // only the block starting at frame 512 is inside [512, 1024).
        for (int i = 0; i < 3; i++) {
            audioEngine.processBlock(input, output, 512);
            advanceTransportByFrames(512);
        }
        pipeline.awaitFlushed();
        long afterPass1 = pipeline.getSession(track).getTotalSamplesRecorded();
        assertThat(afterPass1).isEqualTo(512L);

        // Simulate a loop/rewind: reset transport position to beat 0.
        transport.setPositionInBeats(0.0);

        // Pass 2: same blocks as pass 1 — the punch region must be re-entered
        // and captured again because the transport position has rewound.
        for (int i = 0; i < 3; i++) {
            audioEngine.processBlock(input, output, 512);
            advanceTransportByFrames(512);
        }
        pipeline.awaitFlushed();
        long afterPass2 = pipeline.getSession(track).getTotalSamplesRecorded();
        assertThat(afterPass2).isEqualTo(afterPass1 + 512L);

        stopRecording(pipeline);
    }

    @Test
    void aPunchedTakeStartedInsideThePunchRegionStartsAtThePlayheadLessTheCompensation() {
        // PR #984 review: Record pressed at beat 5 inside punch [4, 6). The
        // gate writes from the playhead, so the clip starts there — not at
        // the punch-in, a beat before anything was recorded.
        AudioFormat format48k = new AudioFormat(48_000.0, 2, 16, 256);
        AudioEngine engine48k = new AudioEngine(format48k);
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        transport.setPunchRegion(new PunchRegion(96_000L, 144_000L, true)); // beats [4, 6) at 120 BPM
        transport.setPositionInBeats(5.0);
        RecordingPipeline pipeline = new RecordingPipeline(
                engine48k, transport, format48k, tempDir, List.of(track));
        pipeline.setReportedLatency(new RoundTripLatency(128, 128, 0));
        pipeline.setApplyLatencyCompensation(true);
        startRecording(pipeline);
        assertThat(pipeline.getSession(track).getCompensationFrames()).as("fixture: 256 frames").isEqualTo(256L);

        long recordFrame = 120_000L; // beat 5
        // A few blocks past the punch-out at beat 6, with every code fed
        // inside 16 bits: the last block fed is the last to start before
        // beat 6.1 (frame 146 400), at 120 000 + 103 × 256 = 146 368, and its
        // last frame's code is 1 + 146 623 − 120 000 = 26 624.
        processRampBlocksUntil(engine48k, pipeline, recordFrame, 6.1);
        List<AudioClip> clips = stopRecording(pipeline);

        assertThat(pipeline.getRecordingStartBeat()).as("the take's anchor is still the punch-in").isEqualTo(4.0);
        assertThat(clips).singleElement().satisfies(clip -> {
            assertThat(clip.getStartBeat()).isCloseTo(5.0 - 256 / 24_000.0, offset(1e-9));
            assertThat(clip.getDurationBeats()).as("from the playhead to the punch-out")
                    .isCloseTo(1.0, offset(1e-9));
            assertThat(clip.getSourceOffsetBeats()).isZero();
            float[][] onDisk = audioOnDisk(clip);
            assertThat(onDisk).as("a stereo file").hasNumberOfRows(2);
            int frameForBeat = (int) Math.round((5.5 - clip.getStartBeat()) * 24_000.0);
            for (int channel = 0; channel < 2; channel++) {
                // Entering the punch region fades in over the 5 ms fade cut to
                // the block's slice: min(240, 256) = 240 frames here, as the
                // first block lies wholly inside the region. So the block is
                // checked just past it: still the block headed at beat 5,
                // frame for frame.
                assertThat(onDisk[channel][250]).as("channel %d: the clip opens with the block headed at beat 5", channel)
                        .isEqualTo(decoded16(rampSample(channel, recordFrame + 250, recordFrame)));
                // Timeline beat 5.5 plays what was captured 256 frames later (the round trip).
                assertThat(onDisk[channel][frameForBeat]).as("channel %d: the sample the clip plays at beat 5.5", channel)
                        .isEqualTo(decoded16(rampSample(channel, 132_000L + 256, recordFrame)));
            }
        });
    }

    @Test
    void aPunchedTakeWhosePunchInIsNotBlockAlignedStartsAtThePunchInFrame() {
        // PR #984 review: Record from beat 3.9 (blocks from frame 93 600) with
        // the punch-in at frame 96 100, 196 frames into a block. The clip
        // starts at that frame less the compensation, and plays from it.
        AudioFormat format48k = new AudioFormat(48_000.0, 2, 16, 256);
        AudioEngine engine48k = new AudioEngine(format48k);
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        transport.setPunchRegion(new PunchRegion(96_100L, 120_000L, true)); // punch-out at beat 5
        transport.setPositionInBeats(3.9);
        RecordingPipeline pipeline = new RecordingPipeline(
                engine48k, transport, format48k, tempDir, List.of(track));
        pipeline.setReportedLatency(new RoundTripLatency(128, 128, 0));
        pipeline.setApplyLatencyCompensation(true);
        startRecording(pipeline);

        long recordFrame = 93_600L; // beat 3.9
        // The last block fed is the last to start before beat 5.2 (frame
        // 124 800), at 93 600 + 121 × 256 = 124 576, and its last frame's
        // code is 1 + 124 831 − 93 600 = 31 232, inside 16 bits.
        processRampBlocksUntil(engine48k, pipeline, recordFrame, 5.2);
        List<AudioClip> clips = stopRecording(pipeline);

        assertThat(clips).singleElement().satisfies(clip -> {
            assertThat(clip.getStartBeat()).as("the punch-in frame less the compensation")
                    .isCloseTo((96_100 - 256) / 24_000.0, offset(1e-9));
            assertThat(clip.getDurationBeats()).isCloseTo((120_000 - 96_100) / 24_000.0, offset(1e-9));
            assertThat(clip.getSourceOffsetBeats()).isZero();
            float[][] onDisk = audioOnDisk(clip);
            assertThat(onDisk).as("a stereo file").hasNumberOfRows(2);
            // Blocks run from frame 93 600 in steps of 256. The punch-in at
            // 96 100 lies (96 100 − 93 600) mod 256 = 196 frames into the
            // block headed 95 904, which keeps 256 − 196 = 60 frames; the
            // punch-out at 120 000 lies (120 000 − 93 600) mod 256 = 32
            // frames into the block headed 119 968, which keeps those 32.
            // Each fade is the 5 ms (240-frame) fade cut to its block's
            // slice: 60 frames in, 32 out. The file holds the frames from the
            // punch-in to the punch-out: 120 000 − 96 100 = 23 900.
            for (int channel = 0; channel < 2; channel++) {
                float[] file = onDisk[channel];
                assertThat(file).as("channel %d: the punch-in to the punch-out", channel).hasSize(23_900);
                assertThat(Arrays.copyOf(file, 60)).as("channel %d: the punch-in block's slice, faded in", channel)
                        .containsExactly(fadedRamp(channel, 96_100L, recordFrame, 60, 60, 0));
                assertThat(file[300]).as("channel %d: the sample captured 300 frames after the punch-in", channel)
                        .isEqualTo(decoded16(rampSample(channel, 96_100L + 300, recordFrame)));
                // Timeline beat 4.5 plays what was captured 256 frames later.
                int frameForBeat = (int) Math.round((4.5 - clip.getStartBeat()) * 24_000.0);
                assertThat(file[frameForBeat]).as("channel %d: the sample the clip plays at beat 4.5", channel)
                        .isEqualTo(decoded16(rampSample(channel, 108_000L + 256, recordFrame)));
                // The punch-out block's 32 frames (119 968 to 119 999) close
                // the file, faded out; frame 119 967, the one before them,
                // ends the previous block unfaded.
                assertThat(Arrays.copyOfRange(file, 23_900 - 32, 23_900))
                        .as("channel %d: the punch-out block's slice, faded out", channel)
                        .containsExactly(fadedRamp(channel, 119_968L, recordFrame, 32, 0, 32));
                assertThat(file[23_900 - 33]).as("channel %d: the last sample before the fade-out", channel)
                        .isEqualTo(decoded16(rampSample(channel, 119_967L, recordFrame)));
            }
        });
    }

    /**
     * Feeds 256-frame blocks at 48 kHz (24 000 frames per beat) until the
     * transport reaches {@code endBeat}. Every frame carries its own 16-bit
     * code ({@link #rampSample}), so a sample on disk names the transport
     * frame it was captured at and the channel it came in on. The callers
     * choose {@code endBeat} so that the code of the last frame fed,
     * {@code 1 + frame − baseFrame}, stays within 32 767.
     */
    private void processRampBlocksUntil(AudioEngine engine, RecordingPipeline pipeline,
                                        long baseFrame, double endBeat) {
        float[][] output = new float[2][256];
        while (transport.getPositionInBeats() < endBeat) {
            // As the capture callback stamps a block's start frame (120 BPM, 48 kHz).
            long blockStartFrame = Math.round(transport.getPositionInBeats() * 60.0 / 120.0 * 48_000.0);
            float[][] input = new float[2][256];
            for (int channel = 0; channel < 2; channel++) {
                for (int i = 0; i < 256; i++) {
                    input[channel][i] = rampSample(channel, blockStartFrame + i, baseFrame);
                }
            }
            engine.processBlock(input, output, 256);
            pipeline.awaitFlushed();
            transport.advancePosition(256 / 24_000.0);
        }
    }

    /**
     * The sample transport frame {@code frame} carries on {@code channel}:
     * the code {@code 1 + frame − baseFrame} on channel 0, and that code
     * negated on channel 1, so the two channels are told apart.
     */
    private static float rampSample(int channel, long frame, long baseFrame) {
        int code = (int) (1 + frame - baseFrame);
        return (channel == 0 ? code : -code) / 32767f;
    }

    /**
     * What a file holds on {@code channel} for the {@code frames}-frame slice
     * a block keeps from {@code firstFrame}: the ramp captured from that
     * frame, under a cosine fade-in over its first {@code fadeIn} frames and
     * a fade-out over its last {@code fadeOut}, decoded.
     */
    private static float[] fadedRamp(int channel, long firstFrame, long baseFrame,
                                     int frames, int fadeIn, int fadeOut) {
        float[][] slice = new float[1][frames];
        for (int i = 0; i < frames; i++) {
            slice[0][i] = rampSample(channel, firstFrame + i, baseFrame);
        }
        CaptureFlushService.applyCosineFades(slice, 1, frames, fadeIn, fadeOut);
        float[] decoded = new float[frames];
        for (int i = 0; i < frames; i++) {
            decoded[i] = decoded16(slice[0][i]);
        }
        return decoded;
    }

    @Test
    void shouldPreferTransportPunchRegionOverLegacyPunchRange() {
        // If both a frame-based PunchRegion and a beat-based PunchRange are
        // set, the sample-accurate transport region wins.
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        transport.setPunchRegion(new PunchRegion(512L, 1536L, true));
        PunchRange legacy = new PunchRange(100.0, 200.0); // far-away beats

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track),
                CountInMode.OFF, InputMonitoringMode.OFF, legacy);
        startRecording(pipeline);

        float[][] input = new float[2][512];
        float[][] output = new float[2][512];
        for (int i = 0; i < 4; i++) {
            audioEngine.processBlock(input, output, 512);
            advanceTransportByFrames(512);
        }

        // Frame region captured 1024 frames despite the legacy beat range
        // covering a completely different span.
        pipeline.awaitFlushed();
        assertThat(pipeline.getSession(track).getTotalSamplesRecorded()).isEqualTo(1024L);

        stopRecording(pipeline);
    }
}
