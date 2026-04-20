package com.benesquivelmusic.daw.sdk.telemetry;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable per-source critical-distance result.
 *
 * <p>Critical distance ({@code d_c}) is the distance from a sound source
 * at which the direct-sound energy equals the reverberant-field energy.
 * Inside {@code d_c} the direct field dominates (high clarity); outside
 * it the reverberant field dominates (low clarity).</p>
 *
 * <p>Formula for a source of directivity factor {@code Q} in a diffuse
 * room of volume {@code V} and reverberation time {@code T60}:
 *
 * <pre>d_c = 0.141 · √(Q · V / (π · T60))</pre></p>
 *
 * @param sourceId        stable identifier of the originating sound source
 * @param distanceMeters  computed critical distance, in metres
 * @param directivity     the directivity used to derive the distance
 */
public record CriticalDistanceSnapshot(
        UUID sourceId,
        double distanceMeters,
        SourceDirectivity directivity) {

    public CriticalDistanceSnapshot {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(directivity, "directivity must not be null");
        if (!(distanceMeters >= 0) || Double.isNaN(distanceMeters)
                || Double.isInfinite(distanceMeters)) {
            throw new IllegalArgumentException(
                    "distanceMeters must be a finite non-negative number: "
                            + distanceMeters);
        }
    }
}
