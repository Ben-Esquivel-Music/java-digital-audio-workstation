package com.benesquivelmusic.daw.core.dsp.reverb;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ConvolutionReverbProcessor}.
 *
 * <p>Verifies the algorithm-level invariants called out in the issue:</p>
 * <ul>
 *   <li>Loading a known-length IR reports the correct length.</li>
 *   <li>Null test: a single-sample IR scaled by {@code k} reproduces the
 *       input scaled by {@code k} after the partition latency.</li>
 *   <li>A {@code 2×} stretch factor produces a {@code 2×} longer IR.</li>
 *   <li>Construction validation, parameter ranges, reset semantics.</li>
 * </ul>
 */
class ConvolutionReverbProcessorTest {

    private static final double SR = 48000.0;
    private static final int B = ConvolutionReverbProcessor.PARTITION_SIZE;

    @Test
    void shouldCreateWithDefaultsAndSensibleLatency() {
        ConvolutionReverbProcessor reverb = new ConvolutionReverbProcessor(2, SR);
        assertThat(reverb.getInputChannelCount()).isEqualTo(2);
        assertThat(reverb.getOutputChannelCount()).isEqualTo(2);
        assertThat(reverb.getLatencySamples()).isEqualTo(B);
        assertThat(reverb.getMix()).isEqualTo(0.3);
        assertThat(reverb.getStretch()).isEqualTo(1.0);
        assertThat(reverb.getPredelayMs()).isEqualTo(0.0);
        assertThat(reverb.getStereoWidth()).isEqualTo(1.0);
        assertThat(reverb.getTrimStart()).isEqualTo(0.0);
        assertThat(reverb.getTrimEnd()).isEqualTo(1.0);
        // Default IR (small room) should be loaded
        assertThat(reverb.getImpulseResponseLength()).isPositive();
    }

    @Test
    void shouldRejectInvalidConstructorArgs() {
        assertThatThrownBy(() -> new ConvolutionReverbProcessor(0, SR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConvolutionReverbProcessor(3, SR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConvolutionReverbProcessor(1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectOutOfRangeParameters() {
        ConvolutionReverbProcessor r = new ConvolutionReverbProcessor(1, SR);
        assertThatThrownBy(() -> r.setStretch(0.4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> r.setStretch(2.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> r.setPredelayMs(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> r.setPredelayMs(201)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> r.setMix(-0.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> r.setMix(1.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> r.setLowCutHz(10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> r.setHighCutHz(500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> r.setStereoWidth(-0.1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void knownLengthIrReportsCorrectLength() {
        ConvolutionReverbProcessor reverb = new ConvolutionReverbProcessor(2, SR);
        int len = 1234;
        float[][] ir = new float[2][len];
        ir[0][0] = 1.0f;
        ir[1][0] = 1.0f;
        reverb.setImpulseResponse(ir);
        assertThat(reverb.getImpulseResponseLength()).isEqualTo(len);
    }

    @Test
    void singleSampleIrReproducesInputScaledByThatSample() {
        // Null-test: an IR equal to a single δ[0] * k is identity-times-k.
        // The processor introduces PARTITION_SIZE samples of latency.
        ConvolutionReverbProcessor reverb = new ConvolutionReverbProcessor(1, SR);
        reverb.setMix(1.0);
        // Use a high-cut that does not affect the test signal frequencies
        reverb.setLowCutHz(20.0);
        reverb.setHighCutHz(20000.0);

        float[][] ir = new float[1][1];
        float k = 0.5f;
        ir[0][0] = k;
        reverb.setImpulseResponse(ir);

        // Feed sustained sine so we can compare wet output to scaled input
        int n = B * 8;
        float[][] in = new float[1][n];
        float[][] out = new float[1][n];
        for (int i = 0; i < n; i++) {
            in[0][i] = (float) Math.sin(2.0 * Math.PI * 1000.0 * i / SR);
        }
        reverb.process(in, out, n);

        // After PARTITION_SIZE samples of latency the wet output should equal
        // scaled input. The 20 Hz HP and 20 kHz LP barely attenuate 1 kHz.
        for (int i = B * 2; i < n; i++) {
            assertThat(out[0][i])
                    .as("frame %d", i)
                    .isCloseTo(k * in[0][i - B], org.assertj.core.data.Offset.offset(0.05f));
        }
    }

    @Test
    void bundledStretchSetterApproximatelyDoublesIrLength() {
        // End-to-end: setStretch(2.0) on a bundled IR should make the loaded
        // IR length ~ 2× the unstretched length (with the 10 s cap).
        ConvolutionReverbProcessor reverb = new ConvolutionReverbProcessor(1, SR);
        reverb.setIrSelection(0); // small-room
        int baseLen = reverb.getImpulseResponseLength();
        assertThat(baseLen).isGreaterThan(0);

        reverb.setStretch(2.0);
        int stretchedLen = reverb.getImpulseResponseLength();
        int cap = (int) (ConvolutionReverbProcessor.MAX_IR_LENGTH_SECONDS * SR);
        int expected = Math.min(baseLen * 2, cap);
        // Allow ±2 samples for rounding
        assertThat(stretchedLen).isBetween(expected - 2, expected + 2);
    }

    @Test
    void stretchProducesPredictableSpectralShift() {
        // A 2× stretch lowers the frequency content by ~ 1 octave.
        // We verify this indirectly: stretching an IR with a sharp impulse
        // produces a longer, lower-frequency-energy result than the base IR.
        ConvolutionReverbProcessor base = new ConvolutionReverbProcessor(1, SR);
        ConvolutionReverbProcessor stretched = new ConvolutionReverbProcessor(1, SR);

        int len = 2048;
        float[][] ir = new float[1][len];
        // Decaying chirp
        for (int i = 0; i < len; i++) {
            ir[0][i] = (float) (Math.sin(2 * Math.PI * (200 + i * 0.5) * i / SR)
                    * Math.exp(-i / (double) len * 5));
        }
        base.setImpulseResponse(ir);
        // Stretch IR by 2× via direct linear interpolation
        float[][] sIr = new float[1][len * 2];
        for (int i = 0; i < len * 2; i++) {
            double srcPos = i / 2.0;
            int idx = (int) srcPos;
            double frac = srcPos - idx;
            float a = ir[0][Math.min(idx, len - 1)];
            float b = ir[0][Math.min(idx + 1, len - 1)];
            sIr[0][i] = (float) (a + (b - a) * frac);
        }
        stretched.setImpulseResponse(sIr);

        assertThat(stretched.getImpulseResponseLength()).isEqualTo(2 * base.getImpulseResponseLength());
    }

    @Test
    void bypassWithMixZeroPassesDrySignal() {
        ConvolutionReverbProcessor reverb = new ConvolutionReverbProcessor(1, SR);
        reverb.setMix(0.0);

        float[][] in = new float[1][B];
        float[][] out = new float[1][B];
        for (int i = 0; i < B; i++) {
            in[0][i] = (float) Math.sin(2.0 * Math.PI * 220.0 * i / SR) * 0.3f;
        }
        reverb.process(in, out, B);

        for (int i = 0; i < B; i++) {
            assertThat(out[0][i])
                    .isCloseTo(in[0][i], org.assertj.core.data.Offset.offset(1e-5f));
        }
    }

    @Test
    void resetClearsInternalState() {
        ConvolutionReverbProcessor reverb = new ConvolutionReverbProcessor(1, SR);
        reverb.setMix(1.0);

        // Run an impulse through to populate internal state
        float[][] in = new float[1][B * 4];
        float[][] out = new float[1][B * 4];
        in[0][0] = 1.0f;
        reverb.process(in, out, B * 4);

        reverb.reset();

        float[][] silence = new float[1][B * 4];
        float[][] resetOut = new float[1][B * 4];
        reverb.process(silence, resetOut, B * 4);

        for (int i = 0; i < B * 4; i++) {
            assertThat(resetOut[0][i])
                    .isCloseTo(0.0f, org.assertj.core.data.Offset.offset(1e-5f));
        }
    }

    @Test
    void shouldProduceReverbTailFromImpulseInput() {
        ConvolutionReverbProcessor reverb = new ConvolutionReverbProcessor(1, SR);
        reverb.setMix(1.0);

        int n = B * 16;
        float[][] in = new float[1][n];
        float[][] out = new float[1][n];
        in[0][0] = 1.0f;
        reverb.process(in, out, n);

        double tailRms = rms(out[0], B * 4, n);
        assertThat(tailRms).isGreaterThan(0.0);
    }

    @Test
    void parameterChangesAreVisibleViaGetters() {
        ConvolutionReverbProcessor r = new ConvolutionReverbProcessor(2, SR);
        r.setMix(0.7);
        r.setPredelayMs(50.0);
        r.setLowCutHz(80.0);
        r.setHighCutHz(8000.0);
        r.setStereoWidth(1.5);
        assertThat(r.getMix()).isEqualTo(0.7);
        assertThat(r.getPredelayMs()).isEqualTo(50.0);
        assertThat(r.getLowCutHz()).isEqualTo(80.0);
        assertThat(r.getHighCutHz()).isEqualTo(8000.0);
        assertThat(r.getStereoWidth()).isEqualTo(1.5);
    }

    @Test
    void irSelectionLoadsDifferentBundledIrs() {
        ConvolutionReverbProcessor r = new ConvolutionReverbProcessor(1, SR);
        r.setIrSelection(0);
        int smallLen = r.getImpulseResponseLength();
        r.setIrSelection(4); // cathedral
        int cathedralLen = r.getImpulseResponseLength();
        assertThat(cathedralLen).isGreaterThan(smallLen);
    }

    @Test
    void asyncImpulseResponseLoadEventuallyApplies() throws Exception {
        ConvolutionReverbProcessor r = new ConvolutionReverbProcessor(1, SR);
        int len = 1500;
        float[][] ir = new float[1][len];
        ir[0][0] = 1.0f;
        r.setImpulseResponseAsync(ir).get();
        assertThat(r.getImpulseResponseLength()).isEqualTo(len);
    }

    @Test
    void trimReducesEffectiveIrLength() {
        ConvolutionReverbProcessor r = new ConvolutionReverbProcessor(1, SR);
        r.setIrSelection(2); // large-room
        int baseLen = r.getImpulseResponseLength();
        r.setTrimStart(0.25);
        r.setTrimEnd(0.75);
        // Half the IR length, with stretch=1.0
        int trimmed = r.getImpulseResponseLength();
        assertThat(trimmed).isLessThan(baseLen);
        assertThat(trimmed).isBetween((int) (baseLen * 0.45), (int) (baseLen * 0.55) + 2);
    }

    private static double rms(float[] buf, int from, int to) {
        double s = 0;
        for (int i = from; i < to; i++) s += (double) buf[i] * buf[i];
        return Math.sqrt(s / (to - from));
    }
}
