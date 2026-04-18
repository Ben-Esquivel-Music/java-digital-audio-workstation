package com.benesquivelmusic.daw.sdk.plugin;

import java.util.Objects;

/**
 * Immutable descriptor for a plugin parameter that can be driven by an
 * automation lane.
 *
 * <p>Plugins expose their automatable parameters via
 * {@link DawPlugin#getAutomatableParameters()} so the host can populate the
 * automation lane's parameter selector with plugin controls in addition to
 * mixer-channel parameters.</p>
 *
 * <p>An automatable parameter is identified by a numeric {@code id} that is
 * unique within the plugin. The host uses this id together with the plugin
 * instance to route automation values to
 * {@link DawPlugin#setAutomatableParameter(int, double)} at playback time.</p>
 *
 * @param id           unique parameter id within the plugin
 * @param displayName  human-readable label (e.g., {@code "Threshold"})
 * @param minValue     minimum allowed value (inclusive)
 * @param maxValue     maximum allowed value (inclusive)
 * @param defaultValue the default / reset value (within {@code [min, max]})
 * @param unit         short unit suffix for UI display (e.g., {@code "dB"},
 *                     {@code "Hz"}, {@code "%"}); empty string if unitless
 */
public record AutomatableParameter(
        int id,
        String displayName,
        double minValue,
        double maxValue,
        double defaultValue,
        String unit
) {
    public AutomatableParameter {
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(unit, "unit must not be null");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (minValue > maxValue) {
            throw new IllegalArgumentException(
                    "minValue (%f) must not exceed maxValue (%f)".formatted(minValue, maxValue));
        }
        if (defaultValue < minValue || defaultValue > maxValue) {
            throw new IllegalArgumentException(
                    "defaultValue (%f) must be within [%f, %f]"
                            .formatted(defaultValue, minValue, maxValue));
        }
    }

    /**
     * Convenience constructor for unitless parameters.
     *
     * @param id           unique parameter id
     * @param displayName  human-readable label
     * @param minValue     minimum allowed value
     * @param maxValue     maximum allowed value
     * @param defaultValue default value
     */
    public AutomatableParameter(int id, String displayName,
                                double minValue, double maxValue, double defaultValue) {
        this(id, displayName, minValue, maxValue, defaultValue, "");
    }

    /**
     * Derives an {@code AutomatableParameter} from a generic
     * {@link PluginParameter}. The derived descriptor carries an empty
     * {@link #unit()} (the legacy {@code PluginParameter} record does not
     * model a unit).
     *
     * @param parameter the source parameter descriptor
     * @return an automatable descriptor with the same id, name and range
     */
    public static AutomatableParameter from(PluginParameter parameter) {
        Objects.requireNonNull(parameter, "parameter must not be null");
        return new AutomatableParameter(
                parameter.id(),
                parameter.name(),
                parameter.minValue(),
                parameter.maxValue(),
                parameter.defaultValue(),
                "");
    }
}
