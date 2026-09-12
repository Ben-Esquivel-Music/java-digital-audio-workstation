package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.SpectrumDisplay;
import com.benesquivelmusic.daw.sdk.visualization.SpectrumData;

import java.util.Objects;

/**
 * Generates the synthetic, breathing spectrum that keeps the idle DAW
 * spectrum display alive when no audio is being processed.
 *
 * <p>Extracted from {@link AnimationController} so the deterministic
 * idle-animation math (pink-noise spectrum shape, frequency-dependent
 * wobble, low-mid breathing bump) can be unit tested without a JavaFX
 * toolkit. The class is purely a function of an accumulated phase plus a
 * per-frame delta — given a fixed phase value its outputs are
 * reproducible.</p>
 *
 * <p>Story 318 removed the synthetic RMS/peak push into the Peak / RMS
 * level display: that display is now a real consumer of the engine's
 * metering tap bus ({@code MASTER_OUT} through the {@code MeterFeed}), and a
 * display never has the real feed and a fiction side by side. The spectrum
 * arm stays until story 319 wires the analysis-lane consumers and deletes
 * this class.</p>
 *
 * <p>Issue: "Decompose Remaining God-Class Controllers into Focused
 * Services."</p>
 */
final class IdleVisualizationAnimator {

    /** FFT size implied by the synthesized spectrum. */
    static final int IDLE_FFT_SIZE = 1024;

    /** Number of magnitude bins (FFT_SIZE / 2). */
    static final int BIN_COUNT = IDLE_FFT_SIZE / 2;

    private static final double IDLE_SAMPLE_RATE = 44100.0;

    private final SpectrumDisplay spectrumDisplay;
    private final float[] bins = new float[BIN_COUNT];

    private double phaseSeconds;

    IdleVisualizationAnimator(SpectrumDisplay spectrumDisplay) {
        this.spectrumDisplay = Objects.requireNonNull(spectrumDisplay,
                "spectrumDisplay must not be null");
    }

    /**
     * Advances the animation phase and pushes the next synthesized
     * spectrum frame to the configured display.
     *
     * @param deltaSeconds elapsed seconds since the previous tick
     */
    void tick(double deltaSeconds) {
        phaseSeconds += deltaSeconds;
        computeSpectrumBins(phaseSeconds, bins);
        spectrumDisplay.updateSpectrum(new SpectrumData(bins, IDLE_FFT_SIZE, IDLE_SAMPLE_RATE));
    }

    /**
     * Computes a synthesized pink-noise-shaped spectrum into {@code out}
     * for the given accumulated phase. Pure function — exposed for
     * testing.
     *
     * @param phaseSeconds accumulated animation phase, in seconds
     * @param out          target buffer of length {@link #BIN_COUNT}
     */
    static void computeSpectrumBins(double phaseSeconds, float[] out) {
        if (out.length < BIN_COUNT) {
            throw new IllegalArgumentException(
                    "spectrum buffer must be at least " + BIN_COUNT + " bins");
        }
        for (int i = 1; i < BIN_COUNT; i++) {
            double t = Math.log((double) i / BIN_COUNT + 1.0) / Math.log(2.0);
            double base = -28.0 - t * 30.0;
            double wobble = 7.0 * Math.sin(phaseSeconds * 0.9 + t * 5.5);
            double bump = 5.0 * Math.exp(-Math.pow((t - 0.25), 2) / 0.01)
                    * (0.5 + 0.5 * Math.sin(phaseSeconds * 0.6));
            out[i] = (float) Math.max(-90.0, base + wobble + bump);
        }
        out[0] = out[1];
    }
}
