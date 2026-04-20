package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.sdk.transport.PunchRegion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

        pipeline.start();

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
        pipeline.start();

        assertThatThrownBy(pipeline::start)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldStopRecordingPipeline() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        pipeline.start();

        List<AudioClip> clips = pipeline.stop();

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

        List<AudioClip> clips = pipeline.stop();

        assertThat(clips).isEmpty();
    }

    @Test
    void shouldRecordAndCreateClipsOnStop() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        pipeline.start();

        // Simulate audio capture by calling processBlock
        float[][] input = new float[2][512];
        float[][] output = new float[2][512];
        for (int i = 0; i < 10; i++) {
            audioEngine.processBlock(input, output, 512);
        }

        List<AudioClip> clips = pipeline.stop();

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
        pipeline.start();

        // Simulate audio capture
        float[][] input = new float[2][512];
        float[][] output = new float[2][512];
        audioEngine.processBlock(input, output, 512);

        List<AudioClip> clips = pipeline.stop();

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
        pipeline.start();

        RecordingSession session = pipeline.getSession(track);
        assertThat(session).isNotNull();
        assertThat(session.getSegmentCount()).isEqualTo(1);
        assertThat(session.isActive()).isTrue();

        // Simulate some audio capture
        float[][] input = new float[2][512];
        float[][] output = new float[2][512];
        audioEngine.processBlock(input, output, 512);

        assertThat(session.getTotalSamplesRecorded()).isGreaterThan(0);

        pipeline.stop();

        assertThat(session.isActive()).isFalse();
    }

    @Test
    void shouldReturnRecordedClipsMap() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        pipeline.start();

        float[][] input = new float[2][512];
        float[][] output = new float[2][512];
        audioEngine.processBlock(input, output, 512);

        pipeline.stop();

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
        pipeline.start();

        assertThat(pipeline.getRecordingStartBeat()).isEqualTo(8.0);

        // Simulate audio capture
        float[][] input = new float[2][512];
        float[][] output = new float[2][512];
        audioEngine.processBlock(input, output, 512);

        List<AudioClip> clips = pipeline.stop();

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
        pipeline.start();

        assertThat(track.isRecording()).isTrue();

        pipeline.stop();

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
        pipeline.start();

        assertThat(pipeline.getRecordingStartBeat()).isEqualTo(4.0);
    }

    @Test
    void shouldGenerateCountInAudio() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track),
                CountInMode.ONE_BAR, InputMonitoringMode.OFF, null);

        float[][] countInAudio = pipeline.generateCountInAudio();

        // 4 beats at 120 BPM (default tempo), 44100 Hz = 88200 samples
        assertThat(countInAudio).hasNumberOfRows(2);
        assertThat(countInAudio[0].length).isEqualTo(88200);
    }

    @Test
    void shouldReturnEmptyCountInAudioWhenOff() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));

        float[][] countInAudio = pipeline.generateCountInAudio();

        assertThat(countInAudio[0]).isEmpty();
    }

    @Test
    void monitoringShouldBeActiveWhenAlwaysAndRecording() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track),
                CountInMode.OFF, InputMonitoringMode.ALWAYS, null);
        pipeline.start();

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
        pipeline.start();

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
        pipeline.start();

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
    void shouldAttachAudioDataToRecordedClips() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        pipeline.start();

        // Simulate audio capture with non-zero data
        float[][] input = new float[2][512];
        float[][] output = new float[2][512];
        for (int i = 0; i < 512; i++) {
            input[0][i] = 0.25f;
            input[1][i] = -0.25f;
        }
        audioEngine.processBlock(input, output, 512);

        List<AudioClip> clips = pipeline.stop();

        assertThat(clips).hasSize(1);
        AudioClip clip = clips.getFirst();
        assertThat(clip.getAudioData()).isNotNull();
        assertThat(clip.getAudioData()).hasNumberOfRows(2);
        assertThat(clip.getAudioData()[0]).hasSize(512);
        assertThat(clip.getAudioData()[0][0]).isEqualTo(0.25f);
        assertThat(clip.getAudioData()[1][0]).isEqualTo(-0.25f);
    }

    @Test
    void shouldAccumulateAudioAcrossMultipleProcessBlocks() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        pipeline.start();

        float[][] input = new float[2][512];
        float[][] output = new float[2][512];
        for (int i = 0; i < 512; i++) {
            input[0][i] = 0.5f;
        }
        for (int block = 0; block < 5; block++) {
            audioEngine.processBlock(input, output, 512);
        }

        List<AudioClip> clips = pipeline.stop();

        assertThat(clips).hasSize(1);
        AudioClip clip = clips.getFirst();
        assertThat(clip.getAudioData()).isNotNull();
        assertThat(clip.getAudioData()[0]).hasSize(512 * 5);
        // Verify data from first and last blocks
        assertThat(clip.getAudioData()[0][0]).isEqualTo(0.5f);
        assertThat(clip.getAudioData()[0][512 * 4]).isEqualTo(0.5f);
    }

    @Test
    void shouldAttachAudioDataToMultipleArmedTracks() {
        Track track1 = new Track("Audio 1", TrackType.AUDIO);
        track1.setArmed(true);
        Track track2 = new Track("Audio 2", TrackType.AUDIO);
        track2.setArmed(true);
        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track1, track2));
        pipeline.start();

        float[][] input = new float[2][512];
        float[][] output = new float[2][512];
        input[0][0] = 0.75f;
        audioEngine.processBlock(input, output, 512);

        List<AudioClip> clips = pipeline.stop();

        assertThat(clips).hasSize(2);
        for (AudioClip clip : clips) {
            assertThat(clip.getAudioData()).isNotNull();
            assertThat(clip.getAudioData()[0][0]).isEqualTo(0.75f);
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
        pipeline.start();

        float[][] input = new float[2][512];
        for (int ch = 0; ch < 2; ch++) {
            java.util.Arrays.fill(input[ch], 1.0f);
        }
        float[][] output = new float[2][512];
        for (int i = 0; i < 4; i++) {
            audioEngine.processBlock(input, output, 512);
            advanceTransportByFrames(512);
        }

        RecordingSession session = pipeline.getSession(track);
        // Exactly two blocks (512 * 2 = 1024 frames) fall inside the region.
        assertThat(session.getTotalSamplesRecorded()).isEqualTo(1024L);

        pipeline.stop();
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
        pipeline.start();

        float[][] input = new float[2][512];
        float[][] output = new float[2][512];
        for (int i = 0; i < 4; i++) {
            audioEngine.processBlock(input, output, 512);
        }

        // All 4 blocks should be captured because punch is disabled.
        assertThat(pipeline.getSession(track).getTotalSamplesRecorded()).isEqualTo(2048L);

        pipeline.stop();
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
        pipeline.start();

        float[][] input = new float[2][512];
        for (int ch = 0; ch < 2; ch++) {
            java.util.Arrays.fill(input[ch], 1.0f);
        }
        float[][] output = new float[2][512];
        audioEngine.processBlock(input, output, 512);
        advanceTransportByFrames(512);

        assertThat(pipeline.getSession(track).getTotalSamplesRecorded()).isEqualTo(100L);

        pipeline.stop();
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
        pipeline.start();

        float[][] input = new float[2][512];
        for (int ch = 0; ch < 2; ch++) {
            java.util.Arrays.fill(input[ch], 1.0f);
        }
        float[][] output = new float[2][512];
        for (int i = 0; i < 4; i++) {
            audioEngine.processBlock(input, output, 512);
            advanceTransportByFrames(512);
        }

        float[][] captured = pipeline.getSession(track).getCapturedAudio();
        assertThat(captured).isNotNull();
        assertThat(captured[0].length).isGreaterThanOrEqualTo(1024);

        int fadeFrames = (int) Math.round(0.005 * format.sampleRate());

        // Fade-in: sample at index 0 should be 0 (silence ramping up).
        assertThat(captured[0][0]).isEqualTo(0.0f);
        // Half-way through the ramp the gain is 0.5.
        float midFadeIn = captured[0][fadeFrames / 2];
        assertThat(midFadeIn).isBetween(0.3f, 0.7f);
        // After the ramp the signal should be at full gain.
        assertThat(captured[0][fadeFrames + 10]).isEqualTo(1.0f);

        // Fade-out: final sample should be near zero; the middle of the
        // tail ramp should be around 0.5.
        int lastIdx = 1024 - 1;
        assertThat(Math.abs(captured[0][lastIdx])).isLessThan(0.05f);
        float midFadeOut = captured[0][lastIdx - fadeFrames / 2];
        assertThat(midFadeOut).isBetween(0.3f, 0.7f);

        pipeline.stop();
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
        pipeline.start();

        float[][] input = new float[2][512];
        float[][] output = new float[2][512];

        // Pass 1: blocks starting at frames 0, 512, 1024 —
        // only the block starting at frame 512 is inside [512, 1024).
        for (int i = 0; i < 3; i++) {
            audioEngine.processBlock(input, output, 512);
            advanceTransportByFrames(512);
        }
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
        long afterPass2 = pipeline.getSession(track).getTotalSamplesRecorded();
        assertThat(afterPass2).isEqualTo(afterPass1 + 512L);

        pipeline.stop();
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
        pipeline.start();

        float[][] input = new float[2][512];
        float[][] output = new float[2][512];
        for (int i = 0; i < 4; i++) {
            audioEngine.processBlock(input, output, 512);
            advanceTransportByFrames(512);
        }

        // Frame region captured 1024 frames despite the legacy beat range
        // covering a completely different span.
        assertThat(pipeline.getSession(track).getTotalSamplesRecorded()).isEqualTo(1024L);

        pipeline.stop();
    }
}
