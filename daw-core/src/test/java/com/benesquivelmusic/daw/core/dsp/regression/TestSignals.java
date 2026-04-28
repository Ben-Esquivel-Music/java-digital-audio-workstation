package com.benesquivelmusic.daw.core.dsp.regression;

import java.util.List;

/**
 * Catalog of canonical test signals used by the DSP regression framework.
 *
 * <p>The framework ships a small fixed set of <strong>committed</strong>
 * 16-bit PCM WAV files (mono, 44 100 Hz) under
 * {@code daw-core/src/test/resources/test-signals/}. They are byte-exact
 * across platforms and JDKs, ensuring golden-file diffs are caused only by
 * processor changes — never by signal-generation drift.</p>
 *
 * <p>Generation is performed once by {@link TestSignalGeneratorMain} and
 * the produced files are committed to source control. The same generator
 * is used by the rebaseline workflow, so re-running it produces identical
 * bytes (it is fully deterministic — no clock or RNG state outside the
 * declared seeds).</p>
 *
 * <h2>Signals</h2>
 * <ul>
 *   <li><b>{@code sine-sweep}</b> — exponential 20 Hz → 20 kHz sine sweep,
 *       1 s, peak −6 dBFS. Drives every band of an EQ; reveals frequency-
 *       dependent regressions.</li>
 *   <li><b>{@code white-noise}</b> — full-band white noise from a fixed-seed
 *       PRNG, 1 s, peak −6 dBFS. Reveals broadband / phase regressions.</li>
 *   <li><b>{@code transient}</b> — 1 s of silence with a single decaying
 *       transient at 100 ms (snare-like 200 Hz × exponential decay).
 *       Reveals attack / envelope-follower regressions in dynamics
 *       processors.</li>
 *   <li><b>{@code speech-like}</b> — modulated band-limited noise simulating
 *       speech-band envelope and spectrum (synthetic stand-in for a real
 *       speech clip; deterministic and license-free).</li>
 *   <li><b>{@code music-like}</b> — sum of three harmonically-related sines
 *       with slow amplitude modulation; a deterministic stand-in for a
 *       music excerpt.</li>
 * </ul>
 */
public final class TestSignals {

    /** Sample rate of every committed test signal. */
    public static final int SAMPLE_RATE = 44_100;
    /** Length in samples (1 second) of every committed test signal. */
    public static final int FRAMES = SAMPLE_RATE;

    /** All canonical test-signal names. */
    public static final List<String> ALL = List.of(
            "sine-sweep", "white-noise", "transient", "speech-like", "music-like");

    private TestSignals() {}

    // ── Generators (deterministic; see TestSignalGeneratorMain) ────────────

    /** −6 dBFS exponential sine sweep 20 Hz → 20 kHz over 1 s. */
    public static float[] sineSweep() {
        float[] out = new float[FRAMES];
        double f0 = 20.0, f1 = 20_000.0, T = 1.0;
        double k = Math.log(f1 / f0);
        double amp = dbToLin(-6.0);
        // Standard exponential sweep phase: φ(t) = 2π f0 T / k (e^{kt/T} − 1)
        for (int i = 0; i < FRAMES; i++) {
            double t = (double) i / SAMPLE_RATE;
            double phase = 2.0 * Math.PI * f0 * T / k * (Math.exp(k * t / T) - 1.0);
            out[i] = (float) (amp * Math.sin(phase));
        }
        return out;
    }

    /** −6 dBFS uniform white noise, fixed seed for byte-exact reproducibility. */
    public static float[] whiteNoise() {
        // Deterministic linear-congruential generator (Knuth MMIX) so every
        // platform/JDK produces identical samples.
        long state = 0x12345678ABCDEFL;
        float[] out = new float[FRAMES];
        double amp = dbToLin(-6.0);
        for (int i = 0; i < FRAMES; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            // Use top 32 bits, normalised to [-1, 1].
            int high = (int) (state >>> 32);
            out[i] = (float) (amp * (high / 2147483648.0));
        }
        return out;
    }

    /** Single 200 Hz exponentially-decaying transient at frame 4 410 (100 ms). */
    public static float[] transient_() {
        float[] out = new float[FRAMES];
        int onset = 4_410;
        double freq = 200.0;
        double tau = 0.04;             // 40 ms decay
        double amp = dbToLin(-6.0);
        for (int i = onset; i < FRAMES; i++) {
            double t = (double) (i - onset) / SAMPLE_RATE;
            double env = Math.exp(-t / tau);
            out[i] = (float) (amp * env * Math.sin(2.0 * Math.PI * freq * t));
        }
        return out;
    }

    /** Speech-band-modulated band-limited noise (synthetic stand-in for speech). */
    public static float[] speechLike() {
        // 200 Hz cosine-squared envelope around a 600 Hz formant carrier of
        // narrow-band noise — produces a syllable-like rhythm in the speech
        // band, deterministic and license-free.
        long state = 0xDEADBEEF12345678L;
        float[] out = new float[FRAMES];
        double amp = dbToLin(-9.0);
        // Single-pole low-pass at 1.5 kHz to band-limit the noise carrier.
        double a = Math.exp(-2.0 * Math.PI * 1500.0 / SAMPLE_RATE);
        double y = 0.0;
        for (int i = 0; i < FRAMES; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            int high = (int) (state >>> 32);
            double n = high / 2147483648.0;
            y = (1.0 - a) * n + a * y;            // band-limited noise
            double t = (double) i / SAMPLE_RATE;
            double env = Math.pow(Math.cos(2.0 * Math.PI * 4.0 * t), 2.0); // 4 Hz syllable rate
            double formant = Math.sin(2.0 * Math.PI * 600.0 * t);
            out[i] = (float) (amp * env * (0.5 * y + 0.5 * formant));
        }
        return out;
    }

    /** Three-sine "musical" tone with slow tremolo (synthetic stand-in for music). */
    public static float[] musicLike() {
        float[] out = new float[FRAMES];
        double amp = dbToLin(-9.0);
        // A4 (440), C#5 (554.37), E5 (659.26) = A-major triad
        double f1 = 440.0, f2 = 554.365262, f3 = 659.255114;
        for (int i = 0; i < FRAMES; i++) {
            double t = (double) i / SAMPLE_RATE;
            double tremolo = 0.85 + 0.15 * Math.sin(2.0 * Math.PI * 5.0 * t);
            double s = Math.sin(2.0 * Math.PI * f1 * t)
                     + Math.sin(2.0 * Math.PI * f2 * t)
                     + Math.sin(2.0 * Math.PI * f3 * t);
            out[i] = (float) (amp * tremolo * s / 3.0);
        }
        return out;
    }

    /** Look up a generator by canonical name. */
    public static float[] generate(String name) {
        return switch (name) {
            case "sine-sweep"   -> sineSweep();
            case "white-noise"  -> whiteNoise();
            case "transient"    -> transient_();
            case "speech-like"  -> speechLike();
            case "music-like"   -> musicLike();
            default -> throw new IllegalArgumentException("Unknown test signal: " + name);
        };
    }

    private static double dbToLin(double db) {
        return Math.pow(10.0, db / 20.0);
    }
}
