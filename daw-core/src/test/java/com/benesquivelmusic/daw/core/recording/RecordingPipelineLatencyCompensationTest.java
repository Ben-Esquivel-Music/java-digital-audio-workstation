package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.audio.RoundTripLatency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for driver-reported round-trip latency compensation in
 * {@link RecordingPipeline}.
 *
 * <p>When a user records a vocal while monitoring a click, the captured take
 * is offset from the grid by the interface's input + output buffer pipeline.
 * The pipeline reads {@code AudioBackend.reportedLatency()} once per opened
 * stream and shifts each recorded clip's start position by the total
 * round-trip latency so the take aligns with the cue the singer heard.</p>
 */
class RecordingPipelineLatencyCompensationTest {

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
     * The deliverable test from the issue: a {@code MockAudioBackend}
     * reporting {@code RoundTripLatency(64, 128, 16)} produces takes whose
     * start position is shifted earlier by 208 frames so the wave aligns
     * with the bar the singer heard.
     */
    @Test
    void shouldShiftRecordedClipByTotalRoundTripFrames() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        transport.setPositionInBeats(8.0);

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        pipeline.setReportedLatency(new RoundTripLatency(64, 128, 16));
        pipeline.start();

        // 64 + 128 + 16 = 208 frames is the resolved compensation.
        assertThat(pipeline.getResolvedCompensationFrames()).isEqualTo(208);
        assertThat(pipeline.isApplyLatencyCompensation()).isTrue();

        float[][] input = new float[2][512];
        float[][] output = new float[2][512];
        audioEngine.processBlock(input, output, 512);

        List<AudioClip> clips = pipeline.stop();

        assertThat(clips).hasSize(1);
        // 208 frames at 44.1 kHz / 120 BPM = 208/44100 * (120/60) beats earlier.
        double compensationBeats =
                (208.0 / format.sampleRate()) * (transport.getTempo() / 60.0);
        assertThat(clips.getFirst().getStartBeat())
                .isEqualTo(8.0 - compensationBeats);
    }

    /**
     * Toggling compensation off leaves recorded takes uncompensated — useful
     * for diagnostic listening or for users wired through a hardware monitor
     * mixer who already pre-compensate.
     */
    @Test
    void disablingCompensationShouldLeaveTakesUncompensated() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        transport.setPositionInBeats(8.0);

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        pipeline.setReportedLatency(new RoundTripLatency(64, 128, 16));
        pipeline.setApplyLatencyCompensation(false);
        pipeline.start();

        assertThat(pipeline.getResolvedCompensationFrames()).isZero();

        float[][] input = new float[2][512];
        float[][] output = new float[2][512];
        audioEngine.processBlock(input, output, 512);

        List<AudioClip> clips = pipeline.stop();

        assertThat(clips).hasSize(1);
        // No shift — clip lands at the original start position.
        assertThat(clips.getFirst().getStartBeat()).isEqualTo(8.0);
    }

    /**
     * The user's calibration override replaces the driver-reported value
     * when present — verified here by passing a different
     * {@link RoundTripLatency} than the one the (mocked) driver would
     * normally report.
     */
    @Test
    void calibrationOverrideShouldReplaceReportedValue() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        transport.setPositionInBeats(8.0);

        // The driver reports 64+128+16 = 208 but the user's calibration
        // measured 0+0+512 = 512 frames; the application layer hands the
        // override down to the pipeline.
        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        pipeline.setReportedLatency(new RoundTripLatency(0, 0, 512));
        pipeline.start();

        assertThat(pipeline.getResolvedCompensationFrames()).isEqualTo(512);

        float[][] input = new float[2][512];
        float[][] output = new float[2][512];
        audioEngine.processBlock(input, output, 512);

        List<AudioClip> clips = pipeline.stop();

        double compensationBeats =
                (512.0 / format.sampleRate()) * (transport.getTempo() / 60.0);
        assertThat(clips.getFirst().getStartBeat())
                .isEqualTo(8.0 - compensationBeats);
    }

    /**
     * The default (no compensation configured) preserves the historical
     * behaviour: clips land at the original start position. This guarantees
     * existing recording tests keep passing.
     */
    @Test
    void noConfiguredLatencyShouldLeaveTakesUncompensated() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        transport.setPositionInBeats(4.0);

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        pipeline.start();

        assertThat(pipeline.getResolvedCompensationFrames()).isZero();
        assertThat(pipeline.getReportedLatency()).isEqualTo(RoundTripLatency.UNKNOWN);

        float[][] input = new float[2][512];
        float[][] output = new float[2][512];
        audioEngine.processBlock(input, output, 512);

        List<AudioClip> clips = pipeline.stop();
        assertThat(clips.getFirst().getStartBeat()).isEqualTo(4.0);
    }

    /**
     * If the recording start beat is closer to zero than the compensation
     * amount (early take near timeline start), the shifted start beat is
     * clamped to {@code 0} so {@link AudioClip}'s validation does not
     * reject a negative {@code startBeat}.
     */
    @Test
    void compensationShouldClampToZeroForEarlyTakes() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);
        transport.setPositionInBeats(0.0);

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        pipeline.setReportedLatency(new RoundTripLatency(64, 128, 16));
        pipeline.start();

        float[][] input = new float[2][512];
        float[][] output = new float[2][512];
        audioEngine.processBlock(input, output, 512);

        List<AudioClip> clips = pipeline.stop();
        assertThat(clips.getFirst().getStartBeat()).isZero();
    }

    @Test
    void recordingSessionShouldExposeCompensationFrames() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.setArmed(true);

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));
        pipeline.setReportedLatency(new RoundTripLatency(64, 128, 16));
        pipeline.start();

        RecordingSession session = pipeline.getSession(track);
        assertThat(session).isNotNull();
        assertThat(session.getCompensationFrames()).isEqualTo(208);

        pipeline.stop();
    }
}
