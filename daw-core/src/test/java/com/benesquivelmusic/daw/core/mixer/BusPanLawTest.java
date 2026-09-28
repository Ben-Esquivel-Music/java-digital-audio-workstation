package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.audio.performance.TrackCpuBudgetEnforcer;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.audio.MixPrecision;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 322 — master and return pan are live in the summing stages with the
 * unity-at-centre constant-power bus law ({@link PanLaw}), in both mix
 * precisions (Audio Engine Wiring Design Book §5.6 "Master / return pan").
 *
 * <p>Every expectation uses a non-default fader gain so a path that ignored
 * the pan (the pre-322 behaviour) or applied the −3 dB channel law instead
 * fails the assertion rather than passing by coincidence.</p>
 */
class BusPanLawTest {

    private static final double RETURN_GAIN = 0.7;
    private static final double MASTER_GAIN = 0.9;
    private static final double CHANNEL_CENTRE = Math.cos(Math.PI / 4.0);
    /** {@code min(1, √2·cos(3π/8))} — the attenuated lane at pan +0.5. */
    private static final double TAPER_AT_HALF = Math.sqrt(2.0) * Math.cos(1.5 * Math.PI / 4.0);

    /**
     * The two engine render entries that each carry their OWN copy of the
     * return-bus pan block — {@code Mixer.mixDown} and
     * {@code Mixer.mixDownInstrumented} are duplicate bodies "kept in step"
     * by hand, so every return case runs through both (fix round 1, N4).
     * The master stage is shared ({@code finishMasterMix}) and needs no
     * fan-out. The instrumented call shape (empty track list, a fresh
     * {@link TrackCpuBudgetEnforcer}) is the one
     * {@code VcaAudibilityTest.instrumentedRenderAppliesTheSameCompositeGain}
     * uses.
     */
    private enum ReturnRenderer {
        MIX_DOWN {
            @Override
            void render(Mixer mixer, float[][][] channelBuffers, float[][] output,
                        float[][][] returnBuffers) {
                mixer.mixDown(channelBuffers, output, returnBuffers, 1);
            }
        },
        MIX_DOWN_INSTRUMENTED {
            @Override
            void render(Mixer mixer, float[][][] channelBuffers, float[][] output,
                        float[][][] returnBuffers) {
                mixer.mixDownInstrumented(channelBuffers, output, returnBuffers, 1,
                        List.<Track>of(), new TrackCpuBudgetEnforcer(44_100.0, 64));
            }
        };

        abstract void render(Mixer mixer, float[][][] channelBuffers, float[][] output,
                             float[][][] returnBuffers);
    }

    /** Every {@link MixPrecision} × every {@link ReturnRenderer}. */
    static Stream<Arguments> returnRenderers() {
        return Arrays.stream(MixPrecision.values()).flatMap(precision ->
                Arrays.stream(ReturnRenderer.values())
                        .map(renderer -> Arguments.of(precision, renderer)));
    }

    // ── PanLaw itself ────────────────────────────────────────────────────

    @Test
    void busLawIsUnityAtCentreAndHardPansSilenceTheFarLane() {
        assertThat(PanLaw.busLeftGain(0.0)).isEqualTo(1.0);
        assertThat(PanLaw.busRightGain(0.0)).isEqualTo(1.0);
        assertThat(PanLaw.busLeftGain(-1.0)).isCloseTo(1.0, within(1e-12));
        assertThat(PanLaw.busRightGain(-1.0)).isCloseTo(0.0, within(1e-12));
        assertThat(PanLaw.busLeftGain(1.0)).isCloseTo(0.0, within(1e-12));
        assertThat(PanLaw.busRightGain(1.0)).isCloseTo(1.0, within(1e-12));
    }

    @Test
    void busLawNeverExceedsUnityAndFollowsTheConstantPowerTaperOnTheAttenuatedLane() {
        for (double pan = -1.0; pan <= 1.0; pan += 0.05) {
            assertThat(PanLaw.busLeftGain(pan)).isBetween(0.0, 1.0);
            assertThat(PanLaw.busRightGain(pan)).isBetween(0.0, 1.0);
        }
        assertThat(PanLaw.busLeftGain(0.5)).isCloseTo(TAPER_AT_HALF, within(1e-12));
        assertThat(PanLaw.busRightGain(0.5)).isEqualTo(1.0);
        assertThat(PanLaw.busRightGain(-0.5)).isCloseTo(TAPER_AT_HALF, within(1e-12));
        assertThat(PanLaw.busLeftGain(-0.5)).isEqualTo(1.0);
    }

    // ── Return bus ───────────────────────────────────────────────────────

    /**
     * A channel at volume 0 with a unity pre-fader send: the main output is
     * the return bus alone, so the return's fader × pan law is observable
     * directly in {@code output} and in the written-back return buffer.
     */
    private static Mixer returnFixture(MixPrecision precision, double returnPan) {
        Mixer mixer = new Mixer();
        mixer.setMixPrecision(precision);
        MixerChannel ch = new MixerChannel("Ch1");
        ch.setVolume(0.0);
        ch.addSend(new Send(mixer.getAuxBus(), 1.0, SendTap.PRE_FADER));
        mixer.addChannel(ch);
        mixer.getAuxBus().setVolume(RETURN_GAIN);
        mixer.getAuxBus().setPan(returnPan);
        return mixer;
    }

    private static float[][] renderReturn(ReturnRenderer renderer, Mixer mixer,
                                          float[][][] returnBuffers) {
        float[][][] channelBuffers = {{{1.0f}, {1.0f}}};
        float[][] output = {{0.0f}, {0.0f}};
        renderer.render(mixer, channelBuffers, output, returnBuffers);
        return output;
    }

    @ParameterizedTest
    @MethodSource("returnRenderers")
    void returnAtCentrePassesItsFaderGainOnBothLanes(MixPrecision precision, ReturnRenderer renderer) {
        float[][][] returnBuffers = {{{0.0f}, {0.0f}}};
        float[][] output = renderReturn(renderer, returnFixture(precision, 0.0), returnBuffers);

        assertThat(output[0][0]).isCloseTo((float) RETURN_GAIN, within(1e-6f));
        assertThat(output[1][0]).isCloseTo((float) RETURN_GAIN, within(1e-6f));
        assertThat(returnBuffers[0][0][0]).isCloseTo((float) RETURN_GAIN, within(1e-6f));
        assertThat(returnBuffers[0][1][0]).isCloseTo((float) RETURN_GAIN, within(1e-6f));
    }

    /**
     * Left at fader gain AND right silent: a copy of the pan block with its
     * lanes swapped fails both assertions, on whichever renderer carries it.
     */
    @ParameterizedTest
    @MethodSource("returnRenderers")
    void returnHardLeftSilencesTheRightLaneAndKeepsTheLeftAtFaderGain(MixPrecision precision,
                                                                       ReturnRenderer renderer) {
        float[][][] returnBuffers = {{{0.0f}, {0.0f}}};
        float[][] output = renderReturn(renderer, returnFixture(precision, -1.0), returnBuffers);

        assertThat(output[0][0]).isCloseTo((float) RETURN_GAIN, within(1e-6f));
        assertThat(output[1][0]).isCloseTo(0.0f, within(1e-6f));
        assertThat(returnBuffers[0][1][0]).isCloseTo(0.0f, within(1e-6f));
    }

    @ParameterizedTest
    @MethodSource("returnRenderers")
    void returnPannedHalfRightAttenuatesTheLeftLaneByTheTaper(MixPrecision precision,
                                                              ReturnRenderer renderer) {
        float[][][] returnBuffers = {{{0.0f}, {0.0f}}};
        float[][] output = renderReturn(renderer, returnFixture(precision, 0.5), returnBuffers);

        assertThat(output[0][0]).isCloseTo((float) (RETURN_GAIN * TAPER_AT_HALF), within(1e-6f));
        assertThat(output[1][0]).isCloseTo((float) RETURN_GAIN, within(1e-6f));
    }

    @ParameterizedTest
    @MethodSource("returnRenderers")
    void monoReturnIsNeverPanned(MixPrecision precision, ReturnRenderer renderer) {
        Mixer mixer = returnFixture(precision, 1.0);
        float[][][] channelBuffers = {{{1.0f}}};
        float[][] output = {{0.0f}};
        float[][][] returnBuffers = {{{0.0f}}};

        renderer.render(mixer, channelBuffers, output, returnBuffers);

        assertThat(output[0][0]).isCloseTo((float) RETURN_GAIN, within(1e-6f));
    }

    // ── Master ───────────────────────────────────────────────────────────

    /**
     * A unity channel at centre contributes {@code cos(π/4)} per lane (the
     * unchanged channel law); the master fader × master pan law scales that.
     */
    private static float[][] renderMaster(MixPrecision precision, double masterPan) {
        Mixer mixer = new Mixer();
        mixer.setMixPrecision(precision);
        MixerChannel ch = new MixerChannel("Ch1");
        mixer.addChannel(ch);
        mixer.getMasterChannel().setVolume(MASTER_GAIN);
        mixer.getMasterChannel().setPan(masterPan);
        float[][][] channelBuffers = {{{1.0f}, {1.0f}}};
        float[][] output = {{0.0f}, {0.0f}};
        mixer.mixDown(channelBuffers, output, new float[1][2][1], 1);
        return output;
    }

    @ParameterizedTest
    @EnumSource(MixPrecision.class)
    void masterAtCentreAppliesItsFaderGainEquallyAndLeavesTheChannelLawAlone(MixPrecision precision) {
        float[][] output = renderMaster(precision, 0.0);
        float expected = (float) (CHANNEL_CENTRE * MASTER_GAIN);

        assertThat(output[0][0]).isCloseTo(expected, within(1e-6f));
        assertThat(output[1][0]).isCloseTo(expected, within(1e-6f));
    }

    @ParameterizedTest
    @EnumSource(MixPrecision.class)
    void masterHardRightSilencesTheLeftLane(MixPrecision precision) {
        float[][] output = renderMaster(precision, 1.0);

        assertThat(output[0][0]).isCloseTo(0.0f, within(1e-6f));
        assertThat(output[1][0]).isCloseTo((float) (CHANNEL_CENTRE * MASTER_GAIN), within(1e-6f));
    }

    @ParameterizedTest
    @EnumSource(MixPrecision.class)
    void masterPannedHalfRightAttenuatesTheLeftLaneByTheTaper(MixPrecision precision) {
        float[][] output = renderMaster(precision, 0.5);

        assertThat(output[0][0])
                .isCloseTo((float) (CHANNEL_CENTRE * MASTER_GAIN * TAPER_AT_HALF), within(1e-6f));
        assertThat(output[1][0]).isCloseTo((float) (CHANNEL_CENTRE * MASTER_GAIN), within(1e-6f));
    }

    @ParameterizedTest
    @EnumSource(MixPrecision.class)
    void masterMuteStillSilencesEveryLaneRegardlessOfPan(MixPrecision precision) {
        Mixer mixer = new Mixer();
        mixer.setMixPrecision(precision);
        mixer.addChannel(new MixerChannel("Ch1"));
        mixer.getMasterChannel().setPan(-0.5);
        mixer.getMasterChannel().setMuted(true);
        float[][] output = {{0.0f}, {0.0f}};

        mixer.mixDown(new float[][][] {{{1.0f}, {1.0f}}}, output, new float[1][2][1], 1);

        assertThat(output[0][0]).isEqualTo(0.0f);
        assertThat(output[1][0]).isEqualTo(0.0f);
    }
}
