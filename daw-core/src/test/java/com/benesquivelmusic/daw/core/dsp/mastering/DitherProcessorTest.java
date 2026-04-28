package com.benesquivelmusic.daw.core.dsp.mastering;

import com.benesquivelmusic.daw.core.dsp.mastering.DitherProcessor.DitherType;
import com.benesquivelmusic.daw.core.dsp.mastering.DitherProcessor.NoiseShape;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

/**
 * Verifies the dithered bit-depth reduction stage.
 *
 * <p>Coverage areas:</p>
 * <ul>
 *   <li>Construction & parameter validation.</li>
 *   <li>Quantization correctness at multiple bit depths.</li>
 *   <li>Acceptance criteria from the issue:
 *     <ul>
 *       <li>Without dither (truncation), a low-level sine has measurable
 *           harmonic distortion.</li>
 *       <li>With TPDF dither the harmonic distortion is replaced with
 *           broadband noise at the expected −90 dBFS-ish level.</li>
 *       <li>With a noise-shaped curve, the audible-band (1–4 kHz) noise
 *           floor is lower than with a flat (TPDF) curve at the cost of more
 *           ultrasonic noise.</li>
 *     </ul>
 *   </li>
 * </ul>
 */
class DitherProcessorTest {

    private static final int SR = 48_000;

    // ── Construction & validation ────────────────────────────────────────────

    @Test
    void shouldRejectInvalidChannels() {
        assertThatThrownBy(() -> new DitherProcessor(0, 16))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidBitDepth() {
        assertThatThrownBy(() -> new DitherProcessor(1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DitherProcessor(1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DitherProcessor(1, 33))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldDefaultToTpdfFlat() {
        DitherProcessor p = new DitherProcessor(2, 16);
        assertThat(p.getType()).isEqualTo(DitherType.TPDF);
        assertThat(p.getShape()).isEqualTo(NoiseShape.FLAT);
        assertThat(p.getTargetBitDepth()).isEqualTo(16);
        assertThat(p.getInputChannelCount()).isEqualTo(2);
        assertThat(p.getOutputChannelCount()).isEqualTo(2);
    }

    @Test
    void shouldUpdateBitDepthDynamically() {
        DitherProcessor p = new DitherProcessor(1, 16);
        p.setTargetBitDepth(24);
        assertThat(p.getTargetBitDepth()).isEqualTo(24);
        assertThatThrownBy(() -> p.setTargetBitDepth(40))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldUpdateTypeAndShape() {
        DitherProcessor p = new DitherProcessor(1, 16);
        p.setType(DitherType.RPDF);
        p.setShape(NoiseShape.WEIGHTED);
        assertThat(p.getType()).isEqualTo(DitherType.RPDF);
        assertThat(p.getShape()).isEqualTo(NoiseShape.WEIGHTED);
        // null falls back to defaults rather than throwing.
        p.setType(null);
        p.setShape(null);
        assertThat(p.getType()).isEqualTo(DitherType.TPDF);
        assertThat(p.getShape()).isEqualTo(NoiseShape.FLAT);
    }

    // ── Quantization ─────────────────────────────────────────────────────────

    @Test
    void shouldQuantizeCloseToInputAtSixteenBit() {
        DitherProcessor p = new DitherProcessor(1, 16, DitherType.TPDF, NoiseShape.FLAT, 42L);
        float[][] in = {{0.5f, -0.5f, 0.0f, 1.0f}};
        float[][] out = new float[1][4];
        p.process(in, out, 4);
        for (int i = 0; i < 4; i++) {
            assertThat((double) out[0][i]).isCloseTo(in[0][i], offset(0.01));
        }
    }

    @Test
    void shouldClampToValidRange() {
        DitherProcessor p = new DitherProcessor(1, 8, DitherType.TPDF, NoiseShape.FLAT, 42L);
        float[][] in = {{1.5f, -1.5f}};
        float[][] out = new float[1][2];
        p.process(in, out, 2);
        assertThat(out[0][0]).isLessThanOrEqualTo(1.01f);
        assertThat(out[0][1]).isGreaterThanOrEqualTo(-1.01f);
    }

    // ── Acceptance criteria from the issue ───────────────────────────────────

    /**
     * A −90 dBFS sine at 16-bit is a textbook case: it sits ~1 LSB peak, so
     * pure truncation produces a square-ish output rich in odd harmonics.
     * The 3rd-harmonic energy should be many dB above the broadband noise
     * floor.
     */
    @Test
    void truncationOfMinusNinetyDbSineProducesMeasurableHarmonicDistortion() {
        int n = 8192;
        double freq = 1000.0;       // fundamental
        double levelDb = -90.0;     // ~1 LSB peak at 16-bit
        float[] signal = sine(n, freq, SR, levelDb);

        DitherProcessor noDither = new DitherProcessor(
                1, 16, DitherType.NONE, NoiseShape.FLAT, 1L);
        float[] out = process(noDither, signal);

        double fundamental = bandPower(out, SR, freq, 50.0);
        double thirdHarm   = bandPower(out, SR, 3 * freq, 50.0);
        double broadband   = bandPowerExcluding(out, SR,
                new double[] {freq, 2 * freq, 3 * freq, 4 * freq, 5 * freq}, 200.0);

        // The third harmonic must clearly tower over the broadband noise floor:
        // truncation of a near-LSB sine produces strong odd harmonics.
        assertThat(toDb(thirdHarm) - toDb(broadband))
                .as("3rd-harmonic-to-noise ratio under pure truncation")
                .isGreaterThan(10.0);
        assertThat(toDb(fundamental))
                .as("fundamental survives truncation")
                .isGreaterThan(toDb(broadband) + 5.0);
    }

    /**
     * With TPDF dither, the harmonic distortion is replaced with broadband
     * noise: the 3rd-harmonic-to-noise ratio collapses (no spurious tones).
     */
    @Test
    void tpdfDitherEliminatesHarmonicDistortionAndProducesBroadbandNoise() {
        int n = 8192;
        double freq = 1000.0;
        double levelDb = -90.0;
        float[] signal = sine(n, freq, SR, levelDb);

        DitherProcessor truncate = new DitherProcessor(
                1, 16, DitherType.NONE, NoiseShape.FLAT, 1L);
        DitherProcessor tpdf = new DitherProcessor(
                1, 16, DitherType.TPDF, NoiseShape.FLAT, 1L);

        float[] outTrunc = process(truncate, signal);
        float[] outTpdf  = process(tpdf, signal);

        double thirdTrunc = bandPower(outTrunc, SR, 3 * freq, 50.0);
        double thirdTpdf  = bandPower(outTpdf, SR, 3 * freq, 50.0);
        double broadbandTpdf = bandPowerExcluding(outTpdf, SR,
                new double[] {freq, 2 * freq, 3 * freq, 4 * freq, 5 * freq}, 200.0);

        // TPDF must drop the 3rd-harmonic spike substantially.
        assertThat(toDb(thirdTrunc) - toDb(thirdTpdf))
                .as("TPDF reduction in 3rd-harmonic spike")
                .isGreaterThan(6.0);

        // The remaining noise is broadband — no single harmonic bin sits
        // far above the broadband floor.
        assertThat(toDb(thirdTpdf) - toDb(broadbandTpdf))
                .as("3rd-harmonic excess over broadband noise (TPDF)")
                .isLessThan(6.0);

        // Sanity: 16-bit TPDF dither noise floor is ~ −93 dBFS RMS.
        double rmsDb = toDb(rmsPower(outTpdf));
        assertThat(rmsDb).isBetween(-110.0, -70.0);
    }

    /**
     * Noise-shaped dither must reduce the audible-band noise (1–4 kHz) at the
     * cost of higher ultrasonic (>16 kHz) noise compared with flat TPDF.
     */
    @Test
    void noiseShapedDitherReducesAudibleBandNoiseAtCostOfUltrasonicNoise() {
        int n = 32768;
        // Pure silence so we measure the dither noise spectrum directly.
        float[] silence = new float[n];

        DitherProcessor flat = new DitherProcessor(
                1, 16, DitherType.TPDF, NoiseShape.FLAT, 7L);
        DitherProcessor shaped = new DitherProcessor(
                1, 16, DitherType.NOISE_SHAPED, NoiseShape.WEIGHTED, 7L);

        float[] outFlat   = process(flat, silence);
        float[] outShaped = process(shaped, silence);

        double audFlat   = bandRmsPower(outFlat,   SR, 1000.0,  4000.0);
        double audShaped = bandRmsPower(outShaped, SR, 1000.0,  4000.0);
        double ultFlat   = bandRmsPower(outFlat,   SR, 16000.0, 22000.0);
        double ultShaped = bandRmsPower(outShaped, SR, 16000.0, 22000.0);

        assertThat(toDb(audShaped) - toDb(audFlat))
                .as("audible-band (1–4 kHz) noise: shaped vs flat")
                .isLessThan(-1.0);
        assertThat(toDb(ultShaped) - toDb(ultFlat))
                .as("ultrasonic-band (16–22 kHz) noise: shaped vs flat")
                .isGreaterThan(1.0);
    }

    @Test
    void resetClearsErrorHistory() {
        DitherProcessor p = new DitherProcessor(
                1, 16, DitherType.NOISE_SHAPED, NoiseShape.POWR_2, 1L);
        float[][] in = {{0.1f, 0.2f, 0.3f, 0.4f}};
        float[][] out = new float[1][4];
        p.process(in, out, 4);
        // Should not throw and should leave the processor usable afterwards.
        p.reset();
        p.process(in, out, 4);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static float[] sine(int n, double freq, double sampleRate, double levelDb) {
        float[] s = new float[n];
        double amp = Math.pow(10.0, levelDb / 20.0);
        double w = 2.0 * Math.PI * freq / sampleRate;
        for (int i = 0; i < n; i++) {
            s[i] = (float) (amp * Math.sin(w * i));
        }
        return s;
    }

    private static float[] process(DitherProcessor p, float[] mono) {
        float[][] in = {mono};
        float[][] out = new float[1][mono.length];
        p.process(in, out, mono.length);
        return out[0];
    }

    /** Naive Goertzel single-bin power. Returns mean-squared energy. */
    private static double bandPower(float[] x, double sr, double freq, double bwHz) {
        // Average power across a tiny ±bwHz bin using DFT samples.
        // Use simple DFT around the target frequency.
        double sumSq = 0.0;
        int bins = 5;
        for (int b = -bins; b <= bins; b++) {
            double f = freq + b * (bwHz / (2.0 * bins + 1));
            sumSq += dftMagnitudeSq(x, sr, f);
        }
        return sumSq / (2 * bins + 1);
    }

    private static double bandPowerExcluding(float[] x, double sr, double[] excluded, double widthHz) {
        // Sample the broadband floor between excluded bins.
        double total = 0.0;
        int count = 0;
        for (double f = 200.0; f < sr / 2.0 - 200.0; f += 250.0) {
            boolean skip = false;
            for (double ex : excluded) {
                if (Math.abs(f - ex) < widthHz) { skip = true; break; }
            }
            if (skip) continue;
            total += dftMagnitudeSq(x, sr, f);
            count++;
        }
        return total / Math.max(1, count);
    }

    /** RMS power within a frequency band by summing single-bin DFT energies. */
    private static double bandRmsPower(float[] x, double sr, double fLow, double fHigh) {
        double total = 0.0;
        int count = 0;
        for (double f = fLow; f <= fHigh; f += 100.0) {
            total += dftMagnitudeSq(x, sr, f);
            count++;
        }
        return total / Math.max(1, count);
    }

    /** Squared magnitude of the DFT of {@code x} at frequency {@code freq}. */
    private static double dftMagnitudeSq(float[] x, double sr, double freq) {
        double w = 2.0 * Math.PI * freq / sr;
        double re = 0.0, im = 0.0;
        for (int n = 0; n < x.length; n++) {
            re += x[n] * Math.cos(w * n);
            im += x[n] * Math.sin(w * n);
        }
        // Normalize so a unit-amplitude tone yields ~0.25 (single-side) — exact
        // value irrelevant since we only compare in dB.
        double scale = 1.0 / x.length;
        re *= scale; im *= scale;
        return re * re + im * im;
    }

    private static double rmsPower(float[] x) {
        double s = 0.0;
        for (float v : x) s += v * v;
        return s / x.length;
    }

    private static double toDb(double power) {
        return 10.0 * Math.log10(Math.max(power, 1e-30));
    }
}
