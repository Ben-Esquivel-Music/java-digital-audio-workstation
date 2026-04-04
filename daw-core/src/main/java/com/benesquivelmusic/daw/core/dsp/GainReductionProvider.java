package com.benesquivelmusic.daw.core.dsp;

/**
 * Interface for audio processors that expose gain reduction metering.
 *
 * <p>Dynamics processors (compressors, limiters) that reduce signal gain
 * implement this interface to report the current gain reduction in dB.
 * The value is updated on the audio thread during {@code process()} and
 * read on the UI thread for meter display.</p>
 */
public interface GainReductionProvider {

    /**
     * Returns the current gain reduction in dB (always ≤ 0).
     *
     * <p>A value of {@code 0.0} means no gain reduction is being applied.
     * Negative values indicate the amount of gain being reduced.</p>
     *
     * @return the gain reduction in dB
     */
    double getGainReductionDb();
}
