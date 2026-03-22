package com.benesquivelmusic.daw.sdk.spatial;

import java.util.List;
import java.util.Objects;

/**
 * Immutable dataset of Head-Related Transfer Function (HRTF) measurements.
 *
 * <p>Contains impulse responses for each measured source direction, for two
 * receivers (left ear = index 0, right ear = index 1). This data is
 * typically loaded from a SOFA file (AES69 format).</p>
 *
 * @param profileName      human-readable name of the HRTF profile
 * @param sampleRate       sampling rate of the impulse responses in Hz
 * @param sourcePositions  list of measured source directions (size = M)
 * @param impulseResponses HRIR data indexed as {@code [measurement][receiver][sample]};
 *                         dimensions are {@code [M][2][N]}
 * @param delays           interaural time difference data indexed as
 *                         {@code [measurement][receiver]}; dimensions are {@code [M][2]},
 *                         expressed in fractional samples
 */
public record HrtfData(
        String profileName,
        double sampleRate,
        List<SphericalCoordinate> sourcePositions,
        float[][][] impulseResponses,
        float[][] delays
) {

    public HrtfData {
        Objects.requireNonNull(profileName, "profileName must not be null");
        Objects.requireNonNull(sourcePositions, "sourcePositions must not be null");
        Objects.requireNonNull(impulseResponses, "impulseResponses must not be null");
        Objects.requireNonNull(delays, "delays must not be null");
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (sourcePositions.isEmpty()) {
            throw new IllegalArgumentException("sourcePositions must not be empty");
        }
        if (impulseResponses.length != sourcePositions.size()) {
            throw new IllegalArgumentException(
                    "impulseResponses length (" + impulseResponses.length
                            + ") must match sourcePositions size (" + sourcePositions.size() + ")");
        }
        if (delays.length != sourcePositions.size()) {
            throw new IllegalArgumentException(
                    "delays length (" + delays.length
                            + ") must match sourcePositions size (" + sourcePositions.size() + ")");
        }
        sourcePositions = List.copyOf(sourcePositions);
    }

    /** Returns the number of measured source positions (M). */
    public int measurementCount() {
        return sourcePositions.size();
    }

    /** Returns the number of receivers (typically 2 for left/right ears). */
    public int receiverCount() {
        return impulseResponses.length > 0 ? impulseResponses[0].length : 0;
    }

    /** Returns the length of each impulse response in samples (N). */
    public int irLength() {
        return (impulseResponses.length > 0 && impulseResponses[0].length > 0)
                ? impulseResponses[0][0].length
                : 0;
    }
}
