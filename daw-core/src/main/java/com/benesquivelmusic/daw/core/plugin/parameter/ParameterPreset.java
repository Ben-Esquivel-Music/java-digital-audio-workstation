package com.benesquivelmusic.daw.core.plugin.parameter;

import java.util.Map;
import java.util.Objects;

/**
 * An immutable named preset containing parameter values for a plugin.
 *
 * <p>Presets are identified by name and store a snapshot of parameter
 * id-to-value mappings. Factory presets are distinguished from user
 * presets via the {@link #factory()} flag.</p>
 *
 * @param name    the human-readable preset name (e.g., "Warm Vocal EQ")
 * @param values  the parameter id-to-value mappings
 * @param factory {@code true} if this is a built-in factory preset
 */
public record ParameterPreset(
        String name,
        Map<Integer, Double> values,
        boolean factory
) {
    public ParameterPreset {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(values, "values must not be null");
        values = Map.copyOf(values);
    }

    /**
     * Creates a user preset with the given name and values.
     *
     * @param name   the preset name
     * @param values the parameter values
     * @return a new user preset
     */
    public static ParameterPreset user(String name, Map<Integer, Double> values) {
        return new ParameterPreset(name, values, false);
    }

    /**
     * Creates a factory preset with the given name and values.
     *
     * @param name   the preset name
     * @param values the parameter values
     * @return a new factory preset
     */
    public static ParameterPreset factory(String name, Map<Integer, Double> values) {
        return new ParameterPreset(name, values, true);
    }
}
