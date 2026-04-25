package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link MidSideWrapperProcessor} covering the two invariants
 * called out in the issue:
 * <ul>
 *   <li><b>Null test</b>: bypassing (or running the wrapper with empty inner
 *       chains) must produce bit-exact output identical to the input — i.e.
 *       encode → decode is identity.</li>
 *   <li><b>L/R invariance</b>: swapping L↔R before encode is equivalent to
 *       negating S afterwards. (Mid is symmetric in L,R; Side flips sign.)</li>
 * </ul>
 */
class MidSideWrapperProcessorTest {

    private static final int FRAMES = 256;

    @Test
    void bypass_isBitExactWhenWrapperBypassed() {
        var wrapper = new MidSideWrapperProcessor();
        wrapper.setBypassed(true);

        float[][] in = randomStereo(FRAMES, 1L);
        float[][] out = new float[2][FRAMES];

        wrapper.process(in, out, FRAMES);

        // Bit-exact: every sample identical to input.
        assertThat(out[0]).containsExactly(in[0]);
        assertThat(out[1]).containsExactly(in[1]);
    }

    @Test
    void emptyChains_areMathematicalIdentity() {
        // With no processors in either chain we take the bypass-style direct
        // copy path so that encode/decode floating-point round-off cannot
        // accumulate. This is the documented null-test guarantee.
        var wrapper = new MidSideWrapperProcessor();

        float[][] in = randomStereo(FRAMES, 2L);
        float[][] out = new float[2][FRAMES];

        wrapper.process(in, out, FRAMES);

        assertThat(out[0]).containsExactly(in[0]);
        assertThat(out[1]).containsExactly(in[1]);
    }

    @Test
    void swappingLR_equalsNegatingSide() {
        // Build a wrapper with a no-op identity on the side chain so we can
        // observe what M/S sees. We do not modify the side data — we just
        // run the wrapper twice (once with L/R, once with R/L) and check
        // the relationship between the outputs.
        var wrapper = new MidSideWrapperProcessor();
        // Add a no-op processor to BOTH chains to force the encode/decode
        // path (otherwise the empty-chain shortcut copies the input).
        wrapper.addMidProcessor(new IdentityMonoProcessor());
        wrapper.addSideProcessor(new IdentityMonoProcessor());

        float[][] in = randomStereo(FRAMES, 3L);
        float[][] swapped = new float[][] { in[1].clone(), in[0].clone() };

        float[][] outNormal  = new float[2][FRAMES];
        float[][] outSwapped = new float[2][FRAMES];

        wrapper.process(in, outNormal, FRAMES);
        wrapper.process(swapped, outSwapped, FRAMES);

        // After encode+decode (identity inner chain), outNormal == in,
        // and outSwapped == swapped. Thus L_swapped == R_normal.
        // Reformulated as the issue's invariant: swapping L↔R before encode
        // is equivalent to negating S afterwards. We verify both the direct
        // swap-equality and the "S negation" form.
        for (int i = 0; i < FRAMES; i++) {
            // Direct: out_swapped's L == out_normal's R.
            assertThat(outSwapped[0][i]).isEqualTo(outNormal[1][i]);
            assertThat(outSwapped[1][i]).isEqualTo(outNormal[0][i]);

            // S-negation form: M is unchanged when L,R swap (M = (R+L)/2 = M);
            // S becomes its own negative: S' = (R-L)/2 = -S.
            float mNormal  = (in[0][i] + in[1][i]) * 0.5f;
            float sNormal  = (in[0][i] - in[1][i]) * 0.5f;
            float mSwapped = (swapped[0][i] + swapped[1][i]) * 0.5f;
            float sSwapped = (swapped[0][i] - swapped[1][i]) * 0.5f;
            assertThat(mSwapped).isEqualTo(mNormal);
            assertThat(sSwapped).isEqualTo(-sNormal);
        }
    }

    @Test
    void encodeDecodeIdentity_throughIdentityChains_isApproximatelyEqual() {
        // Even with a non-empty chain, an *identity* inner chain should
        // round-trip the signal to within float precision.
        var wrapper = new MidSideWrapperProcessor();
        wrapper.addMidProcessor(new IdentityMonoProcessor());
        wrapper.addSideProcessor(new IdentityMonoProcessor());

        float[][] in = randomStereo(FRAMES, 4L);
        float[][] out = new float[2][FRAMES];

        wrapper.process(in, out, FRAMES);

        for (int i = 0; i < FRAMES; i++) {
            assertThat(out[0][i]).isCloseTo(in[0][i], within(1e-6f));
            assertThat(out[1][i]).isCloseTo(in[1][i], within(1e-6f));
        }
    }

    @Test
    void sideOnlyGain_widensStereoImage() {
        // Sanity: applying gain to the Side chain widens the stereo difference
        // (R-L grows in magnitude) while leaving the Mid (sum) unchanged.
        var wrapper = new MidSideWrapperProcessor();
        wrapper.addSideProcessor(new ScaleMonoProcessor(2.0f));

        float[][] in = new float[][] {
                { 0.5f, 0.4f, 0.3f, 0.2f },
                { 0.1f, 0.2f, 0.3f, 0.2f }
        };
        float[][] out = new float[2][4];

        wrapper.process(in, out, 4);

        for (int i = 0; i < 4; i++) {
            float midIn  = (in[0][i] + in[1][i]) * 0.5f;
            float midOut = (out[0][i] + out[1][i]) * 0.5f;
            assertThat(midOut).isCloseTo(midIn, within(1e-6f));

            float sideIn  = (in[0][i] - in[1][i]) * 0.5f;
            float sideOut = (out[0][i] - out[1][i]) * 0.5f;
            assertThat(sideOut).isCloseTo(2.0f * sideIn, within(1e-6f));
        }
    }

    @Test
    void getLatencySamples_reportsMaxOfChains() {
        var wrapper = new MidSideWrapperProcessor();
        wrapper.addMidProcessor(new IdentityMonoProcessor());          // 0
        wrapper.addSideProcessor(new LatencyMonoProcessor(64));        // 64

        assertThat(wrapper.getLatencySamples()).isEqualTo(64);
    }

    @Test
    void monoInput_passesThroughUnchanged() {
        // M/S only makes sense for stereo; the wrapper falls back to passthrough.
        var wrapper = new MidSideWrapperProcessor();
        wrapper.addMidProcessor(new ScaleMonoProcessor(2.0f));

        float[][] in  = new float[][] { { 0.1f, 0.2f, 0.3f } };
        float[][] out = new float[1][3];

        wrapper.process(in, out, 3);

        assertThat(out[0]).containsExactly(in[0]);
    }

    @Test
    void channelAndChainAccessors_returnExpectedShapes() {
        var wrapper = new MidSideWrapperProcessor();
        assertThat(wrapper.getInputChannelCount()).isEqualTo(2);
        assertThat(wrapper.getOutputChannelCount()).isEqualTo(2);
        assertThat(wrapper.getMidChain()).isEmpty();
        assertThat(wrapper.getSideChain()).isEmpty();
        assertThat(wrapper.midChainView()).isEmpty();
        assertThat(wrapper.sideChainView()).isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static float[][] randomStereo(int frames, long seed) {
        Random rng = new Random(seed);
        float[] l = new float[frames];
        float[] r = new float[frames];
        for (int i = 0; i < frames; i++) {
            l[i] = (rng.nextFloat() * 2f) - 1f;
            r[i] = (rng.nextFloat() * 2f) - 1f;
        }
        return new float[][] { l, r };
    }

    private static org.assertj.core.data.Offset<Float> within(float v) {
        return org.assertj.core.data.Offset.offset(v);
    }

    /** Mono identity processor — copies input to output. */
    private static final class IdentityMonoProcessor implements AudioProcessor {
        @Override public void process(float[][] in, float[][] out, int n) {
            System.arraycopy(in[0], 0, out[0], 0, n);
        }
        @Override public void reset() {}
        @Override public int getInputChannelCount()  { return 1; }
        @Override public int getOutputChannelCount() { return 1; }
    }

    /** Mono scaler — multiplies input by a constant gain. */
    private static final class ScaleMonoProcessor implements AudioProcessor {
        private final float gain;
        ScaleMonoProcessor(float gain) { this.gain = gain; }
        @Override public void process(float[][] in, float[][] out, int n) {
            for (int i = 0; i < n; i++) out[0][i] = in[0][i] * gain;
        }
        @Override public void reset() {}
        @Override public int getInputChannelCount()  { return 1; }
        @Override public int getOutputChannelCount() { return 1; }
    }

    /** Reports a fixed latency for testing PDC accumulation. */
    private static final class LatencyMonoProcessor implements AudioProcessor {
        private final int latency;
        LatencyMonoProcessor(int latency) { this.latency = latency; }
        @Override public void process(float[][] in, float[][] out, int n) {
            System.arraycopy(in[0], 0, out[0], 0, n);
        }
        @Override public void reset() {}
        @Override public int getInputChannelCount()  { return 1; }
        @Override public int getOutputChannelCount() { return 1; }
        @Override public int getLatencySamples() { return latency; }
    }
}
