package com.benesquivelmusic.daw.sdk.audio.performance;

import java.util.Objects;

/**
 * Sealed interface describing what the engine should do when a track
 * persistently exceeds its per-track CPU budget.
 *
 * <p>Models the "graceful degradation" strategies used by modern DAWs:
 * Studio One's <em>High Precision Monitoring</em>, Reaper's
 * <em>Anticipative FX</em> throttling, and Pro Tools' plugin
 * auto-bypass. Rather than glitching the whole output when a single
 * track goes over-budget, the engine selectively degrades that one
 * track so the overall mix stays intact at the cost of isolated
 * quality loss.</p>
 *
 * <p>This is an algebraic data type (JEP 409 sealed classes + JEP 395
 * records) so consumers can dispatch with exhaustive pattern-matching
 * {@code switch} expressions (JEP 441).</p>
 */
public sealed interface DegradationPolicy
        permits DegradationPolicy.BypassExpensive,
                DegradationPolicy.ReduceOversampling,
                DegradationPolicy.SubstituteSimpleKernel,
                DegradationPolicy.DoNothing {

    /**
     * Bypass the most-expensive non-mandatory insert on the offending
     * track. Inserts that the user has flagged as mandatory (e.g.,
     * hard-clip limiters, de-essers on a vocal) are skipped by the
     * selection algorithm.
     */
    record BypassExpensive() implements DegradationPolicy { }

    /**
     * Drop the oversampling factor on the track's inserts to the given
     * fallback factor (typically {@code 1} for no oversampling).
     *
     * @param fallbackFactor the oversampling factor to switch to when
     *                       over-budget; must be {@code >= 1}
     */
    record ReduceOversampling(int fallbackFactor) implements DegradationPolicy {
        public ReduceOversampling {
            if (fallbackFactor < 1) {
                throw new IllegalArgumentException(
                        "fallbackFactor must be >= 1: " + fallbackFactor);
            }
        }
    }

    /**
     * Swap the offending plugin's DSP kernel for a simpler variant
     * identified by {@code kernelId} (for example, switching a
     * convolution reverb to an algorithmic one, or a linear-phase EQ
     * to a minimum-phase EQ).
     *
     * @param kernelId identifier of the simpler kernel to substitute;
     *                 must not be {@code null}
     */
    record SubstituteSimpleKernel(String kernelId) implements DegradationPolicy {
        public SubstituteSimpleKernel {
            Objects.requireNonNull(kernelId, "kernelId must not be null");
            if (kernelId.isBlank()) {
                throw new IllegalArgumentException("kernelId must not be blank");
            }
        }
    }

    /**
     * Take no action when the budget is exceeded. This is the default
     * policy so the engine preserves its current behavior for users who
     * have not opted in to per-track budget enforcement.
     */
    record DoNothing() implements DegradationPolicy { }
}
