package com.benesquivelmusic.daw.sdk.audio.performance;

import java.util.Objects;

/**
 * A soft CPU budget assigned to a single track, together with the
 * {@link DegradationPolicy} the engine should apply when the track
 * persistently exceeds its budget.
 *
 * <p>This is the SDK-level configuration value; the engine-side
 * enforcer (in {@code daw-core}) measures per-track CPU via nanosecond
 * timestamps around each track's processing segment, maintains a
 * rolling average, and consults the configured budget before
 * processing each insert.</p>
 *
 * <p>The default policy is {@link DegradationPolicy.DoNothing} so that
 * older projects loaded from disk behave exactly as before: budgets
 * are opt-in and configurable from the mixer channel properties
 * dialog.</p>
 *
 * @param maxFractionOfBlock the soft ceiling on the fraction of a
 *                           real-time block budget (i.e.,
 *                           {@code bufferSize / sampleRate}) that this
 *                           single track may consume; must lie in the
 *                           open interval {@code (0.0, 1.0]}
 * @param onOverBudget       the policy to apply once the track has
 *                           been over its budget for the configured
 *                           number of consecutive blocks; must not be
 *                           {@code null}
 */
public record TrackCpuBudget(double maxFractionOfBlock, DegradationPolicy onOverBudget) {

    /**
     * An unconstrained budget (allows up to 100% of the block) with no
     * degradation — equivalent to disabling per-track enforcement for
     * that channel. Used as the default when loading older projects
     * that did not persist a budget.
     */
    public static final TrackCpuBudget UNLIMITED =
            new TrackCpuBudget(1.0, new DegradationPolicy.DoNothing());

    public TrackCpuBudget {
        Objects.requireNonNull(onOverBudget, "onOverBudget must not be null");
        if (Double.isNaN(maxFractionOfBlock) || maxFractionOfBlock <= 0.0 || maxFractionOfBlock > 1.0) {
            throw new IllegalArgumentException(
                    "maxFractionOfBlock must be in (0.0, 1.0]: " + maxFractionOfBlock);
        }
    }

    /**
     * Returns {@code true} if the given measured CPU fraction is
     * strictly above this budget's ceiling.
     *
     * @param measuredFraction CPU fraction as reported by the engine
     *                         (typically a rolling average)
     * @return whether the fraction exceeds {@link #maxFractionOfBlock}
     */
    public boolean isOverBudget(double measuredFraction) {
        return measuredFraction > maxFractionOfBlock;
    }
}
