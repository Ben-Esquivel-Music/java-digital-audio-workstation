package com.benesquivelmusic.daw.core.automation;

import com.benesquivelmusic.daw.sdk.spatial.ObjectParameter;

import java.util.Objects;

/**
 * An {@link AutomationTarget} pointing to a specific {@link ObjectParameter}
 * on a specific object panner instance.
 *
 * <p>Object panners (used by spatial / immersive tracks) expose the per-object
 * metadata fields {@code x}, {@code y}, {@code z}, {@code size},
 * {@code divergence}, and {@code gain} as continuously-automatable parameters.
 * Each lane targets one parameter on one panner, identified by an opaque
 * host-assigned {@code objectInstanceId} (typically the object id assigned
 * to the spatial track or send).</p>
 *
 * <p>Two targets are equal if and only if they have the same
 * {@code objectInstanceId} and {@code parameter}; the value range and
 * defaults come from the {@link ObjectParameter} enum and so are always
 * consistent for a given parameter.</p>
 *
 * <p>Sample-accurate interpolation between automation points is performed
 * by the surrounding {@link AutomationLane} machinery — this target only
 * supplies range, default and identity. The output value of the lane at a
 * given time can be written directly into the corresponding field of
 * {@link com.benesquivelmusic.daw.sdk.spatial.ObjectMetadata} (for {@code X},
 * {@code Y}, {@code Z}, {@code SIZE}, {@code GAIN}) or into the panner's
 * divergence coefficient (for {@code DIVERGENCE}).</p>
 *
 * @param objectInstanceId stable host-assigned identifier for the object
 *                         panner instance (not just the parameter kind)
 * @param parameter        which object parameter is automated
 */
public record ObjectParameterTarget(
        String objectInstanceId,
        ObjectParameter parameter
) implements AutomationTarget {

    public ObjectParameterTarget {
        Objects.requireNonNull(objectInstanceId, "objectInstanceId must not be null");
        Objects.requireNonNull(parameter, "parameter must not be null");
        if (objectInstanceId.isBlank()) {
            throw new IllegalArgumentException("objectInstanceId must not be blank");
        }
    }

    @Override
    public String displayName() {
        return parameter.displayName();
    }

    @Override
    public double getMinValue() {
        return parameter.getMinValue();
    }

    @Override
    public double getMaxValue() {
        return parameter.getMaxValue();
    }

    @Override
    public double getDefaultValue() {
        return parameter.getDefaultValue();
    }

    @Override
    public boolean isValidValue(double value) {
        return parameter.isValidValue(value);
    }
}
