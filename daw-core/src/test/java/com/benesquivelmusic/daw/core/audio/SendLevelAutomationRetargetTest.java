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

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 322 — the {@code SEND_LEVEL} automation lane is retargeted at the
 * live multi-bus send path: it writes the level of the channel's {@code Send}
 * aimed at the first return bus, and that measurably changes return-bus
 * audio. The legacy scalar {@code MixerChannel.sendLevel} and the dead
 * single-aux {@code mixDown} overload no longer exist (Audio Engine Wiring
 * Design Book §1.8, §5.6 "Send levels").
 */
class SendLevelAutomationRetargetTest {

    private static final double SAMPLE_RATE = 44_100.0;
    private static final int CHANNELS = 2;
    private static final int BUFFER_SIZE = 8;
    private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, CHANNELS, 16, BUFFER_SIZE);
    private static final double AUTOMATED_SEND = 0.6;
    private static final double RETURN_GAIN = 0.8;
    private static final double CHANNEL_CENTRE = Math.cos(Math.PI / 4.0);

    private static Track trackWithUnityClip() {
        Track track = new Track("Vox", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Clip", 0.0, 1.0, null);
        float[][] data = new float[CHANNELS][(int) (SAMPLE_RATE * 60.0 / 120.0)];
        for (float[] lane : data) {
            Arrays.fill(lane, 1.0f);
        }
        clip.setAudioData(data);
        track.addClip(clip);
        return track;
    }

    private static float[][] render(Mixer mixer, Track track) {
        AudioEngine engine = new AudioEngine(FORMAT);
        Transport transport = new Transport();
        transport.setTempo(120.0);
        transport.play();
        engine.setGraph(transport, mixer, List.of(track));
        engine.start();
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(new float[CHANNELS][BUFFER_SIZE], output, BUFFER_SIZE);
        return output;
    }

    @Test
    void sendLevelLaneChangesReturnBusZeroAudio() {
        Track track = trackWithUnityClip();
        track.getAutomationData().getOrCreateLane(AutomationParameter.SEND_LEVEL)
                .addPoint(new AutomationPoint(0.0, AUTOMATED_SEND, InterpolationMode.LINEAR));

        Mixer mixer = new Mixer();
        mixer.getAuxBus().setVolume(RETURN_GAIN);
        MixerChannel channel = new MixerChannel("Vox");
        Send auxSend = new Send(mixer.getAuxBus(), 0.0, SendTap.POST_FADER);
        channel.addSend(auxSend);
        mixer.addChannel(channel);

        float[][] output = render(mixer, track);

        assertThat(auxSend.getLevel())
                .as("the lane wrote the send aimed at the first return bus")
                .isCloseTo(AUTOMATED_SEND, within(1e-9));
        // direct: cos(π/4) per lane; return: send × fader(1.0) × return gain
        // (the return's own pan is centre → unity), summed into the master.
        double expected = CHANNEL_CENTRE + AUTOMATED_SEND * RETURN_GAIN;
        assertThat((double) output[0][0]).isCloseTo(expected, within(1e-4));
        assertThat((double) output[1][0]).isCloseTo(expected, within(1e-4));
    }

    @Test
    void withoutTheLaneTheReturnBusStaysSilentSoTheDifferenceIsTheLane() {
        Track track = trackWithUnityClip();
        Mixer mixer = new Mixer();
        mixer.getAuxBus().setVolume(RETURN_GAIN);
        MixerChannel channel = new MixerChannel("Vox");
        channel.addSend(new Send(mixer.getAuxBus(), 0.0, SendTap.POST_FADER));
        mixer.addChannel(channel);

        float[][] output = render(mixer, track);

        assertThat((double) output[0][0]).isCloseTo(CHANNEL_CENTRE, within(1e-4));
    }

    /**
     * Fix round 1, B1 — with NO return bus left the lane is inert, not
     * fatal. A {@code SEND_LEVEL} lane outlives the bus it targeted (it is
     * user-selectable and deserialised from disk) while
     * {@code Mixer.removeReturnBus} deliberately has no last-bus guard, so
     * the automation arm must never dereference {@code returnBuses.get(0)}
     * on the audio thread. Rendered through the same engine path as the
     * cases above; without the guard {@code processBlock} throws an
     * {@code IndexOutOfBoundsException} out of the render thread. The
     * no-lane control pins the output to the unchanged, audible direct
     * signal rather than to a zeroed block.
     */
    @Test
    void laneIsInertWhenTheMixerHasNoReturnBus() {
        MixerChannel channel = new MixerChannel("Vox");
        Mixer mixer = mixerWhoseOnlyReturnBusWasRemoved(channel);
        Track automated = trackWithUnityClip();
        automated.getAutomationData().getOrCreateLane(AutomationParameter.SEND_LEVEL)
                .addPoint(new AutomationPoint(0.0, AUTOMATED_SEND, InterpolationMode.LINEAR));

        float[][] output = render(mixer, automated);
        float[][] control = render(
                mixerWhoseOnlyReturnBusWasRemoved(new MixerChannel("Vox")), trackWithUnityClip());

        assertThat(mixer.getReturnBusCount()).isZero();
        assertThat(channel.getSends()).as("the lane never creates routing").isEmpty();
        assertThat((double) control[0][0])
                .as("the control is the audible direct signal, not silence")
                .isCloseTo(CHANNEL_CENTRE, within(1e-4));
        assertThat(output[0]).containsExactly(control[0]);
        assertThat(output[1]).containsExactly(control[1]);
    }

    /**
     * The gesture the guard exists for: the channel sent to the default
     * return bus, then the user removed that bus — {@code removeReturnBus}
     * strips the send with it and leaves the mixer with no return bus.
     */
    private static Mixer mixerWhoseOnlyReturnBusWasRemoved(MixerChannel channel) {
        Mixer mixer = new Mixer();
        channel.addSend(new Send(mixer.getAuxBus(), 0.3, SendTap.POST_FADER));
        mixer.addChannel(channel);
        assertThat(mixer.removeReturnBus(mixer.getAuxBus())).isTrue();
        return mixer;
    }

    @Test
    void legacyScalarSendLevelNoLongerExistsOnMixerChannel() {
        assertThat(MixerChannel.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .doesNotContain("sendLevel");
        assertThat(MixerChannel.class.getMethods())
                .extracting(Method::getName)
                .doesNotContain("getSendLevel", "setSendLevel");
    }

    @Test
    void deadSingleAuxMixDownOverloadNoLongerExistsOnMixer() {
        List<Method> auxOverloads = Arrays.stream(Mixer.class.getMethods())
                .filter(m -> m.getName().equals("mixDown"))
                .filter(m -> Arrays.equals(m.getParameterTypes(),
                        new Class<?>[] {float[][][].class, float[][].class, float[][].class, int.class}))
                .toList();

        assertThat(auxOverloads)
                .as("mixDown(float[][][], float[][], float[][] aux, int) was the dead aux path")
                .isEmpty();
    }
}
