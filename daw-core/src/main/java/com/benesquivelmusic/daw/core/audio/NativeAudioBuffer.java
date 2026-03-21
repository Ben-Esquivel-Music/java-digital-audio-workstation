package com.benesquivelmusic.daw.core.audio;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * A multi-channel audio buffer backed by off-heap memory via the
 * Foreign Function &amp; Memory API (JEP 454, final in JDK 22).
 *
 * <p>Uses {@link MemorySegment} for zero-copy, GC-pressure-free audio
 * storage — ideal for long-running recording sessions where large amounts
 * of audio data must be held without triggering garbage collection pauses
 * on the real-time audio thread.</p>
 *
 * <p>Implements {@link AutoCloseable}; the underlying native memory is
 * released when the buffer is closed.</p>
 */
public final class NativeAudioBuffer implements AutoCloseable {

    private final Arena arena;
    private final MemorySegment segment;
    private final int channels;
    private final int frames;
    private final long channelStrideBytes;

    /**
     * Allocates a new native audio buffer.
     *
     * @param channels number of audio channels
     * @param frames   number of sample frames per channel
     */
    public NativeAudioBuffer(int channels, int frames) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (frames <= 0) {
            throw new IllegalArgumentException("frames must be positive: " + frames);
        }
        this.channels = channels;
        this.frames = frames;
        this.channelStrideBytes = (long) frames * Float.BYTES;

        long totalBytes = (long) channels * channelStrideBytes;
        this.arena = Arena.ofConfined();
        this.segment = arena.allocate(totalBytes, Float.BYTES);
        segment.fill((byte) 0);
    }

    /**
     * Returns the sample value at the given channel and frame.
     *
     * @param channel the channel index
     * @param frame   the frame index
     * @return the sample value
     */
    public float getSample(int channel, int frame) {
        checkBounds(channel, frame);
        long offset = (long) channel * channelStrideBytes + (long) frame * Float.BYTES;
        return segment.get(ValueLayout.JAVA_FLOAT, offset);
    }

    /**
     * Sets the sample value at the given channel and frame.
     *
     * @param channel the channel index
     * @param frame   the frame index
     * @param value   the sample value
     */
    public void setSample(int channel, int frame, float value) {
        checkBounds(channel, frame);
        long offset = (long) channel * channelStrideBytes + (long) frame * Float.BYTES;
        segment.set(ValueLayout.JAVA_FLOAT, offset, value);
    }

    /**
     * Copies all sample data for the given channel into a Java float array.
     *
     * @param channel the channel index
     * @return a new float array with the channel's sample data
     */
    public float[] getChannelData(int channel) {
        if (channel < 0 || channel >= channels) {
            throw new IndexOutOfBoundsException("channel: " + channel);
        }
        long offset = (long) channel * channelStrideBytes;
        MemorySegment channelSlice = segment.asSlice(offset, channelStrideBytes);
        return channelSlice.toArray(ValueLayout.JAVA_FLOAT);
    }

    /**
     * Writes sample data from a Java float array into the given channel.
     *
     * @param channel the channel index
     * @param data    the sample data (must have length &ge; {@link #getFrames()})
     */
    public void setChannelData(int channel, float[] data) {
        if (channel < 0 || channel >= channels) {
            throw new IndexOutOfBoundsException("channel: " + channel);
        }
        if (data.length < frames) {
            throw new IllegalArgumentException(
                    "data length " + data.length + " is less than frames " + frames);
        }
        long offset = (long) channel * channelStrideBytes;
        MemorySegment.copy(data, 0, segment, ValueLayout.JAVA_FLOAT, offset, frames);
    }

    /** Fills the entire buffer with silence (zeros). */
    public void clear() {
        segment.fill((byte) 0);
    }

    /** Returns the number of channels. */
    public int getChannels() {
        return channels;
    }

    /** Returns the number of sample frames per channel. */
    public int getFrames() {
        return frames;
    }

    /** Returns the total size of the buffer in bytes. */
    public long sizeBytes() {
        return segment.byteSize();
    }

    /** Returns the underlying {@link MemorySegment} for advanced use. */
    public MemorySegment segment() {
        return segment;
    }

    @Override
    public void close() {
        arena.close();
    }

    private void checkBounds(int channel, int frame) {
        if (channel < 0 || channel >= channels) {
            throw new IndexOutOfBoundsException("channel: " + channel);
        }
        if (frame < 0 || frame >= frames) {
            throw new IndexOutOfBoundsException("frame: " + frame);
        }
    }
}
