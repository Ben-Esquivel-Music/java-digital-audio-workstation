package com.benesquivelmusic.daw.sdk.audio.performance;

import java.util.Objects;

/**
 * Sealed interface describing notifications emitted by the per-track
 * CPU budget enforcer. UI layers subscribe to a
 * {@link java.util.concurrent.Flow.Publisher Flow.Publisher&lt;TrackPerformanceEvent&gt;}
 * so the mixer can dim a track's strip or show a "degraded" badge when
 * an insert has been auto-bypassed, and lift the badge when full
 * quality is restored.
 *
 * <p>Modelled as an algebraic data type (JEP 409 sealed classes +
 * JEP 395 records) so subscribers can dispatch with exhaustive
 * pattern-matching {@code switch} expressions (JEP 441).</p>
 */
public sealed interface TrackPerformanceEvent
        permits TrackPerformanceEvent.TrackDegraded,
                TrackPerformanceEvent.TrackRestored {

    /** Returns the identifier of the track this event applies to. */
    String trackId();

    /**
     * Emitted when a track's rolling CPU fraction has exceeded its
     * budget for the required number of consecutive blocks and the
     * engine has applied {@link #appliedPolicy} to keep the overall
     * mix intact.
     *
     * @param trackId        identifier of the degraded track; must not
     *                       be {@code null}
     * @param measuredFraction the rolling-average CPU fraction that
     *                         tripped the budget; must be
     *                         {@code >= 0}
     * @param budget         the track's configured budget at the time
     *                       of degradation; must not be {@code null}
     * @param appliedPolicy  the policy the engine actually applied;
     *                       must not be {@code null}
     */
    record TrackDegraded(String trackId,
                         double measuredFraction,
                         TrackCpuBudget budget,
                         DegradationPolicy appliedPolicy) implements TrackPerformanceEvent {
        public TrackDegraded {
            Objects.requireNonNull(trackId, "trackId must not be null");
            Objects.requireNonNull(budget, "budget must not be null");
            Objects.requireNonNull(appliedPolicy, "appliedPolicy must not be null");
            if (Double.isNaN(measuredFraction) || measuredFraction < 0.0) {
                throw new IllegalArgumentException(
                        "measuredFraction must be non-negative and finite: " + measuredFraction);
            }
        }
    }

    /**
     * Emitted when a previously-degraded track has stayed below its
     * budget long enough for the engine to restore full quality.
     *
     * @param trackId        identifier of the restored track; must not
     *                       be {@code null}
     * @param measuredFraction the rolling-average CPU fraction at the
     *                         time of restoration; must be {@code >= 0}
     */
    record TrackRestored(String trackId, double measuredFraction) implements TrackPerformanceEvent {
        public TrackRestored {
            Objects.requireNonNull(trackId, "trackId must not be null");
            if (Double.isNaN(measuredFraction) || measuredFraction < 0.0) {
                throw new IllegalArgumentException(
                        "measuredFraction must be non-negative and finite: " + measuredFraction);
            }
        }
    }
}
