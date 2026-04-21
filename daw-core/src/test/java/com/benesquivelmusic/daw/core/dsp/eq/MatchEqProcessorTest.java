package com.benesquivelmusic.daw.core.dsp.eq;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchEqProcessorTest {

    private static final double SR = 48_000.0;
    private static final int SAMPLES = 48_000; // 1 second

    /**
     * Given identical source and reference signals, the target curve must be
     * essentially flat (unity gain) within FFT resolution.
     */
    @Test
    void identicalReferenceAndSourceProducesFlatCurve() {
        float[][] white = whiteNoise(1, SAMPLES, 42);

        MatchEqProcessor proc = new MatchEqProcessor(1, SR);
        proc.setFftSize(MatchEqProcessor.FftSize.SIZE_2048);
        proc.setSmoothing(MatchEqProcessor.Smoothing.THIRD_OCTAVE);
        proc.analyzeSource(white);
        proc.analyzeReference(white);
        proc.updateMatch();

        double[] curve = proc.getTargetCurve();
        assertNotNull(curve, "target curve should be computed");

        // Ignore the DC bin and the near-Nyquist tail (FFT edges always noisy).
        int half = curve.length;
        int lo = 1;
        int hi = (int) (half * 0.9);
        for (int k = lo; k < hi; k++) {
            double db = 20.0 * Math.log10(Math.max(curve[k], 1e-12));
            assertTrue(Math.abs(db) < 0.5,
                    "bin " + k + " expected flat but was " + db + " dB");
        }
    }

    /**
     * Feeding white noise as source and pink noise as reference produces a
     * filter with the expected –3&nbsp;dB/octave (pink) slope.
     */
    @Test
    void whiteSourceWithPinkReferenceProducesPinkSlope() {
        float[][] white = whiteNoise(1, SAMPLES, 1);
        float[][] pink = pinkNoise(1, SAMPLES, 2);

        MatchEqProcessor proc = new MatchEqProcessor(1, SR);
        proc.setFftSize(MatchEqProcessor.FftSize.SIZE_4096);
        proc.setSmoothing(MatchEqProcessor.Smoothing.THIRD_OCTAVE);
        proc.analyzeSource(white);
        proc.analyzeReference(pink);
        proc.updateMatch();

        double[] curve = proc.getTargetCurve();
        assertNotNull(curve);

        int fft = (curve.length - 1) * 2;
        double binHz = SR / fft;

        double db500 = magDb(curve, 500.0 / binHz);
        double db1000 = magDb(curve, 1000.0 / binHz);
        double db2000 = magDb(curve, 2000.0 / binHz);
        double db4000 = magDb(curve, 4000.0 / binHz);

        double slopePerOctave1 = db1000 - db500;
        double slopePerOctave2 = db2000 - db1000;
        double slopePerOctave3 = db4000 - db2000;

        // Expect ≈ –3 dB/octave; tolerate ±1.5 dB around each sampled octave
        // given finite noise/averaging.
        assertTrue(Math.abs(slopePerOctave1 + 3.0) < 1.5,
                "500→1k slope was " + slopePerOctave1 + " dB/oct");
        assertTrue(Math.abs(slopePerOctave2 + 3.0) < 1.5,
                "1k→2k slope was " + slopePerOctave2 + " dB/oct");
        assertTrue(Math.abs(slopePerOctave3 + 3.0) < 1.5,
                "2k→4k slope was " + slopePerOctave3 + " dB/oct");
    }

    /**
     * In linear-phase mode the FIR filter must be symmetric (Type I) so that
     * it introduces constant group delay — i.e. no group-delay variation.
     */
    @Test
    void linearPhaseModeHasConstantGroupDelay() {
        float[][] white = whiteNoise(1, SAMPLES, 3);
        float[][] pink = pinkNoise(1, SAMPLES, 4);

        MatchEqProcessor proc = new MatchEqProcessor(1, SR);
        proc.setFftSize(MatchEqProcessor.FftSize.SIZE_2048);
        proc.setPhaseMode(MatchEqProcessor.PhaseMode.LINEAR_PHASE);
        proc.setFirOrder(1023);
        proc.analyzeSource(white);
        proc.analyzeReference(pink);
        proc.updateMatch();

        assertTrue(proc.isMatchActive());
        int latency = proc.getLatencySamples();
        assertEquals((1023 - 1) / 2, latency,
                "linear-phase latency must be (order-1)/2");

        // Feed an impulse through and confirm the resulting impulse response
        // is symmetric around the latency sample (Type I FIR ⇒ constant group
        // delay ⇒ no group-delay variation).
        int len = 2048;
        float[][] in = new float[1][len];
        float[][] out = new float[1][len];
        in[0][0] = 1.0f;
        proc.process(in, out, len);

        int center = latency;
        double maxAsym = 0.0;
        double maxMag = 0.0;
        for (int i = 0; i < len; i++) maxMag = Math.max(maxMag, Math.abs(out[0][i]));
        for (int k = 1; k <= Math.min(center, len - 1 - center); k++) {
            double diff = Math.abs(out[0][center - k] - out[0][center + k]);
            maxAsym = Math.max(maxAsym, diff);
        }
        assertTrue(maxAsym < maxMag * 1e-3,
                "impulse response not symmetric: asym=" + maxAsym + " vs peak=" + maxMag);
    }

    /**
     * Without a captured reference the processor must be a pass-through.
     */
    @Test
    void passThroughWhenNotMatched() {
        MatchEqProcessor proc = new MatchEqProcessor(1, SR);
        assertFalse(proc.isMatchActive());
        assertNull(proc.getTargetCurve());

        float[][] in = whiteNoise(1, 2048, 7);
        float[][] out = new float[1][2048];
        proc.process(in, out, 2048);
        for (int i = 0; i < 2048; i++) {
            assertEquals(in[0][i], out[0][i], 1e-9);
        }
    }

    /**
     * captureSource() freezes the running live accumulator as the source
     * spectrum.
     */
    @Test
    void captureSourceFreezesLiveAccumulator() {
        MatchEqProcessor proc = new MatchEqProcessor(1, SR);
        proc.setFftSize(MatchEqProcessor.FftSize.SIZE_1024);
        assertFalse(proc.captureSource(), "no capture in progress yet");

        proc.startLiveCapture();
        float[][] in = whiteNoise(1, 8192, 11);
        float[][] out = new float[1][8192];
        proc.process(in, out, 8192);

        assertTrue(proc.captureSource(), "live accumulator should have frames");
        double[] src = proc.getSourceSpectrum();
        assertNotNull(src);
        assertEquals(1024 / 2 + 1, src.length);
    }

    /**
     * Amount = 0 must produce an identity target curve regardless of the
     * captured spectra.
     */
    @Test
    void amountZeroYieldsIdentity() {
        float[][] white = whiteNoise(1, SAMPLES, 5);
        float[][] pink = pinkNoise(1, SAMPLES, 6);

        MatchEqProcessor proc = new MatchEqProcessor(1, SR);
        proc.analyzeSource(white);
        proc.analyzeReference(pink);
        proc.setAmount(0.0);
        proc.updateMatch();

        double[] curve = proc.getTargetCurve();
        assertNotNull(curve);
        for (double m : curve) {
            assertEquals(1.0, m, 1e-9);
        }
    }

    // ---- helpers ----------------------------------------------------------

    private static float[][] whiteNoise(int channels, int samples, long seed) {
        Random r = new Random(seed);
        float[][] out = new float[channels][samples];
        for (int c = 0; c < channels; c++) {
            for (int i = 0; i < samples; i++) {
                out[c][i] = (float) ((r.nextDouble() * 2.0 - 1.0) * 0.5);
            }
        }
        return out;
    }

    /** Paul Kellet's economy pink-noise filter (–3 dB/oct across the audio band). */
    private static float[][] pinkNoise(int channels, int samples, long seed) {
        Random r = new Random(seed);
        float[][] out = new float[channels][samples];
        for (int c = 0; c < channels; c++) {
            double b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0;
            for (int i = 0; i < samples; i++) {
                double w = r.nextDouble() * 2.0 - 1.0;
                b0 = 0.99886 * b0 + w * 0.0555179;
                b1 = 0.99332 * b1 + w * 0.0750759;
                b2 = 0.96900 * b2 + w * 0.1538520;
                b3 = 0.86650 * b3 + w * 0.3104856;
                b4 = 0.55000 * b4 + w * 0.5329522;
                b5 = -0.7616 * b5 - w * 0.0168980;
                double pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362;
                b6 = w * 0.115926;
                out[c][i] = (float) (pink * 0.11);
            }
        }
        return out;
    }

    private static double magDb(double[] curve, double binPos) {
        int lo = (int) Math.floor(binPos);
        int hi = Math.min(lo + 1, curve.length - 1);
        double frac = binPos - lo;
        double m = curve[lo] * (1.0 - frac) + curve[hi] * frac;
        return 20.0 * Math.log10(Math.max(m, 1e-12));
    }
}
