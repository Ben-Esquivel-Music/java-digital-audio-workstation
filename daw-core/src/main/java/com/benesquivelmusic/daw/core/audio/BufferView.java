package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

/**
 * Record accessors for viewing a pooled audio buffer at either
 * 32-bit {@code float} or 64-bit {@code double} precision.
 *
 * <p>Both views are thin, zero-overhead record wrappers that hold a
 * reference to the underlying buffer storage; they do not copy sample data
 * and are real-time safe to construct on the audio thread.</p>
 *
 * <p>This mirrors the precision split required by the DAW's 64-bit
 * internal mix bus: summing stages acquire a {@link DoubleBufferView},
 * while legacy float-only plugin I/O acquires a {@link FloatBufferView}
 * from the same pool.</p>
 *
 * @see com.benesquivelmusic.daw.sdk.audio.MixPrecision
 */
public final class BufferView {

    private BufferView() {
        // non-instantiable — contains record view types only
    }

    /**
     * A 32-bit single-precision view over an {@link AudioBuffer}.
     *
     * @param channels the number of channels
     * @param frames   the number of sample frames
     * @param data     the raw storage, indexed {@code [channel][frame]}
     */
    @RealTimeSafe
    public record FloatBufferView(int channels, int frames, float[][] data) {
        /** Creates a view from an existing {@link AudioBuffer}. */
        public static FloatBufferView of(AudioBuffer buffer) {
            return new FloatBufferView(buffer.getChannels(), buffer.getFrames(), buffer.getData());
        }
    }

    /**
     * A 64-bit double-precision view over a {@link DoubleAudioBuffer}.
     *
     * @param channels the number of channels
     * @param frames   the number of sample frames
     * @param data     the raw storage, indexed {@code [channel][frame]}
     */
    @RealTimeSafe
    public record DoubleBufferView(int channels, int frames, double[][] data) {
        /** Creates a view from an existing {@link DoubleAudioBuffer}. */
        public static DoubleBufferView of(DoubleAudioBuffer buffer) {
            return new DoubleBufferView(buffer.getChannels(), buffer.getFrames(), buffer.getData());
        }
    }
}
