package com.benesquivelmusic.daw.core.plugin.parameter;

import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Holds the mutable state (current values) for a set of {@link PluginParameter} descriptors.
 *
 * <p>Each parameter is identified by its {@link PluginParameter#id()} and its
 * current value is clamped to the [min, max] range defined in the descriptor.
 * Values can be reset to the parameter's default individually or all at once.</p>
 */
public final class PluginParameterState {

    private final List<PluginParameter> parameters;
    private final Map<Integer, Double> values;

    /**
     * Creates a new state initialized with each parameter's default value.
     *
     * @param parameters the parameter descriptors
     * @throws NullPointerException if {@code parameters} is {@code null}
     */
    public PluginParameterState(List<PluginParameter> parameters) {
        Objects.requireNonNull(parameters, "parameters must not be null");
        this.parameters = List.copyOf(parameters);
        this.values = new LinkedHashMap<>();
        for (PluginParameter param : this.parameters) {
            values.put(param.id(), param.defaultValue());
        }
    }

    /**
     * Returns the parameter descriptors.
     *
     * @return an unmodifiable list of parameter descriptors
     */
    public List<PluginParameter> getParameters() {
        return parameters;
    }

    /**
     * Returns the current value of the parameter with the given id.
     *
     * @param parameterId the parameter identifier
     * @return the current value
     * @throws IllegalArgumentException if no parameter with the given id exists
     */
    public double getValue(int parameterId) {
        Double value = values.get(parameterId);
        if (value == null) {
            throw new IllegalArgumentException("Unknown parameter id: " + parameterId);
        }
        return value;
    }

    /**
     * Sets the value of the parameter with the given id.
     *
     * <p>The value is clamped to the parameter's [min, max] range.</p>
     *
     * @param parameterId the parameter identifier
     * @param value       the new value
     * @throws IllegalArgumentException if no parameter with the given id exists
     */
    public void setValue(int parameterId, double value) {
        PluginParameter param = findParameter(parameterId);
        double clamped = Math.max(param.minValue(), Math.min(param.maxValue(), value));
        values.put(parameterId, clamped);
    }

    /**
     * Resets the parameter with the given id to its default value.
     *
     * @param parameterId the parameter identifier
     * @throws IllegalArgumentException if no parameter with the given id exists
     */
    public void resetToDefault(int parameterId) {
        PluginParameter param = findParameter(parameterId);
        values.put(parameterId, param.defaultValue());
    }

    /**
     * Resets all parameters to their default values.
     */
    public void resetAllToDefaults() {
        for (PluginParameter param : parameters) {
            values.put(param.id(), param.defaultValue());
        }
    }

    /**
     * Returns a snapshot of all current parameter values.
     *
     * @return an unmodifiable map from parameter id to current value
     */
    public Map<Integer, Double> getAllValues() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /**
     * Loads a complete set of parameter values.
     *
     * <p>Values for unknown parameter ids are silently ignored.
     * Values outside the parameter's range are clamped.</p>
     *
     * @param valueMap the map of parameter id to value
     * @throws NullPointerException if {@code valueMap} is {@code null}
     */
    public void loadValues(Map<Integer, Double> valueMap) {
        Objects.requireNonNull(valueMap, "valueMap must not be null");
        for (Map.Entry<Integer, Double> entry : valueMap.entrySet()) {
            if (values.containsKey(entry.getKey())) {
                setValue(entry.getKey(), entry.getValue());
            }
        }
    }

    private PluginParameter findParameter(int parameterId) {
        for (PluginParameter param : parameters) {
            if (param.id() == parameterId) {
                return param;
            }
        }
        throw new IllegalArgumentException("Unknown parameter id: " + parameterId);
    }
}
