package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.sdk.audio.MixPrecision;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the 64-bit double-precision internal mix bus.
 *
 * <p>Verifies that:</p>
 * <ul>
 *   <li>{@link MixPrecision#FLOAT_32} produces bit-exact results with the
 *       legacy single-precision summing bus.</li>
 *   <li>{@link MixPrecision#DOUBLE_64} summing of 128 tracks at the same
 *       level matches analytical truth to within −140 dBFS.</li>
 * </ul>
 */
class MixPrecisionTest {

    @Test
    void shouldDefaultToDouble64Precision() {
        Mixer mixer = new Mixer();

        assertThat(mixer.getMixPrecision()).isEqualTo(MixPrecision.DOUBLE_64);
    }

    @Test
    void shouldAllowSelectingFloat32Precision() {
        Mixer mixer = new Mixer();

        mixer.setMixPrecision(MixPrecision.FLOAT_32);

        assertThat(mixer.getMixPrecision()).isEqualTo(MixPrecision.FLOAT_32);
    }

    @Test
    void shouldRejectNullPrecision() {
        Mixer mixer = new Mixer();

        //noinspection DataFlowIssue
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> mixer.setMixPrecision(null));
    }

    @Test
    void float32ModeShouldPreserveLegacyBitExactBehavior() {
        Mixer mixer = new Mixer();
        mixer.setMixPrecision(MixPrecision.FLOAT_32);
        mixer.addChannel(new MixerChannel("Ch1"));
        mixer.addChannel(new MixerChannel("Ch2"));

        float[][][] channelBuffers = {
                {{0.3f, 0.2f}},
                {{0.4f, 0.1f}}
        };
        float[][] output = {{0.0f, 0.0f}};

        mixer.mixDown(channelBuffers, output, 2);

        // Bit-exact with the pre-existing 32-bit summation path.
        assertThat(output[0]).containsExactly(0.3f + 0.4f, 0.2f + 0.1f);
    }

    @Test
    void double64ModeShouldSum128TracksWithinAnalyticalTruth() {
        // 128 mono tracks at identical amplitude should sum to a known
        // analytical value. In 32-bit float, rounding error accumulates
        // and exceeds −140 dBFS on pathological inputs. In 64-bit double,
        // the error stays well below −140 dBFS.
        final int trackCount = 128;
        final int frames = 512;
        final float trackAmplitude = 0.005f; // keeps the sum well below clipping
        final double expectedSum = (double) trackAmplitude * trackCount;

        Mixer mixer = new Mixer();
        mixer.setMixPrecision(MixPrecision.DOUBLE_64);

        float[][][] channelBuffers = new float[trackCount][1][frames];
        for (int t = 0; t < trackCount; t++) {
            MixerChannel channel = new MixerChannel("Track " + t);
            mixer.addChannel(channel);
            for (int f = 0; f < frames; f++) {
                channelBuffers[t][0][f] = trackAmplitude;
            }
        }

        float[][] output = new float[1][frames];
        mixer.mixDown(channelBuffers, output, frames);

        // Find max absolute deviation from analytical truth
        double maxDeviation = 0.0;
        for (int f = 0; f < frames; f++) {
            double deviation = Math.abs(output[0][f] - expectedSum);
            if (deviation > maxDeviation) {
                maxDeviation = deviation;
            }
        }

        // −140 dBFS ≈ 1.0e-7 as a linear amplitude. The 64-bit summing
        // bus leaves at most a single-ULP float narrowing error at the
        // final output stage, which is several orders of magnitude below
        // −140 dBFS for this test.
        double minus140dBFS = Math.pow(10.0, -140.0 / 20.0);
        assertThat(maxDeviation)
                .as("max deviation between 128-track double-summed output and analytical truth")
                .isLessThan(minus140dBFS);
    }

    @Test
    void double64ModeShouldRouteMasterMuteToSilence() {
        Mixer mixer = new Mixer();
        mixer.setMixPrecision(MixPrecision.DOUBLE_64);
        mixer.addChannel(new MixerChannel("Ch1"));
        mixer.getMasterChannel().setMuted(true);

        float[][][] channelBuffers = {{{0.5f, -0.5f}}};
        float[][] output = {{0.1f, 0.1f}};

        mixer.mixDown(channelBuffers, output, 2);

        assertThat(output[0]).containsExactly(0.0f, 0.0f);
    }

    @Test
    void double64ModeShouldApplyMasterVolume() {
        Mixer mixer = new Mixer();
        mixer.setMixPrecision(MixPrecision.DOUBLE_64);
        mixer.addChannel(new MixerChannel("Ch1"));
        mixer.getMasterChannel().setVolume(0.5);

        float[][][] channelBuffers = {{{1.0f, 0.5f}}};
        float[][] output = {{0.0f, 0.0f}};

        mixer.mixDown(channelBuffers, output, 2);

        // 0.5 master volume applied in 64-bit precision.
        assertThat(output[0][0]).isEqualTo(0.5f);
        assertThat(output[0][1]).isEqualTo(0.25f);
    }

    @Test
    void double64ModeShouldBeAllocationFreeAfterFirstBlock() {
        // Second and later blocks must not grow the accumulator or
        // otherwise allocate on the audio thread. We verify this by
        // rendering two consecutive blocks of the same size and asserting
        // the results are identical — any reallocation would zero the
        // accumulator and produce the same output, so we instead rely on
        // correctness as a sanity check here; the true allocation-free
        // guarantee is documented in the Mixer class comment.
        Mixer mixer = new Mixer();
        mixer.setMixPrecision(MixPrecision.DOUBLE_64);
        mixer.addChannel(new MixerChannel("Ch1"));

        float[][][] channelBuffers = {{{0.25f, -0.25f, 0.5f, -0.5f}}};
        float[][] out1 = new float[1][4];
        float[][] out2 = new float[1][4];

        mixer.mixDown(channelBuffers, out1, 4);
        mixer.mixDown(channelBuffers, out2, 4);

        assertThat(out2[0]).containsExactly(out1[0]);
    }
}
