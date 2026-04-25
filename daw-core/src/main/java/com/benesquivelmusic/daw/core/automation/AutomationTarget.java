package com.benesquivelmusic.daw.core.automation;

/**
 * A sealed abstraction over the "things" an {@link AutomationLane} can
 * control.
 *
 * <p>Two permitted implementations exist:</p>
 * <ul>
 *   <li>{@link AutomationParameter} — mixer-channel parameters (volume, pan,
 *       mute, send level). These are enumerated and shared across all tracks.</li>
 *   <li>{@link PluginParameterTarget} — a parameter exposed by an effect
 *       plugin instance. Each plugin parameter has its own instance-specific
 *       identifier, so these targets are created on demand when a user adds a
 *       plugin-parameter automation lane.</li>
 *   <li>{@link ObjectParameterTarget} — a per-object spatial-panner parameter
 *       (X / Y / Z / SIZE / DIVERGENCE / GAIN) on a specific object panner
 *       instance. Used by spatial tracks for sample-accurate trajectory
 *       automation that feeds object-based renderers (Atmos, ADM BWF).</li>
 * </ul>
 *
 * <p>Giving both kinds of targets a common interface lets
 * {@link AutomationLane} store a single {@code target} field and lets
 * {@link AutomationData} validate incoming point values through one shared
 * {@code [min,max]} contract.</p>
 *
 * <p>Using a sealed interface keeps the set of target kinds closed: any new
 * target type (e.g., a future MIDI controller target) must be added here
 * explicitly, giving the compiler exhaustiveness checks over {@code switch}
 * statements that dispatch on target kind.</p>
 */
public sealed interface AutomationTarget
        permits AutomationParameter, PluginParameterTarget, ObjectParameterTarget {

    /** Returns a short human-readable label for UI display. */
    String displayName();

    /** Returns the inclusive minimum value the target accepts. */
    double getMinValue();

    /** Returns the inclusive maximum value the target accepts. */
    double getMaxValue();

    /** Returns the default / reset value of the target. */
    double getDefaultValue();

    /**
     * Returns {@code true} if the given value is inside this target's valid
     * range.
     *
     * @param value the value to check
     * @return {@code true} if {@code min <= value <= max}
     */
    default boolean isValidValue(double value) {
        return value >= getMinValue() && value <= getMaxValue();
    }
}
