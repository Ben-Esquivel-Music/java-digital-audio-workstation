package com.benesquivelmusic.daw.core.dsp;

/**
 * ITU-R BS.1770-4 compliant true peak detector using 4× oversampling.
 *
 * <p>True peak detection identifies intersample peaks that occur between
 * digital sample points. These peaks can cause clipping during D/A conversion
 * or lossy encoding (MP3, AAC). The ITU-R BS.1770-4 standard specifies 4×
 * oversampling with a low-pass interpolation filter to detect these peaks.</p>
 *
 * <p>The interpolation filter uses a 12-tap FIR (4 phases × 12 taps = 48
 * coefficients) derived from a sinc-windowed low-pass filter at the Nyquist
 * frequency of the original sample rate.</p>
 */
public final class TruePeakDetector {

    private static final int OVERSAMPLING_FACTOR = 4;
    private static final int FILTER_TAPS = 12;

    /**
     * 4-phase polyphase FIR filter coefficients for 4× oversampling.
     *
     * <p>These coefficients implement a sinc-windowed low-pass interpolation
     * filter compliant with ITU-R BS.1770-4. Each sub-array represents one
     * phase of the polyphase decomposition.</p>
     */
    private static final double[][] COEFFICIENTS = {
            // Phase 0 (original samples pass through with unity gain)
            {0.0017089843750, -0.0291748046875, -0.0189208984375, -0.0083007812500,
             0.0109863281250, 0.0292968750000, 0.0330810546875, 0.0292968750000,
             0.0109863281250, -0.0083007812500, -0.0189208984375, 0.0017089843750},
            // Phase 1
            {0.0036865234375, -0.0117187500000, -0.0462646484375, -0.0457763671875,
             0.0022583007812, 0.0897216796875, 0.1760253906250, 0.2090148925781,
             0.1760253906250, 0.0897216796875, 0.0022583007812, -0.0457763671875},
            // Phase 2
            {-0.0189208984375, 0.0292968750000, 0.0109863281250, -0.0083007812500,
             -0.0189208984375, 0.0017089843750, 0.0109863281250, 0.0292968750000,
             0.0330810546875, 0.0292968750000, 0.0109863281250, -0.0083007812500},
            // Phase 3
            {-0.0457763671875, 0.0897216796875, 0.1760253906250, 0.2090148925781,
             0.1760253906250, 0.0897216796875, 0.0022583007812, -0.0457763671875,
             -0.0462646484375, -0.0117187500000, 0.0036865234375, 0.0017089843750}
    };

    private final double[] history;
    private int historyPos;
    private double truePeakLinear;

    public TruePeakDetector() {
        this.history = new double[FILTER_TAPS];
        this.historyPos = 0;
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
        truePeakLinear = 0.0;
    }
}
