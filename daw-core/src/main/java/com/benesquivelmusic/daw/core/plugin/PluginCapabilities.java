package com.benesquivelmusic.daw.core.plugin;

import java.util.Objects;
import java.util.Set;

/**
 * Structured, reflection-discovered summary of an {@link com.benesquivelmusic.daw.sdk.audio.AudioProcessor}'s
 * capabilities, used by UI and mixer layers to decide which controls
 * (sidechain source selector, gain-reduction meter, latency label, …) to
 * expose for a given processor without scattering {@code instanceof} checks
 * throughout the code base.
 *
 * <p>Instances are produced by {@link PluginCapabilityIntrospector} which
 * probes a processor class via reflection once and caches the result per
 * {@link Class}.</p>
 *
 * @param processesAudio         {@code true} if the processor implements
 *                               {@link com.benesquivelmusic.daw.sdk.audio.AudioProcessor}
 * @param providesSidechainInput {@code true} if the processor implements
 *                               {@link com.benesquivelmusic.daw.sdk.audio.SidechainAwareProcessor}
 * @param reportsGainReduction   {@code true} if the processor implements
 *                               {@link com.benesquivelmusic.daw.core.dsp.GainReductionProvider}
 * @param reportsLatency         {@code true} if the processor overrides
 *                               {@link com.benesquivelmusic.daw.sdk.audio.AudioProcessor#getLatencySamples()}
 *                               (i.e., is potentially capable of reporting non-zero latency)
 * @param supportsStereoOnly     {@code true} if the processor is restricted to stereo I/O
 * @param realTimeSafeProcess    {@code true} if the processor's {@code process} method is annotated
 *                               with {@link com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe}
 * @param parameterCount         number of {@link com.benesquivelmusic.daw.sdk.annotation.ProcessorParam}-
 *                               annotated getters discovered on the processor class
 * @param genericallyConstructible {@code true} if the processor exposes a
 *                               {@code (int channels, double sampleRate)} public constructor
 * @param customCapabilities     open-ended set of string keys identifying additional
 *                               capabilities (e.g., external feature tags); never {@code null}
 */
public record PluginCapabilities(
        boolean processesAudio,
        boolean providesSidechainInput,
        boolean reportsGainReduction,
        boolean reportsLatency,
        boolean supportsStereoOnly,
        boolean realTimeSafeProcess,
        int parameterCount,
        boolean genericallyConstructible,
        Set<String> customCapabilities) {

    /**
     * Canonical constructor. Defensively copies {@code customCapabilities}
     * into an immutable set and validates basic invariants.
     */
    public PluginCapabilities {
        Objects.requireNonNull(customCapabilities, "customCapabilities must not be null");
        if (parameterCount < 0) {
            throw new IllegalArgumentException("parameterCount must be >= 0: " + parameterCount);
        }
        customCapabilities = Set.copyOf(customCapabilities);
    }

    /**
     * A {@link PluginCapabilities} value describing a processor that has no
     * detected capabilities. Useful as a fallback for {@code null} processors.
     */
    public static final PluginCapabilities NONE = new PluginCapabilities(
            false, false, false, false, false, false, 0, false, Set.of());

    /** Convenience: {@code true} iff this processor has the named custom capability. */
    public boolean hasCustomCapability(String key) {
        return customCapabilities.contains(key);
    }
}
