package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.automation.AutomationParameter;
import com.benesquivelmusic.daw.core.automation.AutomationPoint;
import com.benesquivelmusic.daw.core.automation.InterpolationMode;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.Send;
import com.benesquivelmusic.daw.core.mixer.SendTap;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 322 contract (probe-derived, kept as a permanent guard: with TWO return buses it discriminates a lane aimed at the wrong bus, which the single-bus retarget test cannot) — a SEND_LEVEL lane moves the level of the send aimed at
 * return bus 0 ONLY, through the engine's real render path. Two return buses
 * with sends at different taps (bus 0 POST_FADER starting at 0.1, bus 1
 * PRE_FADER at 0.55), a 0.9 clip, an automated level of 0.81, and a channel
 * fader of 0.81 so a post-fader send is distinguishable from a pre-fader one.
 */
class Story322SendLevelRetargetContractTest {

    private static final double SAMPLE_RATE = 48_000.0;
    private static final int CHANNELS = 2;
    private static final int FRAMES = 16;
    private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, CHANNELS, 24, FRAMES);
    private static final float CLIP_LEVEL = 0.9f;
    private static final double FADER = 0.81;
    private static final double LANE_LEVEL = 0.81;
    private static final double SEND_A_START = 0.1;
    private static final double SEND_B = 0.55;
    private static final double RETURN_A_GAIN = 0.55;
    private static final double RETURN_B_GAIN = 0.37;
    private static final double TOL = 1e-4;

    private static Track trackWithClip(boolean withLane) {
        Track track = new Track("Vox", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Clip", 0.0, 1.0, null);
        float[][] data = new float[CHANNELS][(int) (SAMPLE_RATE * 60.0 / 120.0)];
        for (float[] lane : data) {
            Arrays.fill(lane, CLIP_LEVEL);
        }
        clip.setAudioData(data);
        track.addClip(clip);
        if (withLane) {
            track.getAutomationData().getOrCreateLane(AutomationParameter.SEND_LEVEL)
                    .addPoint(new AutomationPoint(0.0, LANE_LEVEL, InterpolationMode.LINEAR));
        }
        return track;
    }

    private record Rig(Mixer mixer, MixerChannel channel, Send sendA, Send sendB) { }

    private static Rig rig(boolean withSendToBusZero) {
        Mixer mixer = new Mixer();
        MixerChannel returnA = mixer.getAuxBus();
        returnA.setVolume(RETURN_A_GAIN);
        MixerChannel returnB = mixer.addReturnBus("Verb B");
        returnB.setVolume(RETURN_B_GAIN);
        MixerChannel channel = new MixerChannel("Vox");
        channel.setVolume(FADER);
        Send sendA = withSendToBusZero ? new Send(returnA, SEND_A_START, SendTap.POST_FADER) : null;
        if (sendA != null) {
            channel.addSend(sendA);
        }
        Send sendB = new Send(returnB, SEND_B, SendTap.PRE_FADER);
        channel.addSend(sendB);
        mixer.addChannel(channel);
        return new Rig(mixer, channel, sendA, sendB);
    }

    private static float[][] render(Mixer mixer, Track track) {
        AudioEngine engine = new AudioEngine(FORMAT);
        Transport transport = new Transport();
        transport.setTempo(120.0);
        transport.play();
        engine.setGraph(transport, mixer, List.of(track));
        engine.start();
        float[][] output = new float[CHANNELS][FRAMES];
        engine.processBlock(new float[CHANNELS][FRAMES], output, FRAMES);
        return output;
    }

    @Test
    void theLaneWritesOnlyTheSendAimedAtReturnBusZeroAndTheAudioFollows() {
        Rig rig = rig(true);
        float[][] out = render(rig.mixer(), trackWithClip(true));

        assertThat(rig.sendA().getLevel()).as("bus-0 send took the lane value").isCloseTo(LANE_LEVEL, within(1e-9));
        assertThat(rig.sendB().getLevel()).as("bus-1 send is untouched by the lane").isEqualTo(SEND_B);

        double direct = CLIP_LEVEL * Math.cos(Math.PI / 4.0) * FADER;
        double returnA = CLIP_LEVEL * LANE_LEVEL * FADER * RETURN_A_GAIN;  // post-fader rides the fader
        double returnB = CLIP_LEVEL * SEND_B * RETURN_B_GAIN;              // pre-fader does not
        for (int f = 0; f < FRAMES; f++) {
            assertThat((double) out[0][f]).as("L frame %d", f).isCloseTo(direct + returnA + returnB, within(TOL));
            assertThat((double) out[1][f]).as("R frame %d", f).isCloseTo(direct + returnA + returnB, within(TOL));
        }
    }

    @Test
    void withoutTheLaneTheBusZeroSendKeepsItsOwnLevelSoTheDifferenceIsExactlyTheLane() {
        Rig withLane = rig(true);
        float[][] automated = render(withLane.mixer(), trackWithClip(true));
        Rig noLane = rig(true);
        float[][] plain = render(noLane.mixer(), trackWithClip(false));

        assertThat(noLane.sendA().getLevel()).isEqualTo(SEND_A_START);
        double delta = CLIP_LEVEL * (LANE_LEVEL - SEND_A_START) * FADER * RETURN_A_GAIN;
        for (int f = 0; f < FRAMES; f++) {
            assertThat((double) (automated[0][f] - plain[0][f])).isCloseTo(delta, within(TOL));
            assertThat((double) (automated[1][f] - plain[1][f])).isCloseTo(delta, within(TOL));
        }
    }

    @Test
    void aChannelWithNoSendToBusZeroLeavesTheLaneInertAndNeverTouchesBusOne() {
        Rig rig = rig(false);
        float[][] out = render(rig.mixer(), trackWithClip(true));

        assertThat(rig.sendB().getLevel()).isEqualTo(SEND_B);
        assertThat(rig.channel().getSends()).hasSize(1);
        double direct = CLIP_LEVEL * Math.cos(Math.PI / 4.0) * FADER;
        double returnB = CLIP_LEVEL * SEND_B * RETURN_B_GAIN;
        for (int f = 0; f < FRAMES; f++) {
            assertThat((double) out[0][f]).isCloseTo(direct + returnB, within(TOL));
        }
    }
}
