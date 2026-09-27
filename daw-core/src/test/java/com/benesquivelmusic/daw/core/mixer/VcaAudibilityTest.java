package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.performance.TrackCpuBudgetEnforcer;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 322 — the VCA fader is audible: {@code Mixer} folds
 * {@link VcaGroupManager#effectiveLinearMultiplier} into every member
 * channel's render gain once per channel per block, so member output and
 * post-fader sends ride the VCA while pre-fader sends do not, in
 * {@code mixDown}, {@code mixDownInstrumented} and {@code renderDirectOutputs}
 * (Audio Engine Wiring Design Book §5.6 "VCA fader"; subsumes the engine
 * half of story 153).
 *
 * <p>Every fader in the rig sits at a non-default value so a path that
 * re-read {@code channel.getVolume()} instead of the composite gain fails.</p>
 */
class VcaAudibilityTest {

    private static final double MEMBER_FADER = 0.8;
    private static final double OTHER_FADER = 0.6;
    private static final double POST_SEND = 0.5;
    private static final double PRE_SEND = 0.4;
    private static final double MINUS_6_DB = Math.pow(10.0, -6.0 / 20.0);
    private static final double MINUS_12_DB = Math.pow(10.0, -12.0 / 20.0);

    private record Rig(Mixer mixer, VcaGroupManager vcas, MixerChannel member,
                       MixerChannel other, VcaGroup group) { }

    /** Mono buffers and a mono output: no channel pan law, so gains read directly. */
    private static Rig rig() {
        Mixer mixer = new Mixer();
        VcaGroupManager vcas = new VcaGroupManager();
        mixer.setVcaGroupManager(vcas);
        MixerChannel preBus = mixer.addReturnBus("Pre Return");
        MixerChannel member = new MixerChannel("Member");
        member.setVolume(MEMBER_FADER);
        member.addSend(new Send(mixer.getAuxBus(), POST_SEND, SendTap.POST_FADER));
        member.addSend(new Send(preBus, PRE_SEND, SendTap.PRE_FADER));
        MixerChannel other = new MixerChannel("Other");
        other.setVolume(OTHER_FADER);
        mixer.addChannel(member);
        mixer.addChannel(other);
        VcaGroup group = vcas.createVcaGroup("Drums", List.of(member.getId()));
        return new Rig(mixer, vcas, member, other, group);
    }

    private static float[][][] unityInputs() {
        return new float[][][] {{{1.0f}}, {{1.0f}}};
    }

    /**
     * The master sum for a unity input block: member × VCA, the non-member,
     * plus both return buses (unity fader, centre pan) — the post-fader send
     * rides the VCA, the pre-fader send does not.
     */
    private static float masterSum(double vcaMultiplier) {
        return (float) (MEMBER_FADER * vcaMultiplier + OTHER_FADER
                + POST_SEND * MEMBER_FADER * vcaMultiplier + PRE_SEND);
    }

    @Test
    void minusSixDbGroupScalesMemberOutputAndPostFaderSendButNotPreFaderSend() {
        Rig rig = rig();
        rig.vcas().setMasterGainDb(rig.group().id(), -6.0);
        float[][] output = {{0.0f}};
        float[][][] returns = {{{0.0f}}, {{0.0f}}};

        rig.mixer().mixDown(unityInputs(), output, returns, 1);

        assertThat(output[0][0])
                .as("member rides the VCA, the non-member does not")
                .isCloseTo(masterSum(MINUS_6_DB), within(1e-6f));
        assertThat(returns[0][0][0])
                .as("post-fader send = level × fader × VCA")
                .isCloseTo((float) (POST_SEND * MEMBER_FADER * MINUS_6_DB), within(1e-6f));
        assertThat(returns[1][0][0])
                .as("pre-fader send ignores fader and VCA")
                .isCloseTo((float) PRE_SEND, within(1e-6f));
    }

    @Test
    void unityGroupIsTransparent() {
        Rig rig = rig();
        float[][] output = {{0.0f}};
        float[][][] returns = {{{0.0f}}, {{0.0f}}};

        rig.mixer().mixDown(unityInputs(), output, returns, 1);

        assertThat(output[0][0]).isCloseTo(masterSum(1.0), within(1e-6f));
        assertThat(returns[0][0][0]).isCloseTo((float) (POST_SEND * MEMBER_FADER), within(1e-6f));
    }

    @Test
    void groupAtTheFloorSilencesMembersButNotTheirPreFaderSends() {
        Rig rig = rig();
        rig.vcas().setMasterGainDb(rig.group().id(), VcaGroup.MIN_GAIN_DB);
        float[][] output = {{0.0f}};
        float[][][] returns = {{{0.0f}}, {{0.0f}}};

        rig.mixer().mixDown(unityInputs(), output, returns, 1);

        assertThat(output[0][0]).isCloseTo(masterSum(0.0), within(1e-6f));
        assertThat(returns[0][0][0]).isEqualTo(0.0f);
        assertThat(returns[1][0][0]).isCloseTo((float) PRE_SEND, within(1e-6f));
    }

    @Test
    void membershipInTwoGroupsComposesInDecibels() {
        Rig rig = rig();
        rig.vcas().setMasterGainDb(rig.group().id(), -6.0);
        VcaGroup second = rig.vcas().createVcaGroup("All", List.of(rig.member().getId()));
        rig.vcas().setMasterGainDb(second.id(), -6.0);
        float[][] output = {{0.0f}};

        rig.mixer().mixDown(unityInputs(), output, new float[2][1][1], 1);

        assertThat(output[0][0]).isCloseTo(masterSum(MINUS_12_DB), within(1e-6f));
    }

    @Test
    void instrumentedRenderAppliesTheSameCompositeGain() {
        Rig rig = rig();
        rig.vcas().setMasterGainDb(rig.group().id(), -6.0);
        float[][] output = {{0.0f}};
        float[][][] returns = {{{0.0f}}, {{0.0f}}};
        TrackCpuBudgetEnforcer enforcer = new TrackCpuBudgetEnforcer(44_100.0, 64);

        rig.mixer().mixDownInstrumented(unityInputs(), output, returns, 1, List.<Track>of(), enforcer);

        assertThat(output[0][0]).isCloseTo(masterSum(MINUS_6_DB), within(1e-6f));
        assertThat(returns[0][0][0])
                .isCloseTo((float) (POST_SEND * MEMBER_FADER * MINUS_6_DB), within(1e-6f));
        assertThat(returns[1][0][0]).isCloseTo((float) PRE_SEND, within(1e-6f));
    }

    @Test
    void directRoutedMemberRidesTheVcaToo() {
        Rig rig = rig();
        rig.vcas().setMasterGainDb(rig.group().id(), -6.0);
        rig.member().setOutputRouting(new OutputRouting(0, 1));
        float[][][] inputs = unityInputs();
        float[][] output = {{0.0f}};
        float[][] hardware = {{0.0f}};

        rig.mixer().mixDown(inputs, output, new float[2][1][1], 1);
        rig.mixer().renderDirectOutputs(inputs, hardware, 1);

        // The member's own audio leaves the master sum; its sends still feed
        // the returns, which are summed into the master as before.
        assertThat(output[0][0])
                .as("direct-routed member leaves the master sum")
                .isCloseTo((float) (OTHER_FADER + POST_SEND * MEMBER_FADER * MINUS_6_DB + PRE_SEND),
                        within(1e-6f));
        assertThat(hardware[0][0]).isCloseTo((float) (MEMBER_FADER * MINUS_6_DB), within(1e-6f));
    }

    @Test
    void mixerWithoutAManagerRendersAtTheFaderAlone() {
        Mixer mixer = new Mixer();
        MixerChannel ch = new MixerChannel("Ch");
        ch.setVolume(MEMBER_FADER);
        mixer.addChannel(ch);
        float[][] output = {{0.0f}};

        mixer.mixDown(new float[][][] {{{1.0f}}}, output, new float[1][1][1], 1);

        assertThat(output[0][0]).isCloseTo((float) MEMBER_FADER, within(1e-6f));
    }

    @Test
    void dawProjectWiresItsVcaManagerIntoItsMixer() {
        DawProject project = new DawProject("vca", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Kick");
        MixerChannel channel = project.getMixerChannelForTrack(track);
        channel.setVolume(MEMBER_FADER);
        VcaGroup group = project.getVcaGroupManager().createVcaGroup("Drums", List.of(channel.getId()));
        project.getVcaGroupManager().setMasterGainDb(group.id(), -6.0);
        float[][] output = {{0.0f}};

        project.getMixer().mixDown(new float[][][] {{{1.0f}}}, output, new float[1][1][1], 1);

        assertThat(output[0][0])
                .as("the project's own manager is consulted by its mixer")
                .isCloseTo((float) (MEMBER_FADER * MINUS_6_DB), within(1e-6f));
    }
}
