package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

import java.util.List;

/**
 * Manages plugin delay compensation (PDC) for a set of mixer channels and
 * return buses.
 *
 * <p>Audio processors introduce latency — linear-phase EQs, look-ahead
 * compressors, oversampled effects, and convolution reverbs all buffer
 * samples internally before producing output. When multiple channels have
 * different total insert-chain latencies, their audio arrives at the
 * summing bus at different times, causing phase smearing and comb filtering.</p>
 *
 * <p>This class calculates the maximum latency across all active channels
 * (including return buses), then creates a {@link CompensationDelay} for each
 * channel whose latency is less than the maximum. The compensating delay
 * equals the difference between the maximum and the channel's own latency,
 * aligning all channels at the summing bus.</p>
 *
 * <p>All delay buffers are pre-allocated during {@link #recalculate} (called
 * from the UI thread), so that {@link #applyToChannel} and
 * {@link #applyToReturnBus} perform zero heap allocations on the audio
 * thread.</p>
 */
public final class PluginDelayCompensation {

    private volatile CompensationState state = CompensationState.EMPTY;

    /**
     * Immutable snapshot of all compensation data. Published atomically
     * via a volatile write so the audio thread always sees a consistent view.
     */
    private record CompensationState(
            CompensationDelay[] channelDelays,
            CompensationDelay[] returnBusDelays,
            int maxLatencySamples,
            int[] channelLatencies,
            int[] returnBusLatencies
    ) {
        static final CompensationState EMPTY = new CompensationState(
                new CompensationDelay[0],
                new CompensationDelay[0],
                0,
                new int[0],
                new int[0]
        );
    }

    /**
     * Recalculates delay compensation for all channels and return buses.
     *
     * <p>This method must be called from a non-audio thread (e.g., the UI
     * thread) whenever insert effects are added, removed, reordered, or
     * bypassed. It pre-allocates all delay buffers so that the audio thread
     * can use them without allocation.</p>
     *
     * @param channels      the mixer channels
     * @param returnBuses   the return buses
     * @param audioChannels the number of audio channels (e.g., 2 for stereo)
     */
    public void recalculate(List<MixerChannel> channels,
                            List<MixerChannel> returnBuses,
                            int audioChannels) {
        int channelCount = channels.size();
        int returnBusCount = returnBuses.size();

        int[] channelLatencies = new int[channelCount];
        int[] returnBusLatencies = new int[returnBusCount];

        // Calculate per-channel latency
        int maxLatency = 0;
        for (int i = 0; i < channelCount; i++) {
            int latency = channels.get(i).getEffectsChain().getTotalLatencySamples();
            channelLatencies[i] = latency;
            maxLatency = Math.max(maxLatency, latency);
        }

        // Include return bus latencies in the max calculation
        for (int i = 0; i < returnBusCount; i++) {
            int latency = returnBuses.get(i).getEffectsChain().getTotalLatencySamples();
            returnBusLatencies[i] = latency;
            maxLatency = Math.max(maxLatency, latency);
        }

        // Create compensation delays
        CompensationDelay[] channelDelays = new CompensationDelay[channelCount];
        for (int i = 0; i < channelCount; i++) {
            int compensationNeeded = maxLatency - channelLatencies[i];
            channelDelays[i] = new CompensationDelay(audioChannels, compensationNeeded);
        }

        CompensationDelay[] returnBusDelays = new CompensationDelay[returnBusCount];
        for (int i = 0; i < returnBusCount; i++) {
            int compensationNeeded = maxLatency - returnBusLatencies[i];
            returnBusDelays[i] = new CompensationDelay(audioChannels, compensationNeeded);
        }

        // Publish atomically
        state = new CompensationState(
                channelDelays,
                returnBusDelays,
                maxLatency,
                channelLatencies,
                returnBusLatencies
        );
    }

    /**
     * Applies compensation delay to a channel's audio buffer in-place.
     *
     * <p>Call this after the channel's insert effects have been applied
     * but before summing into the master bus.</p>
     *
     * @param channelIndex the index of the channel
     * @param buffer       the audio buffer {@code [audioChannel][frame]}
     * @param numFrames    the number of frames to process
     */
    @RealTimeSafe
    public void applyToChannel(int channelIndex, float[][] buffer, int numFrames) {
        CompensationState s = state;
        if (channelIndex < s.channelDelays.length) {
            s.channelDelays[channelIndex].process(buffer, numFrames);
        }
    }

    /**
     * Applies compensation delay to a return bus's audio buffer in-place.
     *
     * @param returnBusIndex the index of the return bus
     * @param buffer         the audio buffer {@code [audioChannel][frame]}
     * @param numFrames      the number of frames to process
     */
    @RealTimeSafe
    public void applyToReturnBus(int returnBusIndex, float[][] buffer, int numFrames) {
        CompensationState s = state;
        if (returnBusIndex < s.returnBusDelays.length) {
            s.returnBusDelays[returnBusIndex].process(buffer, numFrames);
        }
    }

    /**
     * Returns the maximum latency across all channels and return buses,
     * in samples. This is the total system latency that the transport
     * should offset for playback alignment.
     *
     * @return the maximum latency in sample frames
     */
    public int getMaxLatencySamples() {
        return state.maxLatencySamples;
    }

    /**
     * Returns the insert chain latency for the specified channel, in samples.
     *
     * @param channelIndex the channel index
     * @return the channel's insert chain latency, or 0 if the index is out of range
     */
    public int getChannelLatencySamples(int channelIndex) {
        CompensationState s = state;
        if (channelIndex >= 0 && channelIndex < s.channelLatencies.length) {
            return s.channelLatencies[channelIndex];
        }
        return 0;
    }

    /**
     * Returns the compensation delay applied to the specified channel, in samples.
     *
     * @param channelIndex the channel index
     * @return the compensation delay, or 0 if the index is out of range
     */
    public int getChannelCompensationSamples(int channelIndex) {
        CompensationState s = state;
        if (channelIndex >= 0 && channelIndex < s.channelDelays.length) {
            return s.channelDelays[channelIndex].getDelaySamples();
        }
        return 0;
    }

    /**
     * Returns the insert chain latency for the specified return bus, in samples.
     *
     * @param returnBusIndex the return bus index
     * @return the return bus's insert chain latency, or 0 if the index is out of range
     */
    public int getReturnBusLatencySamples(int returnBusIndex) {
        CompensationState s = state;
        if (returnBusIndex >= 0 && returnBusIndex < s.returnBusLatencies.length) {
            return s.returnBusLatencies[returnBusIndex];
        }
        return 0;
    }

    /**
     * Resets all compensation delays (clears ring buffers to silence).
     * Call this when the transport is stopped or repositioned.
     */
    public void reset() {
        CompensationState s = state;
        for (CompensationDelay delay : s.channelDelays) {
            delay.reset();
        }
        for (CompensationDelay delay : s.returnBusDelays) {
            delay.reset();
        }
    }
}
