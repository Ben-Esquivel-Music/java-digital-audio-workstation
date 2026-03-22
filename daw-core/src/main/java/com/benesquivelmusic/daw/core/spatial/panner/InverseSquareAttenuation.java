package com.benesquivelmusic.daw.core.spatial.panner;

import com.benesquivelmusic.daw.sdk.spatial.DistanceAttenuationModel;

/**
 * Distance attenuation model using the inverse square law with configurable rolloff.
 *
 * <p>Within the reference distance the gain is unity. Beyond the reference
 * distance the gain follows: {@code gain = (refDist / distance) ^ rolloff}.
 * Beyond the maximum distance the gain is zero.</p>
 *
 * <p>Also provides distance-based high-frequency rolloff and reverb send
 * calculations for realistic distance perception.</p>
 */
public final class InverseSquareAttenuation implements DistanceAttenuationModel {

    private final double referenceDistance;
    private final double maxDistance;
    private final double rolloffExponent;

    /**
     * Creates an inverse square attenuation model.
     *
     * @param referenceDistance the distance at which gain is 1.0 (must be positive)
     * @param maxDistance       the distance beyond which gain is 0 (must be &gt; referenceDistance)
     * @param rolloffExponent  the rolloff exponent (2.0 = inverse square law, must be positive)
     */
    public InverseSquareAttenuation(double referenceDistance, double maxDistance,
                                    double rolloffExponent) {
        if (referenceDistance <= 0) {
            throw new IllegalArgumentException(
                    "referenceDistance must be positive: " + referenceDistance);
        }
        if (maxDistance <= referenceDistance) {
            throw new IllegalArgumentException(
                    "maxDistance must be greater than referenceDistance: " + maxDistance);
        }
        if (rolloffExponent <= 0) {
            throw new IllegalArgumentException(
                    "rolloffExponent must be positive: " + rolloffExponent);
        }
        this.referenceDistance = referenceDistance;
        this.maxDistance = maxDistance;
        this.rolloffExponent = rolloffExponent;
    }

    /**
     * Creates an inverse square attenuation model with default rolloff exponent of 2.0.
     *
     * @param referenceDistance the distance at which gain is 1.0
     * @param maxDistance       the maximum distance
     */
    public InverseSquareAttenuation(double referenceDistance, double maxDistance) {
        this(referenceDistance, maxDistance, 2.0);
    }

    @Override
    public double computeGain(double distanceMeters) {
        if (distanceMeters <= referenceDistance) {
            return 1.0;
        }
        if (distanceMeters >= maxDistance) {
            return 0.0;
        }
        return Math.pow(referenceDistance / distanceMeters, rolloffExponent);
    }

    @Override
    public double getReferenceDistance() {
        return referenceDistance;
    }

    @Override
    public double getMaxDistance() {
        return maxDistance;
    }

    @Override
    public double computeHighFrequencyRolloff(double distanceMeters) {
        if (distanceMeters <= referenceDistance) {
            return 1.0;
        }
        if (distanceMeters >= maxDistance) {
            return 0.0;
        }
        double normalized = (distanceMeters - referenceDistance) / (maxDistance - referenceDistance);
        return 1.0 - normalized;
    }

    @Override
    public double computeReverbSend(double distanceMeters) {
        if (distanceMeters <= referenceDistance) {
            return 0.0;
        }
        if (distanceMeters >= maxDistance) {
            return 1.0;
        }
        double normalized = (distanceMeters - referenceDistance) / (maxDistance - referenceDistance);
        return normalized;
    }
}
