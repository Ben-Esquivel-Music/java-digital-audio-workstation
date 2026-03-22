package com.benesquivelmusic.daw.sdk.spatial;

import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot of the 3D spatial panner state for UI visualization.
 *
 * <p>Contains the current source position, per-speaker gains, spread,
 * distance attenuation gain, and positioning mode — everything the
 * 3D panner display needs to render the current state.</p>
 *
 * @param sourcePosition   the current 3D source position
 * @param speakerPositions the speaker layout positions
 * @param speakerGains     per-speaker gain coefficients (same order as speakers)
 * @param spread           the source spread factor [0, 1]
 * @param distanceGain     the distance attenuation gain [0, 1]
 * @param hfRolloff        the high-frequency rolloff factor [0, 1]
 * @param reverbSend       the distance-based reverb send level [0, 1]
 * @param positioningMode  the current positioning mode
 */
public record SpatialPannerData(
        SpatialPosition sourcePosition,
        List<SpatialPosition> speakerPositions,
        double[] speakerGains,
        double spread,
        double distanceGain,
        double hfRolloff,
        double reverbSend,
        PositioningMode positioningMode
) {

    public SpatialPannerData {
        Objects.requireNonNull(sourcePosition, "sourcePosition must not be null");
        Objects.requireNonNull(speakerPositions, "speakerPositions must not be null");
        Objects.requireNonNull(speakerGains, "speakerGains must not be null");
        Objects.requireNonNull(positioningMode, "positioningMode must not be null");
        speakerPositions = List.copyOf(speakerPositions);
        speakerGains = speakerGains.clone();
    }
}
