package com.benesquivelmusic.daw.core.automation;

/**
 * Enumerates the track parameters that can be automated.
 *
 * <p>Each parameter has a defined value range used to validate automation
 * point values.</p>
 */
public enum AutomationParameter {

    /** Track volume (0.0 = silence, 1.0 = unity gain). */
    VOLUME(0.0, 1.0, 1.0),

    /** Track pan (−1.0 = full left, 0.0 = center, 1.0 = full right). */
    PAN(-1.0, 1.0, 0.0),

    /** Track mute (0.0 = unmuted, 1.0 = muted). */
    MUTE(0.0, 1.0, 0.0),

    /** Send level to auxiliary bus (0.0 = no send, 1.0 = full send). */
    SEND_LEVEL(0.0, 1.0, 0.0);

    private final double minValue;
    private final double maxValue;
    private final double defaultValue;

    AutomationParameter(double minValue, double maxValue, double defaultValue) {
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.defaultValue = defaultValue;
    }

    /** Returns the minimum allowed value for this parameter. */
    public double getMinValue() {
        return minValue;
    }

    /** Returns the maximum allowed value for this parameter. */
    public double getMaxValue() {
        return maxValue;
    }

    /** Returns the default value for this parameter. */
    public double getDefaultValue() {
        return defaultValue;
    }

    /**
     * Returns {@code true} if the given value is within the valid range for
     * this parameter.
     *
     * @param value the value to check
     * @return {@code true} if valid
     */
    public boolean isValidValue(double value) {
        return value >= minValue && value <= maxValue;
    }
}
