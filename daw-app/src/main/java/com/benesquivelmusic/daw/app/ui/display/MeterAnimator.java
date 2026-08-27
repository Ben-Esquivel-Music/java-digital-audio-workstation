package com.benesquivelmusic.daw.app.ui.display;

/**
 * Smooth meter animation engine with professional ballistics.
 *
 * <p>Provides peak-hold and smooth attack/release decay for driving audio
 * meters, spectrum analyzers, and other real-time visual displays. The host
 * (a {@code GpuCanvas} frame loop) calls {@link #update(double, long)} once
 * per frame with the real elapsed time; the smoothing coefficient is derived
 * from that delta on every call — {@code coeff = 1 − exp(−Δt/τ)} — so the
 * decay per unit time is identical at 60 Hz, 120 Hz or across a dropped
 * frame (story 318). Because the form is a first-order exponential approach,
 * a large gap converges to the target without overshoot.</p>
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

    private static final double NANOS_PER_SECOND = 1_000_000_000.0;
    /** Below this the smoothed value snaps to zero (an exponential never reaches it). */
    private static final double ZERO_SNAP = 0.0001;

    private final double attackSeconds;
    private final double releaseSeconds;
    private final double peakHoldSeconds;

    private double currentValue;
    private double peakValue;
    private double peakHoldTimer;

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
        this.attackSeconds = attackSeconds;
        this.releaseSeconds = releaseSeconds;
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
     * Advances the animation by {@code deltaNanos} towards {@code targetValue}.
     *
     * <p>Exponential smoothing with the attack time constant while rising and
     * the release time constant while falling; the peak-hold indicator
     * latches the target, holds for the configured time, then falls with the
     * release time constant. A delta of zero or less leaves every value
     * unchanged — no time passed, so nothing moves.</p>
     *
     * @param targetValue the new target value (0.0 to 1.0+)
     * @param deltaNanos  time elapsed since last update in nanoseconds
     */
    public void update(double targetValue, long deltaNanos) {
        if (deltaNanos <= 0L) {
            return;
        }
        double deltaSeconds = deltaNanos / NANOS_PER_SECOND;

        double tau = targetValue > currentValue ? attackSeconds : releaseSeconds;
        currentValue += (targetValue - currentValue) * coefficient(deltaSeconds, tau);
        if (currentValue < ZERO_SNAP) {
            currentValue = 0.0;
        }

        if (targetValue >= peakValue) {
            peakValue = targetValue;
            peakHoldTimer = peakHoldSeconds;
        } else {
            peakHoldTimer -= deltaSeconds;
            if (peakHoldTimer <= 0) {
                peakValue += (0.0 - peakValue) * coefficient(deltaSeconds, releaseSeconds);
                if (peakValue < ZERO_SNAP) {
                    peakValue = 0.0;
                }
            }
        }
    }

    /** {@code 1 − exp(−Δt/τ)}: the fraction of the remaining distance covered in {@code deltaSeconds}. */
    private static double coefficient(double deltaSeconds, double tauSeconds) {
        return 1.0 - Math.exp(-deltaSeconds / tauSeconds);
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
