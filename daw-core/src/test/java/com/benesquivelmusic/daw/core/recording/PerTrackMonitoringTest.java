package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.sdk.audio.MonitoringResolution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for per-track input monitoring resolution including the
 * {@link InputMonitoringMode#TAPE} mode and the pipeline-level
 * "Mute All Inputs" panic switch.
 */
class PerTrackMonitoringTest {

    @TempDir
    Path tempDir;

    private AudioEngine audioEngine;
    private Transport transport;
    private AudioFormat format;

    @BeforeEach
    void setUp() {
        format = new AudioFormat(48_000.0, 2, 16, 512);
        audioEngine = new AudioEngine(format);
        transport = new Transport();
    }

    // --- MonitoringResolution record --------------------------------------

    @Test
    void monitoringResolutionConstantsExposeExpectedAudibility() {
        assertThat(MonitoringResolution.SILENT.inputAudible()).isFalse();
        assertThat(MonitoringResolution.SILENT.playbackAudible()).isFalse();

        assertThat(MonitoringResolution.INPUT_AUDIBLE.inputAudible()).isTrue();
        assertThat(MonitoringResolution.INPUT_AUDIBLE.playbackAudible()).isFalse();

        assertThat(MonitoringResolution.PLAYBACK_AUDIBLE.inputAudible()).isFalse();
        assertThat(MonitoringResolution.PLAYBACK_AUDIBLE.playbackAudible()).isTrue();
    }

    @Test
    void monitoringResolutionRejectsNegativeOrNonFiniteCrossfade() {
        assertThatThrownBy(() -> new MonitoringResolution(true, false, -1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("crossfadeFrames");

        assertThatThrownBy(() -> new MonitoringResolution(true, false, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new MonitoringResolution(true, false, Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- InputMonitoringMode.resolve() per-mode x per-state ---------------

    @Test
    void offModeIsAlwaysSilent() {
        for (TransportState state : TransportState.values()) {
            assertThat(InputMonitoringMode.OFF.resolve(state, true, true, 48_000.0))
                    .as("OFF + %s", state)
                    .isEqualTo(MonitoringResolution.SILENT);
        }
    }

    @Test
    void alwaysModeIsInputAudibleWhenArmed() {
        for (TransportState state : TransportState.values()) {
            MonitoringResolution res = InputMonitoringMode.ALWAYS
                    .resolve(state, true, false, 48_000.0);
            assertThat(res.inputAudible()).as("ALWAYS + %s", state).isTrue();
            assertThat(res.playbackAudible()).isFalse();
        }
    }

    @Test
    void autoModeIsInputAudibleOnlyWhileRecording() {
        assertThat(InputMonitoringMode.AUTO.resolve(TransportState.RECORDING, true, true, 48_000.0)
                .inputAudible()).isTrue();
        assertThat(InputMonitoringMode.AUTO.resolve(TransportState.PLAYING, true, true, 48_000.0)
                .inputAudible()).isFalse();
        assertThat(InputMonitoringMode.AUTO.resolve(TransportState.STOPPED, true, true, 48_000.0)
                .inputAudible()).isFalse();
        assertThat(InputMonitoringMode.AUTO.resolve(TransportState.PAUSED, true, true, 48_000.0)
                .inputAudible()).isFalse();
    }

    @Test
    void tapeModeStoppedHearsInput() {
        MonitoringResolution res = InputMonitoringMode.TAPE
                .resolve(TransportState.STOPPED, true, true, 48_000.0);
        assertThat(res.inputAudible()).isTrue();
        assertThat(res.playbackAudible()).isFalse();
    }

    @Test
    void tapeModePlayingHearsTape() {
        MonitoringResolution res = InputMonitoringMode.TAPE
                .resolve(TransportState.PLAYING, true, true, 48_000.0);
        assertThat(res.inputAudible()).isFalse();
        assertThat(res.playbackAudible()).isTrue();
    }

    @Test
    void tapeModeRecordingInsidePunchHearsInput() {
        MonitoringResolution res = InputMonitoringMode.TAPE
                .resolve(TransportState.RECORDING, true, true, 48_000.0);
        assertThat(res.inputAudible()).isTrue();
        assertThat(res.playbackAudible()).isFalse();
    }

    @Test
    void tapeModeRecordingOutsidePunchHearsTape() {
        // Auto-punch pre-roll: the transport is recording but the playhead
        // is not yet inside the punch range, so the singer should hear the
        // tape for continuity.
        MonitoringResolution res = InputMonitoringMode.TAPE
                .resolve(TransportState.RECORDING, true, false, 48_000.0);
        assertThat(res.inputAudible()).isFalse();
        assertThat(res.playbackAudible()).isTrue();
    }

    @Test
    void tapeModeSuppliesSmoothCrossfadeAtBoundary() {
        MonitoringResolution stopped = InputMonitoringMode.TAPE
                .resolve(TransportState.STOPPED, true, true, 48_000.0);
        MonitoringResolution playing = InputMonitoringMode.TAPE
                .resolve(TransportState.PLAYING, true, true, 48_000.0);

        // 5 ms at 48 kHz = 240 frames.
        assertThat(stopped.crossfadeFrames()).isEqualTo(240.0);
        assertThat(playing.crossfadeFrames()).isEqualTo(240.0);
    }

    @Test
    void nonTapeModesHaveNoCrossfade() {
        assertThat(InputMonitoringMode.AUTO.resolve(TransportState.RECORDING, true, true, 48_000.0)
                .crossfadeFrames()).isEqualTo(0.0);
        assertThat(InputMonitoringMode.ALWAYS.resolve(TransportState.PLAYING, true, true, 48_000.0)
                .crossfadeFrames()).isEqualTo(0.0);
        assertThat(InputMonitoringMode.OFF.resolve(TransportState.STOPPED, true, true, 48_000.0)
                .crossfadeFrames()).isEqualTo(0.0);
    }

    @Test
    void unarmedTracksAreAlwaysSilentRegardlessOfMode() {
        for (InputMonitoringMode mode : InputMonitoringMode.values()) {
            for (TransportState state : TransportState.values()) {
                assertThat(mode.resolve(state, false, true, 48_000.0))
                        .as("%s + %s + unarmed", mode, state)
                        .isEqualTo(MonitoringResolution.SILENT);
            }
        }
    }

    @Test
    void resolveRejectsNullTransportState() {
        assertThatThrownBy(() -> InputMonitoringMode.AUTO.resolve(null, true, true, 48_000.0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void resolveUsesDefaultCrossfadeWhenSampleRateIsZero() {
        MonitoringResolution res = InputMonitoringMode.TAPE
                .resolve(TransportState.STOPPED, true, true, 0.0);
        assertThat(res.crossfadeFrames()).isEqualTo(240.0);
    }

    // --- Track aliases ----------------------------------------------------

    @Test
    void trackInputMonitoringAliasesAreConsistent() {
        Track track = new Track("Vocal", TrackType.AUDIO);
        track.setInputMonitoring(InputMonitoringMode.TAPE);
        assertThat(track.getInputMonitoring()).isEqualTo(InputMonitoringMode.TAPE);
        assertThat(track.getInputMonitoringMode()).isEqualTo(InputMonitoringMode.TAPE);

        track.setInputMonitoringMode(InputMonitoringMode.ALWAYS);
        assertThat(track.getInputMonitoring()).isEqualTo(InputMonitoringMode.ALWAYS);
    }

    // --- Pipeline per-track query and panic switch ------------------------

    @Test
    void pipelinePerTrackQueryReflectsTrackMode() {
        Track vocal = new Track("Vocal", TrackType.AUDIO);
        vocal.setArmed(true);
        vocal.setInputMonitoring(InputMonitoringMode.AUTO);

        Track synth = new Track("Synth", TrackType.AUDIO);
        synth.setArmed(true);
        synth.setInputMonitoring(InputMonitoringMode.ALWAYS);

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(vocal, synth),
                CountInMode.OFF, InputMonitoringMode.OFF, null);

        // Transport stopped:
        // - Vocal (AUTO) should be silent, Synth (ALWAYS) should be audible.
        assertThat(pipeline.isInputMonitoringActive(vocal)).isFalse();
        assertThat(pipeline.isInputMonitoringActive(synth)).isTrue();

        // Start recording:
        pipeline.start();
        assertThat(pipeline.isInputMonitoringActive(vocal)).isTrue();
        assertThat(pipeline.isInputMonitoringActive(synth)).isTrue();
    }

    @Test
    void muteAllInputsPanicSilencesEveryTrackWithoutChangingMode() {
        Track vocal = new Track("Vocal", TrackType.AUDIO);
        vocal.setArmed(true);
        vocal.setInputMonitoring(InputMonitoringMode.ALWAYS);

        Track synth = new Track("Synth", TrackType.AUDIO);
        synth.setArmed(true);
        synth.setInputMonitoring(InputMonitoringMode.ALWAYS);

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(vocal, synth),
                CountInMode.OFF, InputMonitoringMode.OFF, null);

        assertThat(pipeline.isInputMonitoringActive(vocal)).isTrue();
        assertThat(pipeline.isInputMonitoringActive(synth)).isTrue();

        pipeline.setAllInputsMuted(true);
        assertThat(pipeline.isAllInputsMuted()).isTrue();
        assertThat(pipeline.isInputMonitoringActive(vocal)).isFalse();
        assertThat(pipeline.isInputMonitoringActive(synth)).isFalse();

        // Per-track modes are not mutated.
        assertThat(vocal.getInputMonitoring()).isEqualTo(InputMonitoringMode.ALWAYS);
        assertThat(synth.getInputMonitoring()).isEqualTo(InputMonitoringMode.ALWAYS);

        pipeline.setAllInputsMuted(false);
        assertThat(pipeline.isInputMonitoringActive(vocal)).isTrue();
        assertThat(pipeline.isInputMonitoringActive(synth)).isTrue();
    }

    @Test
    void pipelineDefaultModeFillsInTracksStillAtOffDefault() {
        Track vocal = new Track("Vocal", TrackType.AUDIO);
        vocal.setArmed(true); // mode remains OFF (the default)

        Track synth = new Track("Synth", TrackType.AUDIO);
        synth.setArmed(true);
        synth.setInputMonitoring(InputMonitoringMode.TAPE); // explicit override

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(vocal, synth),
                CountInMode.OFF, InputMonitoringMode.AUTO, null);

        pipeline.start();

        // Vocal picked up the pipeline default; synth kept its override.
        assertThat(vocal.getInputMonitoring()).isEqualTo(InputMonitoringMode.AUTO);
        assertThat(synth.getInputMonitoring()).isEqualTo(InputMonitoringMode.TAPE);
    }

    @Test
    void resolveMonitoringRejectsNullTrack() {
        Track track = new Track("Vocal", TrackType.AUDIO);
        track.setArmed(true);

        RecordingPipeline pipeline = new RecordingPipeline(
                audioEngine, transport, format, tempDir, List.of(track));

        assertThatThrownBy(() -> pipeline.resolveMonitoring(null))
                .isInstanceOf(NullPointerException.class);
    }
}
