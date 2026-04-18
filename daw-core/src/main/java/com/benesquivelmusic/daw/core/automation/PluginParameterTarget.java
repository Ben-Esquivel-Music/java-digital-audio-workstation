package com.benesquivelmusic.daw.core.automation;

import com.benesquivelmusic.daw.sdk.plugin.AutomatableParameter;

import java.util.Objects;

/**
 * An {@link AutomationTarget} pointing to a specific parameter on a specific
 * plugin instance inserted on a mixer channel.
 *
 * <p>Unlike {@link AutomationParameter} — which enumerates the handful of
 * mixer-channel parameters shared by all tracks — plugin parameter targets are
 * per-instance: the same {@code CompressorPlugin} class inserted on two
 * different tracks produces two independent targets because the
 * {@code pluginInstanceId} differs.</p>
 *
 * <p>The {@code pluginInstanceId} is an opaque string assigned by the host
 * when a plugin is inserted — typically the plugin descriptor id combined
 * with the track id and slot index (for example
 * {@code "com.benesquivelmusic.daw.builtin.compressor@track-abc/slot-0"}).
 * Two targets are considered equal if and only if they have the same
 * {@code pluginInstanceId} and {@code parameterId}; the range, default and
 * unit fields carry metadata only.</p>
 *
 * @param pluginInstanceId stable host-assigned identifier for the plugin
 *                         instance (not just the plugin class)
 * @param parameterId      parameter id as reported by
 *                         {@link AutomatableParameter#id()}
 * @param displayName      human-readable label (e.g., {@code "Threshold"})
 * @param minValue         minimum allowed value (inclusive)
 * @param maxValue         maximum allowed value (inclusive)
 * @param defaultValue     default / reset value
 * @param unit             short unit suffix (e.g., {@code "dB"}); may be
 *                         empty but never {@code null}
 */
public record PluginParameterTarget(
        String pluginInstanceId,
        int parameterId,
        String displayName,
        double minValue,
        double maxValue,
        double defaultValue,
        String unit
) implements AutomationTarget {

    public PluginParameterTarget {
        Objects.requireNonNull(pluginInstanceId, "pluginInstanceId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(unit, "unit must not be null");
        if (pluginInstanceId.isBlank()) {
            throw new IllegalArgumentException("pluginInstanceId must not be blank");
        }
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

    @Override
    public double getMinValue() {
        return minValue;
    }

    @Override
    public double getMaxValue() {
        return maxValue;
    }

    @Override
    public double getDefaultValue() {
        return defaultValue;
    }

    /**
     * Creates a target from an SDK {@link AutomatableParameter} descriptor
     * and the host-assigned instance id.
     *
     * @param pluginInstanceId the instance identifier
     * @param descriptor       the parameter descriptor reported by the plugin
     * @return a new target
     */
    public static PluginParameterTarget of(String pluginInstanceId,
                                           AutomatableParameter descriptor) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        return new PluginParameterTarget(
                pluginInstanceId,
                descriptor.id(),
                descriptor.displayName(),
                descriptor.minValue(),
                descriptor.maxValue(),
                descriptor.defaultValue(),
                descriptor.unit());
    }
}
