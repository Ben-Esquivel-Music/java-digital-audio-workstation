package com.benesquivelmusic.daw.sdk.spatial;

import java.util.Objects;

/**
 * An Ambisonic bus carries a multi-channel Ambisonic signal through the mixer.
 *
 * <p>Each bus is identified by a name and carries signals in a specific
 * {@link AmbisonicFormat} (FOA = 4 channels, 2nd order = 9 channels,
 * 3rd order = 16 channels). The bus buffer is organized as
 * {@code float[channelCount][numFrames]}.</p>
 *
 * @param name   the display name of this bus (e.g. "Ambisonic Main")
 * @param format the Ambisonic format describing order and channel count
 */
public record AmbisonicBus(String name, AmbisonicFormat format) {

    public AmbisonicBus {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(format, "format must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    /**
     * Returns the number of audio channels carried by this bus.
     *
     * @return the channel count
     */
    public int channelCount() {
        return format.channelCount();
    }

    /**
     * Returns the Ambisonic order of this bus.
     *
     * @return the Ambisonic order
     */
    public AmbisonicOrder order() {
        return format.order();
    }

    /**
     * Allocates a new audio buffer for this bus.
     *
     * @param numFrames the number of sample frames
     * @return a zeroed buffer of size {@code [channelCount][numFrames]}
     */
    public float[][] allocateBuffer(int numFrames) {
        if (numFrames < 0) {
            throw new IllegalArgumentException("numFrames must be non-negative: " + numFrames);
        }
        return new float[channelCount()][numFrames];
    }
}
