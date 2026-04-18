package com.benesquivelmusic.daw.core.template;

import com.benesquivelmusic.daw.core.mixer.InsertEffectType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A serializable specification for a single insert effect slot.
 *
 * <p>Captures the {@link InsertEffectType built-in effect type} together with
 * its parameter values (keyed by the parameter IDs defined by
 * {@link com.benesquivelmusic.daw.core.mixer.InsertEffectFactory#getParameterDescriptors})
 * and the slot's bypass flag.</p>
 *
 * <p>{@link InsertEffectType#CLAP_PLUGIN} is intentionally not supported —
 * third-party CLAP plugins cannot be reliably captured in a portable template
 * format. Templates that are applied to channels will simply skip any unknown
 * effect types.</p>
 *
 * @param type          the built-in effect type (must not be {@code null} or
 *                      {@link InsertEffectType#CLAP_PLUGIN})
 * @param parameters    the parameter values keyed by parameter ID (never
 *                      {@code null}; stored as an unmodifiable copy)
 * @param bypassed      whether this insert should start bypassed
 */
public record InsertEffectSpec(InsertEffectType type,
                               Map<Integer, Double> parameters,
                               boolean bypassed) {

    public InsertEffectSpec {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(parameters, "parameters must not be null");
        if (type == InsertEffectType.CLAP_PLUGIN) {
            throw new IllegalArgumentException(
                    "CLAP plugins cannot be captured in a template spec");
        }
        parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }

    /**
     * Creates a spec with no parameter overrides (default values) and
     * not bypassed.
     *
     * @param type the built-in effect type
     * @return a new spec
     */
    public static InsertEffectSpec ofDefaults(InsertEffectType type) {
        return new InsertEffectSpec(type, Map.of(), false);
    }

    /**
     * Creates a spec with the given parameter overrides and not bypassed.
     *
     * @param type       the built-in effect type
     * @param parameters the parameter values keyed by parameter ID
     * @return a new spec
     */
    public static InsertEffectSpec of(InsertEffectType type, Map<Integer, Double> parameters) {
        return new InsertEffectSpec(type, parameters, false);
    }
}
