package com.benesquivelmusic.daw.sdk.spatial;

/**
 * Continuously-automatable parameters of an object-based audio panner.
 *
 * <p>These are the per-object metadata fields that Dolby Atmos Renderer,
 * Pro Tools Ultimate Object Panner, and Nuendo ObjectPanner all expose as
 * automation lanes under a spatial track. Each value has a defined range
 * and default that the host uses to validate automation point values and
 * to seed a fresh lane.</p>
 *
 * <ul>
 *   <li>{@link #X} — left/right position, [−1.0, +1.0], default {@code 0.0}.</li>
 *   <li>{@link #Y} — back/front position, [−1.0, +1.0], default {@code 0.0}.</li>
 *   <li>{@link #Z} — bottom/top position, [−1.0, +1.0], default {@code 0.0}.</li>
 *   <li>{@link #SIZE} — apparent source size / spread, [0.0, 1.0], default
 *       {@code 0.0} (point source).</li>
 *   <li>{@link #DIVERGENCE} — fraction of energy spread toward neighbouring
 *       speakers, [0.0, 1.0], default {@code 0.0}.</li>
 *   <li>{@link #GAIN} — per-object gain in linear scale, [0.0, 1.0], default
 *       {@code 1.0}.</li>
 * </ul>
 *
 * <p>Ranges align with {@link ObjectMetadata} so that an object-parameter
 * automation lane can write directly into per-frame {@code ObjectMetadata}
 * values without any rescaling.</p>
 */
public enum ObjectParameter {

    /** Horizontal position [−1.0, +1.0] (left to right). */
    X(-1.0, 1.0, 0.0),

    /** Depth position [−1.0, +1.0] (back to front). */
    Y(-1.0, 1.0, 0.0),

    /** Vertical position [−1.0, +1.0] (bottom to top). */
    Z(-1.0, 1.0, 0.0),

    /** Apparent source size / spread [0.0, 1.0] (point to fully diffuse). */
    SIZE(0.0, 1.0, 0.0),

    /** Fraction of object energy diverged to neighbouring speakers [0.0, 1.0]. */
    DIVERGENCE(0.0, 1.0, 0.0),

    /** Per-object linear gain [0.0, 1.0]. */
    GAIN(0.0, 1.0, 1.0);

    private final double minValue;
    private final double maxValue;
    private final double defaultValue;

    ObjectParameter(double minValue, double maxValue, double defaultValue) {
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.defaultValue = defaultValue;
    }

    /** Returns the inclusive minimum value the parameter accepts. */
    public double getMinValue() {
        return minValue;
    }

    /** Returns the inclusive maximum value the parameter accepts. */
    public double getMaxValue() {
        return maxValue;
    }

    /** Returns the default / reset value of the parameter. */
    public double getDefaultValue() {
        return defaultValue;
    }

    /** Returns a short human-readable label for UI display. */
    public String displayName() {
        return name();
    }

    /**
     * Returns {@code true} if the given value is inside this parameter's
     * valid range.
     *
     * @param value the value to check
     * @return {@code true} if {@code min <= value <= max}
     */
    public boolean isValidValue(double value) {
        return value >= minValue && value <= maxValue;
    }
}
