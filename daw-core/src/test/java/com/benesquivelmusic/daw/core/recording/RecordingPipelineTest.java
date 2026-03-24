package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;

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
}
