package com.benesquivelmusic.daw.core.dsp;

/**
 * ITU-R BS.1770-4 compliant true peak detector using 4× oversampling.
 *
 * <p>True peak detection identifies intersample peaks that occur between
 * digital sample points. These peaks can cause clipping during D/A conversion
 * or lossy encoding (MP3, AAC). The ITU-R BS.1770-4 standard specifies 4×
 * oversampling with a low-pass interpolation filter to detect these peaks.</p>
 *
 * <p>The interpolation filter is a 48-tap linear-phase FIR (4 polyphase
 * branches × 12 taps each) functionally equivalent to the BS.1770-4 Annex 2
 * reference filter: it presents ≈ −6 dB at the original Nyquist frequency
 * and rejects out-of-band content while reconstructing inter-sample peaks
 * from the discrete input.</p>
 */
public final class TruePeakDetector {

    private static final int OVERSAMPLING_FACTOR = 4;
    private static final int FILTER_TAPS = 12;

    /**
     * Polyphase FIR coefficients for 4× upsampling reconstruction.
     *
     * <p>The prototype filter is a 48-tap Kaiser-windowed sinc designed to
     * meet the magnitude response of the ITU-R BS.1770-4 Annex 2 reference
     * filter:</p>
     * <ul>
     *   <li>length {@code N = 48} taps (linear-phase, symmetric prototype)
     *   <li>cutoff {@code fc = fs_orig / 2} (i.e. {@code fs_4x / 8}),
     *       yielding ≈ {@code −6 dB} at the original Nyquist frequency
     *   <li>Kaiser window with {@code β ≈ 8.0} (≈ 80 dB sidelobe target;
     *       achievable stopband attenuation is limited by the 48-tap budget
     *       mandated by BS.1770-4)
     *   <li>prototype DC gain normalised to {@code L = 4} so that each
     *       polyphase sub-filter has unity DC gain
     * </ul>
     *
     * <p>The polyphase decomposition stores {@code COEFFICIENTS[p][k] =
     * h[4·k + p]} so that, for an input history where {@code x[n−1]} is the
     * most recent sample, the {@code p}-th interpolated output between
     * consecutive input samples is {@code y_p = Σ_k COEFFICIENTS[p][k] ·
     * x[n−1−k]}. The prototype is symmetric ({@code h[n] == h[N−1−n]}),
     * which appears here as time-reversal symmetry between phases {@code p}
     * and {@code 3−p}.</p>
     *
     * <p>Source: BS.1770-4 Annex 2 (4× polyphase reconstruction filter,
     * 48 taps / 12 per phase). Equivalent design parameters above are
     * provided so the filter can be regenerated independently.</p>
     */
    private static final double[][] COEFFICIENTS = {
            // Phase 0 — h[0], h[4], h[8], h[12], h[16], h[20], h[24], h[28], h[32], h[36], h[40], h[44]
            {-0.0000484909535,  0.0009902690766, -0.0050024776097,  0.0163488519976,
             -0.0439618052969,  0.1280666259700,  0.9728015979264, -0.0942818081879,
              0.0345839050248, -0.0124766816100,  0.0035226242504, -0.0005819542021},
            // Phase 1 — h[1], h[5], h[9], h[13], h[17], h[21], h[25], h[29], h[33], h[37], h[41], h[45]
            {-0.0003482638813,  0.0038164176009, -0.0167158229268,  0.0510901640679,
             -0.1352094048497,  0.4509517067186,  0.7723047265111, -0.1737756620326,
              0.0655158542040, -0.0226430603734,  0.0058062347353, -0.0007535461600},
            // Phase 2 — h[2], h[6], h[10], h[14], h[18], h[22], h[26], h[30], h[34], h[38], h[42], h[46]
            {-0.0007535461600,  0.0058062347353, -0.0226430603734,  0.0655158542040,
             -0.1737756620326,  0.7723047265111,  0.4509517067186, -0.1352094048497,
              0.0510901640679, -0.0167158229268,  0.0038164176009, -0.0003482638813},
            // Phase 3 — h[3], h[7], h[11], h[15], h[19], h[23], h[27], h[31], h[35], h[39], h[43], h[47]
            {-0.0005819542021,  0.0035226242504, -0.0124766816100,  0.0345839050248,
             -0.0942818081879,  0.9728015979264,  0.1280666259700, -0.0439618052969,
              0.0163488519976, -0.0050024776097,  0.0009902690766, -0.0000484909535}
    };

    private final double[] history;
    private int historyPos;
    private boolean primed;
    private double truePeakLinear;

    public TruePeakDetector() {
        this.history = new double[FILTER_TAPS];
        this.historyPos = 0;
        this.primed = false;
        this.truePeakLinear = 0.0;
    }

    /**
     * Processes a single sample and returns the detected true peak across all
     * interpolated phases for that sample.
     *
     * @param sample the input sample value
     * @return the maximum absolute value across all 4 interpolated sub-samples
     */
    public double processSample(double sample) {
        if (!primed) {
            // Prime the FIR delay line with the first sample so a constant /
            // slowly-varying signal does not produce a spurious step transient
            // through the filter (which would otherwise latch a phantom peak
            // significantly above the actual sample peak for DC inputs).
            java.util.Arrays.fill(history, sample);
            primed = true;
        }
        history[historyPos] = sample;
        historyPos = (historyPos + 1) % FILTER_TAPS;

        double maxAbs = Math.abs(sample);

        for (int phase = 0; phase < OVERSAMPLING_FACTOR; phase++) {
            double sum = 0.0;
            int idx = historyPos;
            for (int tap = 0; tap < FILTER_TAPS; tap++) {
                idx = (idx == 0) ? FILTER_TAPS - 1 : idx - 1;
                sum += COEFFICIENTS[phase][tap] * history[idx];
            }
            double abs = Math.abs(sum);
            if (abs > maxAbs) {
                maxAbs = abs;
            }
        }

        if (maxAbs > truePeakLinear) {
            truePeakLinear = maxAbs;
        }

        return maxAbs;
    }

    /**
     * Returns the highest true peak value observed since the last reset.
     */
    public double getTruePeakLinear() {
        return truePeakLinear;
    }

    /**
     * Returns the highest true peak value in dBTP since the last reset.
     */
    public double getTruePeakDbtp() {
        if (truePeakLinear <= 0.0) {
            return -120.0;
        }
        return 20.0 * Math.log10(truePeakLinear);
    }

    /**
     * Resets the detector state and peak memory.
     */
    public void reset() {
        java.util.Arrays.fill(history, 0.0);
        historyPos = 0;
        primed = false;
        truePeakLinear = 0.0;
    }

    // ── Package-private accessors for tests ────────────────────────────────

    /** Returns a defensive copy of the polyphase coefficient table (phase × tap). For tests. */
    static double[][] coefficients() {
        double[][] copy = new double[COEFFICIENTS.length][];
        for (int phase = 0; phase < COEFFICIENTS.length; phase++) {
            copy[phase] = java.util.Arrays.copyOf(COEFFICIENTS[phase], COEFFICIENTS[phase].length);
        }
        return copy;
    }

    /** Number of polyphase branches (oversampling factor). For tests. */
    static int oversamplingFactor() {
        return OVERSAMPLING_FACTOR;
    }

    /** Number of taps per polyphase branch. For tests. */
    static int filterTaps() {
        return FILTER_TAPS;
    }
}
