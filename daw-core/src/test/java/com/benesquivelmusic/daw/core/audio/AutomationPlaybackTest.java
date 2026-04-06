package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.automation.AutomationParameter;
import com.benesquivelmusic.daw.core.automation.AutomationPoint;
import com.benesquivelmusic.daw.core.automation.InterpolationMode;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.track.AutomationMode;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * Tests for automation lane playback integration with the audio engine.
 *
 * <p>Verifies that automation values for volume, pan, mute, and send level
 * are applied to mixer channel parameters during {@link AudioEngine#processBlock}
 * when a track's automation mode is set to {@link AutomationMode#READ}.</p>
 */
class AutomationPlaybackTest {

    private static final double SAMPLE_RATE = 44_100.0;
    private static final int CHANNELS = 2;
    private static final int BUFFER_SIZE = 8;
    private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, CHANNELS, 16, BUFFER_SIZE);

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

    /**
     * Helper: creates a track with a 1-beat clip filled with the given sample value.
     */
    private Track createTrackWithClip(String name, float sampleValue) {
        Track track = new Track(name, TrackType.AUDIO);
        AudioClip clip = new AudioClip("Clip", 0.0, 1.0, null);
        float[][] clipData = new float[CHANNELS][(int) SAMPLES_PER_BEAT];
        for (int i = 0; i < clipData[0].length; i++) {
            clipData[0][i] = sampleValue;
            clipData[1][i] = sampleValue;
        }
        clip.setAudioData(clipData);
        track.addClip(clip);
        return track;
    }

    /**
     * Helper: configures the engine and runs one processBlock call.
     */
    private float[][] runProcessBlock(List<Track> tracks) {
        transport.play();
        engine.setTransport(transport);
        engine.setMixer(mixer);
        engine.setTracks(tracks);
        engine.start();

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);
        return output;
    }

    // ── Volume automation ──────────────────────────────────────────────────

    @Test
    void shouldApplyVolumeAutomationToOutput() {
        Track track = createTrackWithClip("Track 1", 1.0f);

        // Automate volume to 0.5 at beat 0
        track.getAutomationData().getOrCreateLane(AutomationParameter.VOLUME)
                .addPoint(new AutomationPoint(0.0, 0.5, InterpolationMode.LINEAR));

        MixerChannel mixerChannel = new MixerChannel("Track 1");
        mixer.addChannel(mixerChannel);

        float[][] output = runProcessBlock(List.of(track));

        // Volume automated to 0.5, center pan: gain = 0.5 * cos(pi/4)
        double expectedGain = 0.5 * Math.cos(Math.PI / 4.0);
        for (int i = 0; i < BUFFER_SIZE; i++) {
            assertThat((double) output[0][i]).isCloseTo(expectedGain, offset(0.001));
            assertThat((double) output[1][i]).isCloseTo(expectedGain, offset(0.001));
        }
    }

    @Test
    void shouldApplyVolumeAutomationOverridingStaticFaderValue() {
        Track track = createTrackWithClip("Track 1", 1.0f);

        // Static fader at 1.0 (default), automation sets volume to 0.25
        track.getAutomationData().getOrCreateLane(AutomationParameter.VOLUME)
                .addPoint(new AutomationPoint(0.0, 0.25, InterpolationMode.LINEAR));

        MixerChannel mixerChannel = new MixerChannel("Track 1");
        mixerChannel.setVolume(1.0); // static value should be overridden
        mixer.addChannel(mixerChannel);

        float[][] output = runProcessBlock(List.of(track));

        double expectedGain = 0.25 * Math.cos(Math.PI / 4.0);
        assertThat((double) output[0][0]).isCloseTo(expectedGain, offset(0.001));
    }

    @Test
    void shouldApplyZeroVolumeAutomationToSilenceOutput() {
        Track track = createTrackWithClip("Track 1", 1.0f);

        track.getAutomationData().getOrCreateLane(AutomationParameter.VOLUME)
                .addPoint(new AutomationPoint(0.0, 0.0, InterpolationMode.LINEAR));

        MixerChannel mixerChannel = new MixerChannel("Track 1");
        mixer.addChannel(mixerChannel);

        float[][] output = runProcessBlock(List.of(track));

        for (int i = 0; i < BUFFER_SIZE; i++) {
            assertThat(output[0][i]).isEqualTo(0.0f);
            assertThat(output[1][i]).isEqualTo(0.0f);
        }
    }

    // ── Pan automation ─────────────────────────────────────────────────────

    @Test
    void shouldApplyPanAutomationFullLeft() {
        Track track = createTrackWithClip("Track 1", 1.0f);

        // Automate pan to full left (-1.0)
        track.getAutomationData().getOrCreateLane(AutomationParameter.PAN)
                .addPoint(new AutomationPoint(0.0, -1.0, InterpolationMode.LINEAR));

        MixerChannel mixerChannel = new MixerChannel("Track 1");
        mixer.addChannel(mixerChannel);

        float[][] output = runProcessBlock(List.of(track));

        // Full left: angle=0, leftGain=cos(0)*1.0=1.0, rightGain=sin(0)*1.0=0.0
        assertThat((double) output[0][0]).isCloseTo(1.0, offset(0.001));
        assertThat((double) output[1][0]).isCloseTo(0.0, offset(0.001));
    }

    @Test
    void shouldApplyPanAutomationFullRight() {
        Track track = createTrackWithClip("Track 1", 1.0f);

        // Automate pan to full right (1.0)
        track.getAutomationData().getOrCreateLane(AutomationParameter.PAN)
                .addPoint(new AutomationPoint(0.0, 1.0, InterpolationMode.LINEAR));

        MixerChannel mixerChannel = new MixerChannel("Track 1");
        mixer.addChannel(mixerChannel);

        float[][] output = runProcessBlock(List.of(track));

        // Full right: angle=pi/2, leftGain=cos(pi/2)*1.0≈0, rightGain=sin(pi/2)*1.0=1.0
        assertThat((double) output[0][0]).isCloseTo(0.0, offset(0.001));
        assertThat((double) output[1][0]).isCloseTo(1.0, offset(0.001));
    }

    // ── Mute automation ────────────────────────────────────────────────────

    @Test
    void shouldApplyMuteAutomationToSilenceChannel() {
        Track track = createTrackWithClip("Track 1", 1.0f);

        // Automate mute to 1.0 (muted)
        track.getAutomationData().getOrCreateLane(AutomationParameter.MUTE)
                .addPoint(new AutomationPoint(0.0, 1.0, InterpolationMode.LINEAR));

        MixerChannel mixerChannel = new MixerChannel("Track 1");
        mixer.addChannel(mixerChannel);

        float[][] output = runProcessBlock(List.of(track));

        for (int i = 0; i < BUFFER_SIZE; i++) {
            assertThat(output[0][i]).isEqualTo(0.0f);
            assertThat(output[1][i]).isEqualTo(0.0f);
        }
    }

    @Test
    void shouldNotMuteWhenMuteAutomationBelowThreshold() {
        Track track = createTrackWithClip("Track 1", 1.0f);

        // Automate mute to 0.0 (unmuted)
        track.getAutomationData().getOrCreateLane(AutomationParameter.MUTE)
                .addPoint(new AutomationPoint(0.0, 0.0, InterpolationMode.LINEAR));

        MixerChannel mixerChannel = new MixerChannel("Track 1");
        mixer.addChannel(mixerChannel);

        float[][] output = runProcessBlock(List.of(track));

        // Should be audible: default volume=1.0, center pan
        double expectedGain = Math.cos(Math.PI / 4.0);
        assertThat((double) output[0][0]).isCloseTo(expectedGain, offset(0.001));
    }

    // ── Send level automation ──────────────────────────────────────────────

    @Test
    void shouldApplySendLevelAutomation() {
        Track track = createTrackWithClip("Track 1", 1.0f);

        // Automate send level to 0.75
        track.getAutomationData().getOrCreateLane(AutomationParameter.SEND_LEVEL)
                .addPoint(new AutomationPoint(0.0, 0.75, InterpolationMode.LINEAR));

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

        // After processBlock, the mixer channel's send level should be 0.75
        assertThat(mixerChannel.getSendLevel()).isCloseTo(0.75, offset(0.001));
    }

    // ── Automation read mode disable ───────────────────────────────────────

    @Test
    void shouldNotApplyAutomationWhenModeIsOff() {
        Track track = createTrackWithClip("Track 1", 1.0f);
        track.setAutomationMode(AutomationMode.OFF);

        // Automate volume to 0.0 (silence) — should be ignored
        track.getAutomationData().getOrCreateLane(AutomationParameter.VOLUME)
                .addPoint(new AutomationPoint(0.0, 0.0, InterpolationMode.LINEAR));

        MixerChannel mixerChannel = new MixerChannel("Track 1");
        mixerChannel.setVolume(1.0);
        mixer.addChannel(mixerChannel);

        float[][] output = runProcessBlock(List.of(track));

        // Automation OFF — static volume 1.0 should be used, output should be audible
        double expectedGain = Math.cos(Math.PI / 4.0);
        for (int i = 0; i < BUFFER_SIZE; i++) {
            assertThat((double) output[0][i]).isCloseTo(expectedGain, offset(0.001));
        }
    }

    @Test
    void shouldDefaultToAutomationReadMode() {
        Track track = new Track("Track 1", TrackType.AUDIO);
        assertThat(track.getAutomationMode()).isEqualTo(AutomationMode.READ);
    }

    @Test
    void shouldApplyAutomationPerTrackIndependently() {
        // Track 1: automation OFF, static volume 1.0
        Track track1 = createTrackWithClip("Track 1", 1.0f);
        track1.setAutomationMode(AutomationMode.OFF);
        track1.getAutomationData().getOrCreateLane(AutomationParameter.VOLUME)
                .addPoint(new AutomationPoint(0.0, 0.0, InterpolationMode.LINEAR));

        // Track 2: automation READ, volume automated to 0.5
        Track track2 = createTrackWithClip("Track 2", 1.0f);
        track2.getAutomationData().getOrCreateLane(AutomationParameter.VOLUME)
                .addPoint(new AutomationPoint(0.0, 0.5, InterpolationMode.LINEAR));

        MixerChannel ch1 = new MixerChannel("Track 1");
        MixerChannel ch2 = new MixerChannel("Track 2");
        mixer.addChannel(ch1);
        mixer.addChannel(ch2);

        float[][] output = runProcessBlock(List.of(track1, track2));

        // Track 1: automation OFF, uses static volume 1.0
        // Track 2: automation READ, uses automated volume 0.5
        // Combined output = (1.0 + 0.5) * cos(pi/4)
        double centerGain = Math.cos(Math.PI / 4.0);
        double expectedOutput = (1.0 + 0.5) * centerGain;
        assertThat((double) output[0][0]).isCloseTo(expectedOutput, offset(0.001));
    }

    // ── No automation data ─────────────────────────────────────────────────

    @Test
    void shouldUseDefaultValuesWhenNoAutomationPointsExist() {
        Track track = createTrackWithClip("Track 1", 1.0f);
        // AutomationMode.READ is default but no automation points are added
        // Default values: volume=1.0, pan=0.0, mute=0.0, sendLevel=0.0

        MixerChannel mixerChannel = new MixerChannel("Track 1");
        mixer.addChannel(mixerChannel);

        float[][] output = runProcessBlock(List.of(track));

        // Default automation values match default MixerChannel values
        double expectedGain = Math.cos(Math.PI / 4.0);
        for (int i = 0; i < BUFFER_SIZE; i++) {
            assertThat((double) output[0][i]).isCloseTo(expectedGain, offset(0.001));
        }
    }

    // ── Interpolated automation ────────────────────────────────────────────

    @Test
    void shouldApplyInterpolatedVolumeAutomation() {
        Track track = createTrackWithClip("Track 1", 1.0f);

        // Volume ramp: 1.0 at beat 0, 0.0 at beat 1
        var lane = track.getAutomationData().getOrCreateLane(AutomationParameter.VOLUME);
        lane.addPoint(new AutomationPoint(0.0, 1.0, InterpolationMode.LINEAR));
        lane.addPoint(new AutomationPoint(1.0, 0.0, InterpolationMode.LINEAR));

        MixerChannel mixerChannel = new MixerChannel("Track 1");
        mixer.addChannel(mixerChannel);

        // Position at beat 0.5 — linear interpolation should yield volume ≈ 0.5
        transport.setPositionInBeats(0.5);
        transport.play();
        engine.setTransport(transport);
        engine.setMixer(mixer);
        engine.setTracks(List.of(track));
        engine.start();

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        double expectedGain = 0.5 * Math.cos(Math.PI / 4.0);
        assertThat((double) output[0][0]).isCloseTo(expectedGain, offset(0.01));
    }
}
