package com.benesquivelmusic.daw.core.automation;

/**
 * Enumerates the track parameters that can be automated.
 *
 * <p>Each parameter has a defined value range used to validate automation
 * point values.</p>
 */
public enum AutomationParameter implements AutomationTarget {

    /** Track volume (0.0 = silence, 1.0 = unity gain). */
    VOLUME(0.0, 1.0, 1.0),

    /** Track pan (−1.0 = full left, 0.0 = center, 1.0 = full right). */
    PAN(-1.0, 1.0, 0.0),

    /** Track mute (0.0 = unmuted, 1.0 = muted). */
    MUTE(0.0, 1.0, 0.0),

    /**
     * Level of the track channel's {@code Send} targeting the first return
     * bus — {@code Mixer.getAuxBus()}, the "aux bus" this lane has always
     * named (0.0 = no send, 1.0 = full send).
     *
     * <p>Story 322 retargeted this lane at the live multi-bus send path: the
     * render pipeline writes {@code Send.setLevel} on the channel's send to
     * the first return bus each block. A channel with no send to that bus,
     * or a mixer left with no return bus at all, makes the lane inert — it
     * never creates routing (Audio Engine Wiring Design Book §5.6, "Send
     * levels").</p>
     */
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
    @Override
    public double getMinValue() {
        return minValue;
    }

    /** Returns the maximum allowed value for this parameter. */
    @Override
    public double getMaxValue() {
        return maxValue;
    }

    /** Returns the default value for this parameter. */
    @Override
    public double getDefaultValue() {
        return defaultValue;
    }

    /** Returns a short human-readable label (the enum constant name). */
    @Override
    public String displayName() {
        return name();
    }

    /**
     * Returns {@code true} if the given value is within the valid range for
     * this parameter.
     *
     * @param value the value to check
     * @return {@code true} if valid
     */
    @Override
    public boolean isValidValue(double value) {
        return value >= minValue && value <= maxValue;
    }
}
