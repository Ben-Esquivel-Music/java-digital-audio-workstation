package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 322 contract (probe-derived, kept as a permanent guard for §5.6 "VCA gain reaches the render path") — VCA audibility through a {@link DawProject} (the project
 * wires its own {@link VcaGroupManager} into the mixer). Independent inputs:
 * two OVERLAPPING groups (A at −4.3 dB over kick+snare, B at −2.0 dB over
 * snare only), a 30 %-duty 0.9 pulse whose RMS/peak is neither 1 nor 1/√2,
 * odd fader gains (0.81 / 1.0 / 0.37), off-centre channel pans (−0.62 / 0 /
 * +0.81), and two return buses fed at different taps (kick → return A
 * POST_FADER at 0.37, snare → return B PRE_FADER at 0.55). Every expected
 * value is computed here from the documented laws, never from the mixer.
 */
class Story322VcaCompositeGainContractTest {

    private static final int FRAMES = 64;
    private static final double GROUP_A_DB = -4.3;
    private static final double GROUP_B_DB = -2.0;
    private static final double VOL_KICK = 0.81;
    private static final double PAN_KICK = -0.62;
    private static final double VOL_SNARE = 1.0;
    private static final double PAN_SNARE = 0.0;
    private static final double VOL_HAT = 0.37;
    private static final double PAN_HAT = 0.81;
    private static final float SNARE_LEVEL = 0.5f;
    private static final float HAT_LEVEL = -0.25f;
    private static final double SEND_KICK_POST = 0.37;
    private static final double SEND_SNARE_PRE = 0.55;
    private static final double TOL = 1e-4;

    private DawProject project;
    private MixerChannel chKick;
    private MixerChannel chSnare;
    private MixerChannel chHat;
    private VcaGroup groupA;
    private VcaGroup groupB;

    /** The duty-cycle test signal: 0.9 for three of every ten frames, else silence. */
    private static float pulse(int frame) {
        return frame % 10 < 3 ? 0.9f : 0.0f;
    }

    private static double channelLeft(double pan, double gain) {
        return Math.cos((pan + 1.0) * Math.PI / 4.0) * gain;
    }

    private static double channelRight(double pan, double gain) {
        return Math.sin((pan + 1.0) * Math.PI / 4.0) * gain;
    }

    private static double linear(double db) {
        return Math.pow(10.0, db / 20.0);
    }

    @BeforeEach
    void setUp() {
        project = new DawProject("Probe322", new AudioFormat(48_000, 2, 24, FRAMES));
        Track kick = project.createAudioTrack("Kick");
        Track snare = project.createAudioTrack("Snare");
        Track hat = project.createAudioTrack("Hat");
        chKick = project.getMixerChannelForTrack(kick);
        chSnare = project.getMixerChannelForTrack(snare);
        chHat = project.getMixerChannelForTrack(hat);
        assertThat(project.getMixer().getChannels()).containsExactly(chKick, chSnare, chHat);

        chKick.setVolume(VOL_KICK);
        chKick.setPan(PAN_KICK);
        chSnare.setVolume(VOL_SNARE);
        chSnare.setPan(PAN_SNARE);
        chHat.setVolume(VOL_HAT);
        chHat.setPan(PAN_HAT);

        MixerChannel returnA = project.getMixer().getAuxBus();
        MixerChannel returnB = project.getMixer().addReturnBus("Verb B");
        chKick.addSend(new Send(returnA, SEND_KICK_POST, SendTap.POST_FADER));
        chSnare.addSend(new Send(returnB, SEND_SNARE_PRE, SendTap.PRE_FADER));

        VcaGroupManager vca = project.getVcaGroupManager();
        groupA = vca.createVcaGroup("A", List.of(chKick.getId(), chSnare.getId()));
        vca.setMasterGainDb(groupA.id(), GROUP_A_DB);
        groupB = vca.createVcaGroup("B", List.of(chSnare.getId()));
        vca.setMasterGainDb(groupB.id(), GROUP_B_DB);
    }

    private record Rendered(float[][] out, float[][][] returns) { }

    private Rendered render() {
        float[][][] chans = new float[3][2][FRAMES];
        for (int f = 0; f < FRAMES; f++) {
            chans[0][0][f] = chans[0][1][f] = pulse(f);
            chans[1][0][f] = chans[1][1][f] = SNARE_LEVEL;
            chans[2][0][f] = chans[2][1][f] = HAT_LEVEL;
        }
        float[][] out = new float[2][FRAMES];
        float[][][] returns = new float[2][2][FRAMES];
        project.getMixer().mixDown(chans, out, returns, FRAMES);
        return new Rendered(out, returns);
    }

    @Test
    void memberGainIsTheCompositeOfEveryOverlappingGroupAndPostFaderSendsRideIt() {
        double gainA = linear(GROUP_A_DB);
        double gainAB = linear(GROUP_A_DB + GROUP_B_DB);
        Rendered r = render();

        for (int f = 0; f < FRAMES; f++) {
            double kick = pulse(f);
            double returnA = kick * SEND_KICK_POST * VOL_KICK * gainA;   // post-fader: fader × VCA
            double returnB = SNARE_LEVEL * SEND_SNARE_PRE;                // pre-fader: neither
            double left = kick * channelLeft(PAN_KICK, VOL_KICK * gainA)
                    + SNARE_LEVEL * channelLeft(PAN_SNARE, VOL_SNARE * gainAB)
                    + HAT_LEVEL * channelLeft(PAN_HAT, VOL_HAT)
                    + returnA + returnB;
            double right = kick * channelRight(PAN_KICK, VOL_KICK * gainA)
                    + SNARE_LEVEL * channelRight(PAN_SNARE, VOL_SNARE * gainAB)
                    + HAT_LEVEL * channelRight(PAN_HAT, VOL_HAT)
                    + returnA + returnB;
            assertThat((double) r.out()[0][f]).as("L frame %d", f).isCloseTo(left, within(TOL));
            assertThat((double) r.out()[1][f]).as("R frame %d", f).isCloseTo(right, within(TOL));
            assertThat((double) r.returns()[0][0][f]).as("return A L frame %d", f)
                    .isCloseTo(returnA, within(TOL));
            assertThat((double) r.returns()[0][1][f]).as("return A R frame %d", f)
                    .isCloseTo(returnA, within(TOL));
            assertThat((double) r.returns()[1][0][f]).as("return B L frame %d", f)
                    .isCloseTo(returnB, within(TOL));
        }
    }

    @Test
    void aGroupAtTheFloorSilencesItsMembersAndTheirPostFaderSendsButNotAPreFaderSend() {
        project.getVcaGroupManager().setMasterGainDb(groupA.id(), VcaGroup.MIN_GAIN_DB);
        Rendered r = render();

        for (int f = 0; f < FRAMES; f++) {
            double returnB = SNARE_LEVEL * SEND_SNARE_PRE;
            double left = HAT_LEVEL * channelLeft(PAN_HAT, VOL_HAT) + returnB;
            double right = HAT_LEVEL * channelRight(PAN_HAT, VOL_HAT) + returnB;
            assertThat((double) r.out()[0][f]).as("L frame %d", f).isCloseTo(left, within(TOL));
            assertThat((double) r.out()[1][f]).as("R frame %d", f).isCloseTo(right, within(TOL));
            assertThat(r.returns()[0][0][f]).as("post-fader send of a floored member is silent").isZero();
            assertThat((double) r.returns()[1][0][f]).as("pre-fader send survives the floor")
                    .isCloseTo(returnB, within(TOL));
        }
        // Group B alone still attenuates the snare's own gain once A is lifted again.
        project.getVcaGroupManager().setMasterGainDb(groupA.id(), 0.0);
        assertThat(project.getVcaGroupManager().effectiveLinearMultiplier(chSnare.getId()))
                .isCloseTo(linear(GROUP_B_DB), within(1e-12));
    }

    @Test
    void theDutyCycleSignalsRmsAndPeakScaleByTheCompositeGainUnderSolo() {
        chKick.setSolo(true);
        Rendered r = render();

        double inputPeak = 0.9;
        double sumSq = 0.0;
        for (int f = 0; f < FRAMES; f++) {
            sumSq += pulse(f) * pulse(f);
        }
        double inputRms = Math.sqrt(sumSq / FRAMES);
        assertThat(inputRms).as("the probe signal is neither full scale nor 1/sqrt2")
                .isNotCloseTo(1.0, within(0.05)).isNotCloseTo(Math.sqrt(0.5), within(0.05));

        // Return buses are solo-safe by default (Mixer.addReturnBus), so the
        // soloed kick's POST_FADER send (fader × VCA) still reaches the block;
        // the gated snare is never routed, so its PRE_FADER send to return B is.
        double gainA = linear(GROUP_A_DB);
        double sendA = SEND_KICK_POST * VOL_KICK * gainA;
        double laneGainL = channelLeft(PAN_KICK, VOL_KICK * gainA) + sendA;
        double laneGainR = channelRight(PAN_KICK, VOL_KICK * gainA) + sendA;
        assertThat(peak(r.out()[0])).isCloseTo(inputPeak * laneGainL, within(TOL));
        assertThat(rms(r.out()[0])).isCloseTo(inputRms * laneGainL, within(TOL));
        assertThat(peak(r.out()[1])).isCloseTo(inputPeak * laneGainR, within(TOL));
        assertThat(rms(r.out()[1])).isCloseTo(inputRms * laneGainR, within(TOL));
        assertThat(peak(r.returns()[0][0])).isCloseTo(inputPeak * sendA, within(TOL));
        assertThat(rms(r.returns()[0][0])).isCloseTo(inputRms * sendA, within(TOL));
        for (int f = 0; f < FRAMES; f++) {
            assertThat(r.returns()[1][0][f]).as("a gated member's send is not routed").isZero();
        }
    }

    @Test
    void theMultiplierAndTheDbReadoutAgreeForMembersAndNonMembers() {
        VcaGroupManager vca = project.getVcaGroupManager();
        assertThat(vca.effectiveLinearMultiplier(chKick.getId()))
                .isCloseTo(linear(GROUP_A_DB), within(1e-12));
        assertThat(vca.effectiveLinearMultiplier(chSnare.getId()))
                .isCloseTo(linear(GROUP_A_DB + GROUP_B_DB), within(1e-12));
        assertThat(vca.effectiveGainDb(chSnare.getId())).isCloseTo(-6.3, within(1e-12));
        assertThat(vca.effectiveLinearMultiplier(chHat.getId())).isEqualTo(1.0);
        assertThat(vca.effectiveGainDb(chHat.getId())).isEqualTo(0.0);
        assertThat(groupB.hasMember(chKick.getId())).isFalse();
    }

    private static double peak(float[] lane) {
        double p = 0.0;
        for (float v : lane) {
            p = Math.max(p, Math.abs(v));
        }
        return p;
    }

    private static double rms(float[] lane) {
        double sum = 0.0;
        for (float v : lane) {
            sum += (double) v * v;
        }
        return Math.sqrt(sum / lane.length);
    }
}
