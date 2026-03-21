package com.benesquivelmusic.daw.core.audio;

import java.util.Arrays;
import java.util.Objects;

/**
 * A multi-channel audio buffer holding sample data.
 *
 * <p>Buffers are indexed as {@code [channel][frame]}. Sample values are
 * expected to be in the range {@code [-1.0f, 1.0f]}.</p>
 */
public final class AudioBuffer {

    private final float[][] data;
    private final int channels;
    private final int frames;

    /**
     * Creates a new audio buffer with the specified dimensions, initialized to silence.
     *
     * @param channels number of channels
     * @param frames   number of sample frames
     */
    public AudioBuffer(int channels, int frames) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (frames <= 0) {
            throw new IllegalArgumentException("frames must be positive: " + frames);
        }
        this.channels = channels;
        this.frames = frames;
        this.data = new float[channels][frames];
    }

    /**
     * Returns the sample value at the given channel and frame index.
     *
     * @param channel the channel index
     * @param frame   the frame index
     * @return the sample value
     */
    public float getSample(int channel, int frame) {
        return data[channel][frame];
    }

    /**
     * Sets the sample value at the given channel and frame index.
     *
     * @param channel the channel index
     * @param frame   the frame index
     * @param value   the sample value
     */
    public void setSample(int channel, int frame, float value) {
        data[channel][frame] = value;
    }

    /**
     * Returns the raw data array for the specified channel.
     *
     * @param channel the channel index
     * @return the sample data for the channel
     */
    public float[] getChannelData(int channel) {
        return data[channel];
    }

    /**
     * Returns the underlying data array indexed as {@code [channel][frame]}.
     *
     * @return the raw buffer data
     */
    public float[][] getData() {
        return data;
    }

    /** Returns the number of channels. */
    public int getChannels() {
        return channels;
    }

    /** Returns the number of sample frames. */
    public int getFrames() {
        return frames;
    }

    /** Fills the entire buffer with silence (zeros). */
    public void clear() {
        for (float[] channel : data) {
            Arrays.fill(channel, 0.0f);
        }
    }
}
