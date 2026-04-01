package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.Send;
import com.benesquivelmusic.daw.core.mixer.SendMode;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * Tests for the real-time audio playback rendering path in {@link AudioEngine}.
 *
 * <p>These tests verify that the engine correctly reads audio from track clips,
 * mixes through the mixer, applies volume/pan/mute/solo, advances the transport,
 * and supports loop playback.</p>
 */
class AudioEnginePlaybackTest {

    // Use a simple format: 44100 Hz, stereo, 16-bit, 8-frame buffer
    private static final double SAMPLE_RATE = 44_100.0;
    private static final int CHANNELS = 2;
    private static final int BUFFER_SIZE = 8;
    private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, CHANNELS, 16, BUFFER_SIZE);

    // At 120 BPM: samplesPerBeat = 44100 * 60 / 120 = 22050
    private static final double TEMPO = 120.0;
    private static final double SAMPLES_PER_BEAT = SAMPLE_RATE * 60.0 / TEMPO;

    private AudioEngine engine;
    private Transport transport;
    private Mixer mixer;

    @BeforeEach
    void setUp() {
        engine = new AudioEngine(FORMAT);
        transport = new Transport();
        transport.setTempo(TEMPO);
        mixer = new Mixer();
    }

    // ── Passthrough behavior when no transport/mixer/tracks configured ──────

    @Test
    void shouldPassthroughInputWhenNoTransportConfigured() {
        engine.start();

        float[][] input = {{0.5f, -0.3f, 0.8f, -1.0f, 0.0f, 0.0f, 0.0f, 0.0f},
                           {0.1f, 0.2f, 0.3f, 0.4f, 0.0f, 0.0f, 0.0f, 0.0f}};
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, 4);

        assertThat(output[0][0]).isEqualTo(0.5f);
        assertThat(output[0][1]).isEqualTo(-0.3f);
        assertThat(output[1][0]).isEqualTo(0.1f);
    }

    @Test
    void shouldPassthroughInputWhenTransportIsStopped() {
        engine.setTransport(transport);
        engine.setMixer(mixer);
        engine.setTracks(List.of());
        engine.start();

        // Transport is STOPPED by default
        assertThat(transport.getState()).isEqualTo(TransportState.STOPPED);

        float[][] input = {{0.5f, -0.3f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f},
                           {0.1f, 0.2f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f}};
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, 2);

        assertThat(output[0][0]).isEqualTo(0.5f);
        assertThat(output[0][1]).isEqualTo(-0.3f);
    }

    // ── Single track rendering ──────────────────────────────────────────────

    @Test
    void shouldRenderSingleTrackClipAtPositionZero() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        // Create a clip at beat 0 with 1 beat duration
        // 1 beat = 22050 samples at 120 BPM / 44100 Hz
        AudioClip clip = new AudioClip("Clip", 0.0, 1.0, null);
        float[][] clipData = new float[CHANNELS][(int) SAMPLES_PER_BEAT];
        // Fill first 8 samples with known values
        for (int i = 0; i < BUFFER_SIZE; i++) {
            clipData[0][i] = 0.5f;
            clipData[1][i] = 0.3f;
        }
        clip.setAudioData(clipData);
        track.addClip(clip);

        MixerChannel mixerChannel = new MixerChannel("Track 1");
        // Volume=1.0, pan=0.0 (center) by default
        mixer.addChannel(mixerChannel);

        transport.play();
        engine.setTransport(transport);
        engine.setMixer(mixer);
        engine.setTracks(List.of(track));
        engine.start();

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        // With center pan (constant-power pan law), left and right gains are
        // cos(π/4) ≈ 0.7071 and sin(π/4) ≈ 0.7071
        double expectedGain = Math.cos(Math.PI / 4.0);
        for (int i = 0; i < BUFFER_SIZE; i++) {
            assertThat((double) output[0][i]).isCloseTo(0.5 * expectedGain, offset(0.001));
            assertThat((double) output[1][i]).isCloseTo(0.3 * expectedGain, offset(0.001));
        }
    }

    @Test
    void shouldNotRenderClipBeforeItsStartBeat() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        // Clip starts at beat 1.0
        AudioClip clip = new AudioClip("Clip", 1.0, 1.0, null);
        float[][] clipData = new float[CHANNELS][(int) SAMPLES_PER_BEAT];
        for (int i = 0; i < clipData[0].length; i++) {
            clipData[0][i] = 0.8f;
            clipData[1][i] = 0.8f;
        }
        clip.setAudioData(clipData);
        track.addClip(clip);

        MixerChannel mixerChannel = new MixerChannel("Track 1");
        mixer.addChannel(mixerChannel);

        transport.play();
        transport.setPositionInBeats(0.0);
        engine.setTransport(transport);
        engine.setMixer(mixer);
        engine.setTracks(List.of(track));
        engine.start();

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        // Position 0 with 8 frames at 22050 samples/beat = tiny fraction of a beat
        // The clip starts at beat 1.0, so nothing should be rendered
        for (int i = 0; i < BUFFER_SIZE; i++) {
            assertThat(output[0][i]).isEqualTo(0.0f);
            assertThat(output[1][i]).isEqualTo(0.0f);
        }
    }

    @Test
    void shouldNotRenderClipAfterItsEndBeat() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Clip", 0.0, 0.0001, null);
        // Very short clip that doesn't reach our block
        float[][] clipData = new float[CHANNELS][1];
        clipData[0][0] = 0.9f;
        clipData[1][0] = 0.9f;
        clip.setAudioData(clipData);
        track.addClip(clip);

        MixerChannel mixerChannel = new MixerChannel("Track 1");
        mixer.addChannel(mixerChannel);

        // Position far past the clip end
        transport.play();
        transport.setPositionInBeats(1.0);
        engine.setTransport(transport);
        engine.setMixer(mixer);
        engine.setTracks(List.of(track));
        engine.start();

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        for (int i = 0; i < BUFFER_SIZE; i++) {
            assertThat(output[0][i]).isEqualTo(0.0f);
        }
    }

    // ── Volume and mute ─────────────────────────────────────────────────────

    @Test
    void shouldApplyMixerChannelVolume() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Clip", 0.0, 1.0, null);
        float[][] clipData = new float[CHANNELS][(int) SAMPLES_PER_BEAT];
        for (int i = 0; i < BUFFER_SIZE; i++) {
            clipData[0][i] = 1.0f;
            clipData[1][i] = 1.0f;
        }
        clip.setAudioData(clipData);
        track.addClip(clip);

        MixerChannel mixerChannel = new MixerChannel("Track 1");
        mixerChannel.setVolume(0.5);
        mixer.addChannel(mixerChannel);

        transport.play();
        engine.setTransport(transport);
        engine.setMixer(mixer);
        engine.setTracks(List.of(track));
        engine.start();

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        // Volume=0.5, center pan: gain = 0.5 * cos(π/4) ≈ 0.3536
        double expectedGain = 0.5 * Math.cos(Math.PI / 4.0);
        assertThat((double) output[0][0]).isCloseTo(expectedGain, offset(0.001));
    }

    @Test
    void shouldMuteTrackWhenMixerChannelMuted() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Clip", 0.0, 1.0, null);
        float[][] clipData = new float[CHANNELS][(int) SAMPLES_PER_BEAT];
        for (int i = 0; i < BUFFER_SIZE; i++) {
            clipData[0][i] = 1.0f;
            clipData[1][i] = 1.0f;
        }
        clip.setAudioData(clipData);
        track.addClip(clip);

        MixerChannel mixerChannel = new MixerChannel("Track 1");
        mixerChannel.setMuted(true);
        mixer.addChannel(mixerChannel);

        transport.play();
        engine.setTransport(transport);
        engine.setMixer(mixer);
        engine.setTracks(List.of(track));
        engine.start();

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        for (int i = 0; i < BUFFER_SIZE; i++) {
            assertThat(output[0][i]).isEqualTo(0.0f);
            assertThat(output[1][i]).isEqualTo(0.0f);
        }
    }

    // ── Solo ────────────────────────────────────────────────────────────────

    @Test
    void shouldOnlyPlaySoloedTrack() {
        Track track1 = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip1 = new AudioClip("Clip1", 0.0, 1.0, null);
        float[][] clipData1 = new float[CHANNELS][(int) SAMPLES_PER_BEAT];
        for (int i = 0; i < BUFFER_SIZE; i++) {
            clipData1[0][i] = 0.5f;
            clipData1[1][i] = 0.5f;
        }
        clip1.setAudioData(clipData1);
        track1.addClip(clip1);

        Track track2 = new Track("Track 2", TrackType.AUDIO);
        AudioClip clip2 = new AudioClip("Clip2", 0.0, 1.0, null);
        float[][] clipData2 = new float[CHANNELS][(int) SAMPLES_PER_BEAT];
        for (int i = 0; i < BUFFER_SIZE; i++) {
            clipData2[0][i] = 1.0f;
            clipData2[1][i] = 1.0f;
        }
        clip2.setAudioData(clipData2);
        track2.addClip(clip2);

        MixerChannel ch1 = new MixerChannel("Track 1");
        MixerChannel ch2 = new MixerChannel("Track 2");
        ch2.setSolo(true);
        mixer.addChannel(ch1);
        mixer.addChannel(ch2);

        transport.play();
        engine.setTransport(transport);
        engine.setMixer(mixer);
        engine.setTracks(List.of(track1, track2));
        engine.start();

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        // Only track2 should be audible (solo=true), track1 is silenced
        double expectedGain = Math.cos(Math.PI / 4.0); // vol=1.0 center pan
        assertThat((double) output[0][0]).isCloseTo(1.0 * expectedGain, offset(0.001));
    }

    // ── Pan ─────────────────────────────────────────────────────────────────

    @Test
    void shouldPanTrackLeft() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Clip", 0.0, 1.0, null);
        float[][] clipData = new float[CHANNELS][(int) SAMPLES_PER_BEAT];
        for (int i = 0; i < BUFFER_SIZE; i++) {
            clipData[0][i] = 1.0f;
            clipData[1][i] = 1.0f;
        }
        clip.setAudioData(clipData);
        track.addClip(clip);

        MixerChannel mixerChannel = new MixerChannel("Track 1");
        mixerChannel.setPan(-1.0); // Full left
        mixer.addChannel(mixerChannel);

        transport.play();
        engine.setTransport(transport);
        engine.setMixer(mixer);
        engine.setTracks(List.of(track));
        engine.start();

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        // Full left: leftGain = cos(0) = 1.0, rightGain = sin(0) = 0.0
        assertThat((double) output[0][0]).isCloseTo(1.0, offset(0.001));
        assertThat((double) output[1][0]).isCloseTo(0.0, offset(0.001));
    }

    @Test
    void shouldPanTrackRight() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Clip", 0.0, 1.0, null);
        float[][] clipData = new float[CHANNELS][(int) SAMPLES_PER_BEAT];
        for (int i = 0; i < BUFFER_SIZE; i++) {
            clipData[0][i] = 1.0f;
            clipData[1][i] = 1.0f;
        }
        clip.setAudioData(clipData);
        track.addClip(clip);

        MixerChannel mixerChannel = new MixerChannel("Track 1");
        mixerChannel.setPan(1.0); // Full right
        mixer.addChannel(mixerChannel);

        transport.play();
        engine.setTransport(transport);
        engine.setMixer(mixer);
        engine.setTracks(List.of(track));
        engine.start();

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        // Full right: leftGain = cos(π/2) ≈ 0, rightGain = sin(π/2) = 1.0
        assertThat((double) output[0][0]).isCloseTo(0.0, offset(0.001));
        assertThat((double) output[1][0]).isCloseTo(1.0, offset(0.001));
    }

    // ── Master volume and mute ──────────────────────────────────────────────

    @Test
    void shouldApplyMasterVolume() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Clip", 0.0, 1.0, null);
        float[][] clipData = new float[CHANNELS][(int) SAMPLES_PER_BEAT];
        for (int i = 0; i < BUFFER_SIZE; i++) {
            clipData[0][i] = 1.0f;
            clipData[1][i] = 1.0f;
        }
        clip.setAudioData(clipData);
        track.addClip(clip);

        MixerChannel mixerChannel = new MixerChannel("Track 1");
        mixer.addChannel(mixerChannel);
        mixer.getMasterChannel().setVolume(0.5);

        transport.play();
        engine.setTransport(transport);
        engine.setMixer(mixer);
        engine.setTracks(List.of(track));
        engine.start();

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        // Channel vol=1.0, center pan gain=cos(π/4), master vol=0.5
        double expectedGain = Math.cos(Math.PI / 4.0) * 0.5;
        assertThat((double) output[0][0]).isCloseTo(expectedGain, offset(0.001));
    }

    @Test
    void shouldSilenceWhenMasterMuted() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Clip", 0.0, 1.0, null);
        float[][] clipData = new float[CHANNELS][(int) SAMPLES_PER_BEAT];
        for (int i = 0; i < BUFFER_SIZE; i++) {
            clipData[0][i] = 1.0f;
            clipData[1][i] = 1.0f;
        }
        clip.setAudioData(clipData);
        track.addClip(clip);

        MixerChannel mixerChannel = new MixerChannel("Track 1");
        mixer.addChannel(mixerChannel);
        mixer.getMasterChannel().setMuted(true);

        transport.play();
        engine.setTransport(transport);
        engine.setMixer(mixer);
        engine.setTracks(List.of(track));
        engine.start();

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        for (int i = 0; i < BUFFER_SIZE; i++) {
            assertThat(output[0][i]).isEqualTo(0.0f);
            assertThat(output[1][i]).isEqualTo(0.0f);
        }
    }

    // ── Transport advance ───────────────────────────────────────────────────

    @Test
    void shouldAdvanceTransportPositionAfterProcessBlock() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Clip", 0.0, 1.0, null);
        clip.setAudioData(new float[CHANNELS][(int) SAMPLES_PER_BEAT]);
        track.addClip(clip);

        MixerChannel mixerChannel = new MixerChannel("Track 1");
        mixer.addChannel(mixerChannel);

        transport.play();
        assertThat(transport.getPositionInBeats()).isEqualTo(0.0);

        engine.setTransport(transport);
        engine.setMixer(mixer);
        engine.setTracks(List.of(track));
        engine.start();

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        // 8 frames at 22050 samples/beat = 8/22050 beats ≈ 0.000363
        double expectedDelta = BUFFER_SIZE / SAMPLES_PER_BEAT;
        assertThat(transport.getPositionInBeats()).isCloseTo(expectedDelta, offset(0.0001));
    }

    @Test
    void shouldNotAdvanceTransportWhenStopped() {
        engine.setTransport(transport);
        engine.setMixer(mixer);
        engine.setTracks(List.of());
        engine.start();

        // Transport is STOPPED
        assertThat(transport.getPositionInBeats()).isEqualTo(0.0);

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        assertThat(transport.getPositionInBeats()).isEqualTo(0.0);
    }

    @Test
    void shouldAdvanceTransportDuringRecording() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        MixerChannel mixerChannel = new MixerChannel("Track 1");
        mixer.addChannel(mixerChannel);

        transport.record();
        assertThat(transport.getState()).isEqualTo(TransportState.RECORDING);

        engine.setTransport(transport);
        engine.setMixer(mixer);
        engine.setTracks(List.of(track));
        engine.start();

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        double expectedDelta = BUFFER_SIZE / SAMPLES_PER_BEAT;
        assertThat(transport.getPositionInBeats()).isCloseTo(expectedDelta, offset(0.0001));
    }

    // ── Loop playback ───────────────────────────────────────────────────────

    @Test
    void shouldSupportLoopPlayback() {
        // Create a clip that spans beat 0–2 with known audio
        Track track = new Track("Track 1", TrackType.AUDIO);
        int samplesFor2Beats = (int) (SAMPLES_PER_BEAT * 2);
        AudioClip clip = new AudioClip("Clip", 0.0, 2.0, null);
        float[][] clipData = new float[CHANNELS][samplesFor2Beats];
        // Fill all samples with 0.5
        for (int i = 0; i < samplesFor2Beats; i++) {
            clipData[0][i] = 0.5f;
            clipData[1][i] = 0.5f;
        }
        clip.setAudioData(clipData);
        track.addClip(clip);

        MixerChannel mixerChannel = new MixerChannel("Track 1");
        mixer.addChannel(mixerChannel);

        // Set loop from beat 0 to 1
        transport.setLoopEnabled(true);
        transport.setLoopRegion(0.0, 1.0);
        transport.play();

        engine.setTransport(transport);
        engine.setMixer(mixer);
        engine.setTracks(List.of(track));
        engine.start();

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];

        // Process many blocks and verify position stays within loop bounds
        for (int block = 0; block < 100; block++) {
            engine.processBlock(input, output, BUFFER_SIZE);
            assertThat(transport.getPositionInBeats()).isLessThan(1.0);
            assertThat(transport.getPositionInBeats()).isGreaterThanOrEqualTo(0.0);
        }
    }

    // ── Multi-track mixing ──────────────────────────────────────────────────

    @Test
    void shouldMixMultipleTracks() {
        Track track1 = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip1 = new AudioClip("Clip1", 0.0, 1.0, null);
        float[][] clipData1 = new float[CHANNELS][(int) SAMPLES_PER_BEAT];
        for (int i = 0; i < BUFFER_SIZE; i++) {
            clipData1[0][i] = 0.3f;
            clipData1[1][i] = 0.3f;
        }
        clip1.setAudioData(clipData1);
        track1.addClip(clip1);

        Track track2 = new Track("Track 2", TrackType.AUDIO);
        AudioClip clip2 = new AudioClip("Clip2", 0.0, 1.0, null);
        float[][] clipData2 = new float[CHANNELS][(int) SAMPLES_PER_BEAT];
        for (int i = 0; i < BUFFER_SIZE; i++) {
            clipData2[0][i] = 0.2f;
            clipData2[1][i] = 0.2f;
        }
        clip2.setAudioData(clipData2);
        track2.addClip(clip2);

        MixerChannel ch1 = new MixerChannel("Track 1");
        MixerChannel ch2 = new MixerChannel("Track 2");
        mixer.addChannel(ch1);
        mixer.addChannel(ch2);

        transport.play();
        engine.setTransport(transport);
        engine.setMixer(mixer);
        engine.setTracks(List.of(track1, track2));
        engine.start();

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        // Both tracks at center pan: gain = cos(π/4) ≈ 0.7071
        // Sum = (0.3 + 0.2) * cos(π/4) ≈ 0.3536
        double expectedGain = (0.3 + 0.2) * Math.cos(Math.PI / 4.0);
        assertThat((double) output[0][0]).isCloseTo(expectedGain, offset(0.001));
    }

    // ── Clip with source offset ─────────────────────────────────────────────

    @Test
    void shouldRespectSourceOffsetBeats() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Clip", 0.0, 1.0, null);
        // Source offset of 0.5 beats means we skip first 0.5 beats of audio data
        clip.setSourceOffsetBeats(0.5);

        int totalSamples = (int) (SAMPLES_PER_BEAT * 2);
        float[][] clipData = new float[1][totalSamples];
        // First half (0.5 beats) = 0.0, second half (0.5 beats) = 0.8
        int halfBeatSamples = (int) (SAMPLES_PER_BEAT * 0.5);
        for (int i = halfBeatSamples; i < totalSamples; i++) {
            clipData[0][i] = 0.8f;
        }
        clip.setAudioData(clipData);
        track.addClip(clip);

        // Use mono format to simplify assertions (no pan applied)
        AudioFormat monoFormat = new AudioFormat(SAMPLE_RATE, 1, 16, BUFFER_SIZE);
        AudioEngine monoEngine = new AudioEngine(monoFormat);

        Mixer monoMixer = new Mixer();
        MixerChannel mixerChannel = new MixerChannel("Track 1");
        monoMixer.addChannel(mixerChannel);

        Transport monoTransport = new Transport();
        monoTransport.setTempo(TEMPO);
        monoTransport.play();

        monoEngine.setTransport(monoTransport);
        monoEngine.setMixer(monoMixer);
        monoEngine.setTracks(List.of(track));
        monoEngine.start();

        float[][] input = new float[1][BUFFER_SIZE];
        float[][] output = new float[1][BUFFER_SIZE];
        monoEngine.processBlock(input, output, BUFFER_SIZE);

        // With source offset of 0.5 beats, we read from sample index
        // 0.5 * 22050 = 11025 in the audio data. Those samples are 0.8f.
        // Mono with master volume 1.0 -> output should be 0.8
        assertThat((double) output[0][0]).isCloseTo(0.8, offset(0.001));
    }

    // ── Null audio data clips are skipped ───────────────────────────────────

    @Test
    void shouldSkipClipsWithNullAudioData() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Clip", 0.0, 1.0, "/path/to/file.wav");
        // audioData is null (external file reference)
        track.addClip(clip);

        MixerChannel mixerChannel = new MixerChannel("Track 1");
        mixer.addChannel(mixerChannel);

        transport.play();
        engine.setTransport(transport);
        engine.setMixer(mixer);
        engine.setTracks(List.of(track));
        engine.start();

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        // Should produce silence without errors
        for (int i = 0; i < BUFFER_SIZE; i++) {
            assertThat(output[0][i]).isEqualTo(0.0f);
            assertThat(output[1][i]).isEqualTo(0.0f);
        }
    }

    // ── Empty track list ────────────────────────────────────────────────────

    @Test
    void shouldProduceSilenceWithNoTracks() {
        transport.play();
        engine.setTransport(transport);
        engine.setMixer(mixer);
        engine.setTracks(List.of());
        engine.start();

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        for (int i = 0; i < BUFFER_SIZE; i++) {
            assertThat(output[0][i]).isEqualTo(0.0f);
            assertThat(output[1][i]).isEqualTo(0.0f);
        }
    }

    // ── Recording callback still works during playback ──────────────────────

    @Test
    void shouldInvokeRecordingCallbackDuringPlayback() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        MixerChannel mixerChannel = new MixerChannel("Track 1");
        mixer.addChannel(mixerChannel);

        transport.record();
        engine.setTransport(transport);
        engine.setMixer(mixer);
        engine.setTracks(List.of(track));
        engine.start();

        int[] callbackCount = {0};
        engine.setRecordingCallback((inputBuffer, numFrames) -> callbackCount[0]++);

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        assertThat(callbackCount[0]).isEqualTo(1);
    }

    // ── Getter/setter coverage ──────────────────────────────────────────────

    @Test
    void shouldGetAndSetTransport() {
        assertThat(engine.getTransport()).isNull();
        engine.setTransport(transport);
        assertThat(engine.getTransport()).isSameAs(transport);
        engine.setTransport(null);
        assertThat(engine.getTransport()).isNull();
    }

    @Test
    void shouldGetAndSetMixer() {
        assertThat(engine.getMixer()).isNull();
        engine.setMixer(mixer);
        assertThat(engine.getMixer()).isSameAs(mixer);
        engine.setMixer(null);
        assertThat(engine.getMixer()).isNull();
    }

    @Test
    void shouldGetAndSetTracks() {
        assertThat(engine.getTracks()).isNull();
        List<Track> trackList = List.of(new Track("T1", TrackType.AUDIO));
        engine.setTracks(trackList);
        assertThat(engine.getTracks()).isSameAs(trackList);
        engine.setTracks(null);
        assertThat(engine.getTracks()).isNull();
    }

    // ── Consecutive blocks advance through clip ─────────────────────────────

    @Test
    void shouldReadConsecutiveSamplesAcrossBlocks() {
        AudioFormat monoFormat = new AudioFormat(SAMPLE_RATE, 1, 16, 4);
        AudioEngine monoEngine = new AudioEngine(monoFormat);

        Track track = new Track("Track 1", TrackType.AUDIO);
        // Create a clip with distinct sample values
        int totalSamples = 16;
        float[][] clipData = new float[1][totalSamples];
        for (int i = 0; i < totalSamples; i++) {
            clipData[0][i] = (i + 1) * 0.01f;
        }

        // Duration in beats to hold totalSamples
        double durationBeats = totalSamples / SAMPLES_PER_BEAT;
        AudioClip clip = new AudioClip("Clip", 0.0, durationBeats + 1.0, null);
        clip.setAudioData(clipData);
        track.addClip(clip);

        Mixer monoMixer = new Mixer();
        MixerChannel mixerChannel = new MixerChannel("Track 1");
        monoMixer.addChannel(mixerChannel);

        Transport monoTransport = new Transport();
        monoTransport.setTempo(TEMPO);
        monoTransport.play();

        monoEngine.setTransport(monoTransport);
        monoEngine.setMixer(monoMixer);
        monoEngine.setTracks(List.of(track));
        monoEngine.start();

        // Process first block of 4 frames
        float[][] input = new float[1][4];
        float[][] output1 = new float[1][4];
        monoEngine.processBlock(input, output1, 4);

        // Process second block of 4 frames
        float[][] output2 = new float[1][4];
        monoEngine.processBlock(input, output2, 4);

        // First block should have samples 0-3, second block should have samples 4-7
        // (mono, master vol=1.0, no pan on mono output)
        assertThat((double) output1[0][0]).isCloseTo(0.01, offset(0.001));
        assertThat((double) output1[0][1]).isCloseTo(0.02, offset(0.001));
        assertThat((double) output1[0][2]).isCloseTo(0.03, offset(0.001));
        assertThat((double) output1[0][3]).isCloseTo(0.04, offset(0.001));

        assertThat((double) output2[0][0]).isCloseTo(0.05, offset(0.001));
        assertThat((double) output2[0][1]).isCloseTo(0.06, offset(0.001));
        assertThat((double) output2[0][2]).isCloseTo(0.07, offset(0.001));
        assertThat((double) output2[0][3]).isCloseTo(0.08, offset(0.001));
    }

    // ── Send/return bus routing in processBlock ─────────────────────────────

    @Test
    void shouldRouteSendToReturnBusDuringProcessBlock() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Clip", 0.0, 1.0, null);
        float[][] clipData = new float[CHANNELS][(int) SAMPLES_PER_BEAT];
        for (int i = 0; i < BUFFER_SIZE; i++) {
            clipData[0][i] = 1.0f;
            clipData[1][i] = 1.0f;
        }
        clip.setAudioData(clipData);
        track.addClip(clip);

        MixerChannel mixerChannel = new MixerChannel("Track 1");
        MixerChannel reverbBus = mixer.getAuxBus();
        // Add a pre-fader send at full level to the return bus
        mixerChannel.addSend(new Send(reverbBus, 1.0, SendMode.PRE_FADER));
        mixer.addChannel(mixerChannel);

        transport.play();
        engine.setTransport(transport);
        engine.setMixer(mixer);
        engine.setTracks(List.of(track));
        engine.start();

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        // Output should include both the direct channel contribution and the
        // return bus contribution (pre-fader send at 1.0 + return bus volume 1.0).
        // With center pan: gain ≈ cos(π/4) ≈ 0.7071
        // Direct contribution per channel: 1.0 * 0.7071
        // Return bus contribution per channel: 1.0 * 1.0 (return bus sums raw pre-fader)
        // Total should be significantly greater than direct-only
        double directGain = Math.cos(Math.PI / 4.0);
        for (int i = 0; i < BUFFER_SIZE; i++) {
            // Output must be greater than just the direct signal
            assertThat((double) output[0][i]).isGreaterThan(directGain);
        }
    }

    @Test
    void shouldNotRouteSendWhenSendLevelIsZero() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Clip", 0.0, 1.0, null);
        float[][] clipData = new float[CHANNELS][(int) SAMPLES_PER_BEAT];
        for (int i = 0; i < BUFFER_SIZE; i++) {
            clipData[0][i] = 0.5f;
            clipData[1][i] = 0.5f;
        }
        clip.setAudioData(clipData);
        track.addClip(clip);

        MixerChannel mixerChannel = new MixerChannel("Track 1");
        MixerChannel reverbBus = mixer.getAuxBus();
        // Send level of 0.0 — should contribute nothing to return bus
        mixerChannel.addSend(new Send(reverbBus, 0.0, SendMode.PRE_FADER));
        mixer.addChannel(mixerChannel);

        transport.play();
        engine.setTransport(transport);
        engine.setMixer(mixer);
        engine.setTracks(List.of(track));
        engine.start();

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        // Output should be only the direct channel contribution (no return bus)
        double expectedGain = Math.cos(Math.PI / 4.0);
        for (int i = 0; i < BUFFER_SIZE; i++) {
            assertThat((double) output[0][i]).isCloseTo(0.5 * expectedGain, offset(0.001));
        }
    }

    @Test
    void shouldApplyPostFaderSendWithChannelVolume() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Clip", 0.0, 1.0, null);
        float[][] clipData = new float[CHANNELS][(int) SAMPLES_PER_BEAT];
        for (int i = 0; i < BUFFER_SIZE; i++) {
            clipData[0][i] = 1.0f;
            clipData[1][i] = 1.0f;
        }
        clip.setAudioData(clipData);
        track.addClip(clip);

        MixerChannel mixerChannel = new MixerChannel("Track 1");
        mixerChannel.setVolume(0.5);
        MixerChannel reverbBus = mixer.getAuxBus();
        // Post-fader send: send level is multiplied by channel volume
        mixerChannel.addSend(new Send(reverbBus, 1.0, SendMode.POST_FADER));
        mixer.addChannel(mixerChannel);

        transport.play();
        engine.setTransport(transport);
        engine.setMixer(mixer);
        engine.setTracks(List.of(track));
        engine.start();

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] outputWithSend = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, outputWithSend, BUFFER_SIZE);

        // Post-fader send at volume 0.5: return bus gets 0.5 * 1.0 * signal = 0.5 per channel.
        // Direct contribution (stereo, center pan): 1.0 * 0.5 * cos(π/4) per left channel.
        // Return bus contribution (mono sum into output): 0.5 * 1.0 (return bus vol) per channel.
        // Total left output = direct + return = 0.5 * cos(π/4) + 0.5 ≈ 0.8536
        double directGainPerChannel = 0.5 * Math.cos(Math.PI / 4.0);
        double returnContribution = 0.5; // post-fader: volume * sendLevel * signal * returnBusVol
        double expectedTotal = directGainPerChannel + returnContribution;
        for (int i = 0; i < BUFFER_SIZE; i++) {
            assertThat((double) outputWithSend[0][i]).isCloseTo(expectedTotal, offset(0.01));
        }
    }
}
