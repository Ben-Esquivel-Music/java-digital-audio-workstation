package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.sdk.audio.MixPrecision;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 322 contract (probe-derived, kept as a permanent guard for the §5.6 bus pan law at arbitrary pans) — the bus pan law numerically at −0.62 / +0.81 / 0 for a
 * RETURN bus and for the MASTER, in both mix precisions. Expected lane gains
 * are computed here from the documented unity-at-centre taper
 * ({@code L = min(1, √2·cos θ)}, {@code R = min(1, √2·sin θ)},
 * {@code θ = (pan+1)·π/4}) and a 30 %-duty 0.9 pulse, never from
 * {@link PanLaw}. The return path is isolated with a channel at fader 0 and a
 * PRE_FADER send (fader-independent) at 0.37 into a return at 0.81.
 */
class Story322BusPanLawContractTest {

    private static final int FRAMES = 32;
    private static final double[] PANS = {-0.62, 0.81, 0.0};
    private static final double SEND = 0.37;
    private static final double RETURN_GAIN = 0.81;
    private static final double MASTER_GAIN = 0.37;
    private static final double TOL = 1e-6;

    private static float pulse(int frame) {
        return frame % 10 < 3 ? 0.9f : 0.0f;
    }

    private static double expectedLeft(double pan) {
        return Math.min(1.0, Math.sqrt(2.0) * Math.cos((pan + 1.0) * Math.PI / 4.0));
    }

    private static double expectedRight(double pan) {
        return Math.min(1.0, Math.sqrt(2.0) * Math.sin((pan + 1.0) * Math.PI / 4.0));
    }

    private static float[][][] stereoPulse() {
        float[][][] chans = new float[1][2][FRAMES];
        for (int f = 0; f < FRAMES; f++) {
            chans[0][0][f] = chans[0][1][f] = pulse(f);
        }
        return chans;
    }

    @ParameterizedTest
    @EnumSource(MixPrecision.class)
    void returnBusPanFollowsTheUnityAtCentreLawOnBothLanes(MixPrecision precision) {
        for (double pan : PANS) {
            Mixer mixer = new Mixer();
            mixer.setMixPrecision(precision);
            MixerChannel src = new MixerChannel("Src");
            src.setVolume(0.0); // the direct path contributes nothing: only the return is heard
            src.addSend(new Send(mixer.getAuxBus(), SEND, SendTap.PRE_FADER));
            mixer.addChannel(src);
            MixerChannel returnBus = mixer.getAuxBus();
            returnBus.setVolume(RETURN_GAIN);
            returnBus.setPan(pan);

            float[][] out = new float[2][FRAMES];
            float[][][] returns = new float[1][2][FRAMES];
            mixer.mixDown(stereoPulse(), out, returns, FRAMES);

            for (int f = 0; f < FRAMES; f++) {
                double base = pulse(f) * SEND * RETURN_GAIN;
                assertThat((double) out[0][f]).as("%s pan %s L frame %d", precision, pan, f)
                        .isCloseTo(base * expectedLeft(pan), within(TOL));
                assertThat((double) out[1][f]).as("%s pan %s R frame %d", precision, pan, f)
                        .isCloseTo(base * expectedRight(pan), within(TOL));
                // The return buffer carries the post-fader, post-pan value back (RETURN_POST tap).
                assertThat((double) returns[0][0][f]).isCloseTo(base * expectedLeft(pan), within(TOL));
                assertThat((double) returns[0][1][f]).isCloseTo(base * expectedRight(pan), within(TOL));
                if (pan == 0.0) {
                    assertThat(out[0][f]).as("centre is exactly unity on both lanes").isEqualTo(out[1][f]);
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(MixPrecision.class)
    void masterPanFollowsTheUnityAtCentreLawAfterTheChannelLaw(MixPrecision precision) {
        for (double pan : PANS) {
            Mixer mixer = new Mixer();
            mixer.setMixPrecision(precision);
            mixer.addChannel(new MixerChannel("Src")); // fader 1.0, pan centre → cos(π/4) per lane
            mixer.getMasterChannel().setVolume(MASTER_GAIN);
            mixer.getMasterChannel().setPan(pan);

            float[][] out = new float[2][FRAMES];
            mixer.mixDown(stereoPulse(), out, new float[1][2][FRAMES], FRAMES);

            for (int f = 0; f < FRAMES; f++) {
                double base = pulse(f) * Math.cos(Math.PI / 4.0) * MASTER_GAIN;
                assertThat((double) out[0][f]).as("%s pan %s L frame %d", precision, pan, f)
                        .isCloseTo(base * expectedLeft(pan), within(TOL));
                assertThat((double) out[1][f]).as("%s pan %s R frame %d", precision, pan, f)
                        .isCloseTo(base * expectedRight(pan), within(TOL));
                if (pan == 0.0) {
                    assertThat(out[0][f]).isEqualTo(out[1][f]);
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(MixPrecision.class)
    void aMonoMasterIsNeverPanned(MixPrecision precision) {
        Mixer mixer = new Mixer();
        mixer.setMixPrecision(precision);
        mixer.addChannel(new MixerChannel("Src"));
        mixer.getMasterChannel().setVolume(MASTER_GAIN);
        mixer.getMasterChannel().setPan(0.81);

        float[][] out = new float[1][FRAMES];
        mixer.mixDown(stereoPulse(), out, new float[1][1][FRAMES], FRAMES);
        for (int f = 0; f < FRAMES; f++) {
            assertThat((double) out[0][f]).isCloseTo(pulse(f) * MASTER_GAIN, within(TOL));
        }
    }

    @ParameterizedTest
    @EnumSource(MixPrecision.class)
    void theLawItselfIsUnityAtCentreHardPansAreOneZeroAndNoLaneExceedsOne(MixPrecision precision) {
        assertThat(PanLaw.busLeftGain(0.0)).isEqualTo(1.0);
        assertThat(PanLaw.busRightGain(0.0)).isEqualTo(1.0);
        assertThat(PanLaw.busLeftGain(-1.0)).isCloseTo(1.0, within(1e-12));
        assertThat(PanLaw.busRightGain(-1.0)).isCloseTo(0.0, within(1e-12));
        assertThat(PanLaw.busLeftGain(1.0)).isCloseTo(0.0, within(1e-12));
        assertThat(PanLaw.busRightGain(1.0)).isCloseTo(1.0, within(1e-12));
        assertThat(PanLaw.busLeftGain(-0.62)).isEqualTo(1.0);
        assertThat(PanLaw.busRightGain(-0.62)).isCloseTo(expectedRight(-0.62), within(1e-12))
                .isLessThan(1.0);
        assertThat(PanLaw.busRightGain(0.81)).isEqualTo(1.0);
        assertThat(PanLaw.busLeftGain(0.81)).isCloseTo(expectedLeft(0.81), within(1e-12))
                .isLessThan(1.0);
        assertThat(precision).isNotNull();
    }
}
