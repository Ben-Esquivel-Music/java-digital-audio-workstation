package com.benesquivelmusic.daw.app.ui.display;

import javafx.animation.AnimationTimer;

/**
 * Smooth meter animation engine with professional ballistics.
 *
 * <p>Provides peak-hold, smooth attack/release decay, and configurable
 * refresh rate for driving audio meters, spectrum analyzers, and other
 * real-time visual displays. Uses JavaFX {@link AnimationTimer} for
 * frame-synchronized updates.</p>
 *
 * <p>Supports the metering and visualization requirements from the
 * mastering-techniques research (§4 — Dynamics Processing, §8 —
 * Loudness Standards and Metering) and the immersive-audio-mixing
 * research (§1 — 3D Staging).</p>
 */
public final class MeterAnimator {

    /** Default attack time constant in seconds (fast attack for peaks). */
    public static final double DEFAULT_ATTACK_SECONDS = 0.005;
    /** Default release time constant in seconds (smooth decay). */
    public static final double DEFAULT_RELEASE_SECONDS = 0.3;
    /** Default peak hold time in seconds. */
    public static final double DEFAULT_PEAK_HOLD_SECONDS = 1.5;

    private final double attackCoeff;
    private final double releaseCoeff;
    private final double peakHoldSeconds;

    private double currentValue;
    private double peakValue;
    private double peakHoldTimer;
    private long lastNanos;

    /**
     * Creates a meter animator with the specified time constants.
     *
     * @param attackSeconds      attack time constant in seconds
     * @param releaseSeconds     release time constant in seconds
     * @param peakHoldSeconds    peak hold duration in seconds
     */
    public MeterAnimator(double attackSeconds, double releaseSeconds, double peakHoldSeconds) {
        if (attackSeconds <= 0) {
            throw new IllegalArgumentException("attackSeconds must be positive: " + attackSeconds);
        }
        if (releaseSeconds <= 0) {
            throw new IllegalArgumentException("releaseSeconds must be positive: " + releaseSeconds);
        }
        if (peakHoldSeconds < 0) {
            throw new IllegalArgumentException("peakHoldSeconds must not be negative: " + peakHoldSeconds);
        }
        this.attackCoeff = 1.0 - Math.exp(-1.0 / (attackSeconds * 60.0));
        this.releaseCoeff = 1.0 - Math.exp(-1.0 / (releaseSeconds * 60.0));
        this.peakHoldSeconds = peakHoldSeconds;
        this.currentValue = 0.0;
        this.peakValue = 0.0;
        this.peakHoldTimer = 0.0;
    }

    /**
     * Creates a meter animator with default ballistics.
     */
    public MeterAnimator() {
        this(DEFAULT_ATTACK_SECONDS, DEFAULT_RELEASE_SECONDS, DEFAULT_PEAK_HOLD_SECONDS);
    }

    /**
     * Updates the animation state with a new target value.
     *
     * <p>Should be called once per animation frame (typically 60 fps).
     * Uses exponential smoothing for natural-looking meter movement.</p>
     *
     * @param targetValue the new target value (0.0 to 1.0+)
     * @param deltaNanos  time elapsed since last update in nanoseconds
     */
    public void update(double targetValue, long deltaNanos) {
        double deltaSeconds = deltaNanos / 1_000_000_000.0;

        // Smooth towards target with different attack/release rates
        if (targetValue > currentValue) {
            currentValue += (targetValue - currentValue) * attackCoeff;
        } else {
            currentValue += (targetValue - currentValue) * releaseCoeff;
        }

        // Clamp to zero
        if (currentValue < 0.0001) {
            currentValue = 0.0;
        }

        // Peak hold logic
        if (targetValue >= peakValue) {
            peakValue = targetValue;
            peakHoldTimer = peakHoldSeconds;
        } else {
            peakHoldTimer -= deltaSeconds;
            if (peakHoldTimer <= 0) {
                peakValue += (0.0 - peakValue) * releaseCoeff * 0.5;
                if (peakValue < 0.0001) {
                    peakValue = 0.0;
                }
            }
        }
    }

    /**
     * Returns the current smoothed meter value.
     *
     * @return the current display value
     */
    public double getCurrentValue() {
        return currentValue;
    }

    /**
     * Returns the current peak-hold value.
     *
     * @return the peak hold indicator value
     */
    public double getPeakValue() {
        return peakValue;
    }

    /**
     * Resets the animator to zero.
     */
    public void reset() {
        currentValue = 0.0;
        peakValue = 0.0;
        peakHoldTimer = 0.0;
    }
}
