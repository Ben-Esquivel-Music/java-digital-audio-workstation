package com.benesquivelmusic.daw.sdk.spatial;

/**
 * Model for computing gain attenuation based on distance from the listener.
 *
 * <p>Implementations provide configurable rolloff curves such as inverse
 * square law, linear, or custom distance-to-gain mappings used by
 * {@link SpatialPanner} for realistic distance perception.</p>
 */
public interface DistanceAttenuationModel {

    /**
     * Computes the linear gain factor for a source at the given distance.
     *
     * @param distanceMeters the distance from the listener in meters (non-negative)
     * @return the gain factor in [0, 1], where 1 = no attenuation
     */
    double computeGain(double distanceMeters);

    /**
     * Returns the reference distance at which gain is unity (1.0).
     *
     * @return the reference distance in meters
     */
    double getReferenceDistance();

    /**
     * Returns the maximum distance beyond which the gain is zero.
     *
     * @return the maximum distance in meters
     */
    double getMaxDistance();

    /**
     * Computes a high-frequency rolloff factor for distance-based spectral filtering.
     *
     * <p>At greater distances, high frequencies are naturally attenuated by air
     * absorption. This factor can be used to control a low-pass filter cutoff.</p>
     *
     * @param distanceMeters the distance from the listener in meters
     * @return a factor in [0, 1], where 1 = no HF rolloff, 0 = full HF rolloff
     */
    double computeHighFrequencyRolloff(double distanceMeters);

    /**
     * Computes a reverb send level based on distance.
     *
     * <p>Sources further from the listener should produce a higher ratio
     * of reflected (wet) to direct (dry) sound.</p>
     *
     * @param distanceMeters the distance from the listener in meters
     * @return the reverb send level in [0, 1]
     */
    double computeReverbSend(double distanceMeters);
}
