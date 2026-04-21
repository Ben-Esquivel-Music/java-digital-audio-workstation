package com.benesquivelmusic.daw.core.project.edit;

import java.util.Objects;

/**
 * Per-project configuration for the nudge command.
 *
 * <p>A nudge is described by a {@link NudgeUnit unit} and a scalar
 * {@code amount} in that unit. For example:</p>
 *
 * <ul>
 *   <li>{@code new NudgeSettings(GRID_STEPS, 1.0)} — one grid step
 *       (the default).</li>
 *   <li>{@code new NudgeSettings(MILLISECONDS, 10.0)} — ten ms.</li>
 *   <li>{@code new NudgeSettings(FRAMES, 1.0)} — one sample (also
 *       reachable via the {@code Alt+Left/Right} shortcut regardless
 *       of the configured unit).</li>
 * </ul>
 *
 * <p>The record is immutable — construct a new instance to change the
 * nudge settings.</p>
 *
 * <p>Story — Nudge Clips and Selections by Grid and by Sample.</p>
 *
 * @param unit    the unit this nudge is measured in; must not be
 *                {@code null}
 * @param amount  the magnitude of one nudge in {@code unit}; must be
 *                finite and strictly positive
 */
public record NudgeSettings(NudgeUnit unit, double amount) {

    /**
     * Default nudge: one grid step. This matches the user-story
     * requirement {@code "Default nudge: 1 grid step"}.
     */
    public static final NudgeSettings DEFAULT = new NudgeSettings(NudgeUnit.GRID_STEPS, 1.0);

    public NudgeSettings {
        Objects.requireNonNull(unit, "unit must not be null");
        if (!Double.isFinite(amount) || amount <= 0.0) {
            throw new IllegalArgumentException(
                    "amount must be a finite, strictly positive number: " + amount);
        }
    }
}
