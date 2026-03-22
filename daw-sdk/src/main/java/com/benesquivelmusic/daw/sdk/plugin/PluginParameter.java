package com.benesquivelmusic.daw.sdk.plugin;

import java.util.Objects;

/**
 * Immutable descriptor for a single parameter exposed by an external plugin.
 *
 * <p>Parameters allow the host (DAW) to discover and control the knobs,
 * sliders, and switches that a plugin exposes. Each parameter has an
 * identifier, a human-readable name, and a continuous value range.</p>
 *
 * @param id           unique parameter identifier within the plugin
 * @param name         human-readable parameter name (e.g., "Cutoff Frequency")
 * @param minValue     minimum allowed value
 * @param maxValue     maximum allowed value
 * @param defaultValue the default (reset) value
 */
public record PluginParameter(
        int id,
        String name,
        double minValue,
        double maxValue,
        double defaultValue
) {
    public PluginParameter {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (minValue > maxValue) {
            throw new IllegalArgumentException(
                    "minValue (%f) must not exceed maxValue (%f)".formatted(minValue, maxValue));
        }
        if (defaultValue < minValue || defaultValue > maxValue) {
            throw new IllegalArgumentException(
                    "defaultValue (%f) must be within [%f, %f]".formatted(defaultValue, minValue, maxValue));
        }
    }
}
