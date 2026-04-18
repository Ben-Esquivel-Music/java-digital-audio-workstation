package com.benesquivelmusic.daw.core.mixer.snapshot;

import com.benesquivelmusic.daw.core.mixer.InsertEffectType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable snapshot of the state of a single insert slot on a mixer channel.
 *
 * <p>Captures the insert's effect type, bypass flag, and current parameter
 * values. CLAP and external plugins are captured with a {@code null}
 * {@link #effectType()} and an empty parameter map — they are treated as
 * opaque and their parameter state is not recalled.</p>
 *
 * @param effectType the built-in effect type, or {@code null} for CLAP/external plugins
 * @param bypassed   whether this insert was bypassed at capture time
 * @param parameters effect-parameter values keyed by parameter id (defensively copied, unmodifiable)
 */
public record InsertSnapshot(InsertEffectType effectType,
                             boolean bypassed,
                             Map<Integer, Double> parameters) {

    public InsertSnapshot {
        Objects.requireNonNull(parameters, "parameters must not be null");
        parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }
}
