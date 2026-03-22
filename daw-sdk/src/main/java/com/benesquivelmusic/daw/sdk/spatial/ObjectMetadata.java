package com.benesquivelmusic.daw.sdk.spatial;

/**
 * Immutable spatial metadata for an audio object in object-based mixing.
 *
 * <p>Carries per-object 3D position, size, and gain metadata as defined
 * by the Dolby Atmos and ADM (Audio Definition Model, ITU-R BS.2076)
 * specifications. Each audio object has a position in 3D space, a spread
 * parameter controlling apparent source size, and a gain value.</p>
 *
 * <p>Position uses normalized Cartesian coordinates where:</p>
 * <ul>
 *   <li>{@code x}: left (−1) to right (+1)</li>
 *   <li>{@code y}: back (−1) to front (+1)</li>
 *   <li>{@code z}: bottom (−1) to top (+1)</li>
 * </ul>
 *
 * @param x      horizontal position [−1.0, +1.0] (left to right)
 * @param y      depth position [−1.0, +1.0] (back to front)
 * @param z      vertical position [−1.0, +1.0] (bottom to top)
 * @param size   apparent source size [0.0, 1.0] (point to fully diffuse)
 * @param gain   object gain in linear scale [0.0, 1.0]
 */
public record ObjectMetadata(double x, double y, double z, double size, double gain) {

    /** Default metadata — centered position, point source, unity gain. */
    public static final ObjectMetadata DEFAULT = new ObjectMetadata(0.0, 0.0, 0.0, 0.0, 1.0);

    public ObjectMetadata {
        if (x < -1.0 || x > 1.0) {
            throw new IllegalArgumentException("x must be in [−1.0, +1.0]: " + x);
        }
        if (y < -1.0 || y > 1.0) {
            throw new IllegalArgumentException("y must be in [−1.0, +1.0]: " + y);
        }
        if (z < -1.0 || z > 1.0) {
            throw new IllegalArgumentException("z must be in [−1.0, +1.0]: " + z);
        }
        if (size < 0.0 || size > 1.0) {
            throw new IllegalArgumentException("size must be in [0.0, 1.0]: " + size);
        }
        if (gain < 0.0 || gain > 1.0) {
            throw new IllegalArgumentException("gain must be in [0.0, 1.0]: " + gain);
        }
    }

    /**
     * Returns a new {@code ObjectMetadata} with the position updated.
     *
     * @param newX horizontal position
     * @param newY depth position
     * @param newZ vertical position
     * @return a new metadata instance with updated position
     */
    public ObjectMetadata withPosition(double newX, double newY, double newZ) {
        return new ObjectMetadata(newX, newY, newZ, size, gain);
    }

    /**
     * Returns a new {@code ObjectMetadata} with the gain updated.
     *
     * @param newGain the new gain value [0.0, 1.0]
     * @return a new metadata instance with updated gain
     */
    public ObjectMetadata withGain(double newGain) {
        return new ObjectMetadata(x, y, z, size, newGain);
    }
}
