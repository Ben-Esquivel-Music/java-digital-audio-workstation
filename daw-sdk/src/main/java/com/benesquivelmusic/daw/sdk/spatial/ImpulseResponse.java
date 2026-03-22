package com.benesquivelmusic.daw.sdk.spatial;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable representation of an acoustic impulse response.
 *
 * <p>An impulse response (IR) captures the acoustic characteristics of a
 * room — it is the signal that results when a perfect impulse (Dirac delta)
 * is emitted in the room and recorded at the listener position. The IR
 * can be convolved with dry audio to produce auralized (room-simulated)
 * output.</p>
 *
 * <p>Multi-channel IRs (e.g., stereo for binaural rendering) are stored
 * as separate per-channel sample arrays.</p>
 *
 * @param samples    the IR samples per channel, indexed as {@code [channel][sample]};
 *                   all channels must have the same length
 * @param sampleRate the sample rate in Hz
 */
public record ImpulseResponse(float[][] samples, int sampleRate) {

    public ImpulseResponse {
        Objects.requireNonNull(samples, "samples must not be null");
        if (samples.length == 0) {
            throw new IllegalArgumentException("samples must have at least one channel");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        int length = samples[0].length;
        if (length == 0) {
            throw new IllegalArgumentException("samples must not be empty");
        }
        for (int ch = 1; ch < samples.length; ch++) {
            if (samples[ch].length != length) {
                throw new IllegalArgumentException(
                        "all channels must have the same length; channel 0 has %d but channel %d has %d"
                                .formatted(length, ch, samples[ch].length));
            }
        }
        // Defensive copy
        float[][] copy = new float[samples.length][];
        for (int ch = 0; ch < samples.length; ch++) {
            copy[ch] = Arrays.copyOf(samples[ch], samples[ch].length);
        }
        samples = copy;
    }

    /**
     * Returns the number of channels in this impulse response.
     *
     * @return the channel count
     */
    public int channelCount() {
        return samples.length;
    }

    /**
     * Returns the number of samples per channel.
     *
     * @return the length in samples
     */
    public int lengthInSamples() {
        return samples[0].length;
    }

    /**
     * Returns the duration of the impulse response in seconds.
     *
     * @return the duration in seconds
     */
    public double durationSeconds() {
        return (double) lengthInSamples() / sampleRate;
    }
}
