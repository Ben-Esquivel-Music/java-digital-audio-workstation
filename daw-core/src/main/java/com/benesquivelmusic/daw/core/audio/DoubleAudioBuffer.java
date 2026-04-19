package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

import java.util.Arrays;

/**
 * A multi-channel audio buffer holding 64-bit double-precision sample data.
 *
 * <p>Buffers are indexed as {@code [channel][frame]}. Sample values are
 * expected to be in the range {@code [-1.0, 1.0]}.</p>
 *
 * <p>This is the double-precision counterpart to {@link AudioBuffer} and is
 * used by the 64-bit internal mix bus. All read/write operations on this
 * buffer are allocation-free and safe to call on the real-time audio
 * thread.</p>
 *
 * @see AudioBuffer
 * @see com.benesquivelmusic.daw.sdk.audio.MixPrecision
 */
@RealTimeSafe
public final class DoubleAudioBuffer {

    private final double[][] data;
    private final int channels;
    private final int frames;

    /**
     * Creates a new double-precision audio buffer with the specified dimensions,
     * initialized to silence.
     *
     * @param channels number of channels
     * @param frames   number of sample frames
     */
    public DoubleAudioBuffer(int channels, int frames) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (frames <= 0) {
            throw new IllegalArgumentException("frames must be positive: " + frames);
        }
        this.channels = channels;
        this.frames = frames;
        this.data = new double[channels][frames];
    }

    /** Returns the sample value at the given channel and frame index. */
    public double getSample(int channel, int frame) {
        return data[channel][frame];
    }

    /** Sets the sample value at the given channel and frame index. */
    public void setSample(int channel, int frame, double value) {
        data[channel][frame] = value;
    }

    /** Returns the raw data array for the specified channel. */
    public double[] getChannelData(int channel) {
        return data[channel];
    }

    /** Returns the underlying data array indexed as {@code [channel][frame]}. */
    public double[][] getData() {
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
        for (double[] channel : data) {
            Arrays.fill(channel, 0.0);
        }
    }
}
