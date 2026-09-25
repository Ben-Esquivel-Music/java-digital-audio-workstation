package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

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
 * <p>Delay buffers are prepared by {@link #recalculate} on the control thread
 * or by a monitor of explicitly thread-safe dynamic latency sources, so that {@link #applyToChannel} and
 * {@link #applyToReturnBus} perform zero heap allocations on the audio
 * thread. The monitor starts only for dynamic sources and stops when they
 * leave the graph or {@link #close()} retires the project. Static/native
 * processor latency is queried only during control-thread recalculation.</p>
 */
public final class PluginDelayCompensation implements AutoCloseable {

    private volatile CompensationState state = CompensationState.EMPTY;
    private List<MixerChannel> channels = List.of();
    private List<MixerChannel> returnBuses = List.of();
    private List<EffectsChain.LatencySnapshot> channelSources = List.of();
    private List<EffectsChain.LatencySnapshot> returnBusSources = List.of();
    private EffectsChain.LatencySnapshot masterSource;
    private int audioChannels;
    private Thread latencyWatcher;
    private boolean closed;

    /** Latency of the serial master insert chain, never a parallel compensation delay. */
    @RealTimeSafe
    public int getMasterLatencySamples() {
        return state.masterLatencySamples;
    }

    /**
     * Immutable snapshot of all compensation data. Published atomically
     * via a volatile write so the audio thread always sees a consistent view.
     */
    private record CompensationState(
            CompensationDelay[] channelDelays,
            CompensationDelay[] returnBusDelays,
            CompensationDelay[] directOutputDelays,
            int masterLatencySamples,
            int maxLatencySamples,
            int[] channelLatencies,
            int[] returnBusLatencies,
            List<MixerChannel> channels,
            List<MixerChannel> returnBuses,
            int audioChannels
    ) {
        static final CompensationState EMPTY = new CompensationState(
                new CompensationDelay[0],
                new CompensationDelay[0],
                new CompensationDelay[0],
                0, 0,
                new int[0],
                new int[0], List.of(), List.of(), 0
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
    public synchronized void recalculate(List<MixerChannel> channels,
                            List<MixerChannel> returnBuses,
                            int audioChannels) {
        recalculate(channels, returnBuses, null, audioChannels);
    }

    /** Master insert latency is serial after the aligned channel/return sum. */
    public synchronized void recalculate(List<MixerChannel> channels,
                            List<MixerChannel> returnBuses, MixerChannel master,
                            int audioChannels) {
        if (closed) return;
        this.channels = List.copyOf(channels);
        this.returnBuses = List.copyOf(returnBuses);
        this.audioChannels = audioChannels;
        masterSource = master != null ? master.getEffectsChain().captureLatency() : null;
        channelSources = channels.stream().map(channel -> channel.getEffectsChain().captureLatency()).toList();
        returnBusSources = returnBuses.stream().map(channel -> channel.getEffectsChain().captureLatency()).toList();
        rebuildCompensation(false);
        boolean hasDynamicLatency = channelSources.stream().anyMatch(source -> !source.dynamic().isEmpty())
                || returnBusSources.stream().anyMatch(source -> !source.dynamic().isEmpty())
                || masterSource != null && !masterSource.dynamic().isEmpty();
        if (hasDynamicLatency && latencyWatcher == null) {
            var reference = new WeakReference<>(this);
            // JEP 444 (final since Java 21): polling and buffer allocation stay off RT.
            latencyWatcher = Thread.ofVirtual().name("plugin-latency-refresh")
                    .unstarted(() -> watchLatencies(reference));
            latencyWatcher.start();
        } else if (!hasDynamicLatency) {
            stopLatencyWatcher();
        }
    }

    private static void watchLatencies(WeakReference<PluginDelayCompensation> reference) {
        while (!Thread.currentThread().isInterrupted()) {
            PluginDelayCompensation compensation = reference.get();
            if (compensation == null || !compensation.refreshFromWatcher()) return;
            compensation = null;
            LockSupport.parkNanos(5_000_000L);
        }
    }

    private synchronized boolean refreshFromWatcher() {
        if (closed || latencyWatcher != Thread.currentThread()) return false;
        rebuildCompensation(true);
        return true;
    }

    /** Refreshes live latency synchronously before a non-real-time render starts. */
    public synchronized void refreshLatencies() {
        if (!closed) rebuildCompensation(true);
    }

    private void rebuildCompensation(boolean onlyIfChanged) {
        int masterLatencySamples = masterSource != null ? masterSource.samples() : 0;
        if (onlyIfChanged && masterLatencySamples == state.masterLatencySamples && !latenciesChanged()) return;
        int channelCount = channels.size();
        int returnBusCount = returnBuses.size();

        int[] channelLatencies = new int[channelCount];
        int[] returnBusLatencies = new int[returnBusCount];

        // Calculate per-channel latency
        int maxLatency = 0;
        for (int i = 0; i < channelCount; i++) {
            int latency = channelSources.get(i).samples();
            channelLatencies[i] = latency;
            maxLatency = Math.max(maxLatency, latency);
        }

        // Include return bus latencies in the max calculation
        for (int i = 0; i < returnBusCount; i++) {
            int latency = returnBusSources.get(i).samples();
            returnBusLatencies[i] = latency;
            maxLatency = Math.max(maxLatency, latency);
        }

        // Create compensation delays
        CompensationDelay[] channelDelays = new CompensationDelay[channelCount];
        for (int i = 0; i < channelCount; i++) {
            int compensationNeeded = maxLatency - channelLatencies[i];
            channelDelays[i] = onlyIfChanged
                    ? reuseDelay(channels.get(i), compensationNeeded, state.channels, state.channelDelays)
                    : new CompensationDelay(audioChannels, compensationNeeded);
        }

        CompensationDelay[] returnBusDelays = new CompensationDelay[returnBusCount];
        for (int i = 0; i < returnBusCount; i++) {
            int compensationNeeded = maxLatency - returnBusLatencies[i];
            returnBusDelays[i] = onlyIfChanged
                    ? reuseDelay(returnBuses.get(i), compensationNeeded, state.returnBuses, state.returnBusDelays)
                    : new CompensationDelay(audioChannels, compensationNeeded);
        }

        CompensationDelay[] directOutputDelays = new CompensationDelay[channelCount];
        for (int i = 0; i < channelCount; i++) {
            directOutputDelays[i] = onlyIfChanged
                    ? reuseDelay(channels.get(i), masterLatencySamples, state.channels, state.directOutputDelays)
                    : new CompensationDelay(audioChannels, masterLatencySamples);
        }

        // Publish atomically
        state = new CompensationState(
                channelDelays,
                returnBusDelays,
                directOutputDelays,
                masterLatencySamples,
                maxLatency,
                channelLatencies,
                returnBusLatencies, channels, returnBuses, audioChannels
        );
    }

    private boolean latenciesChanged() {
        for (int i = 0; i < channels.size(); i++) {
            if (channelSources.get(i).samples() != state.channelLatencies[i]) {
                return true;
            }
        }
        for (int i = 0; i < returnBuses.size(); i++) {
            if (returnBusSources.get(i).samples() != state.returnBusLatencies[i]) {
                return true;
            }
        }
        return false;
    }

    private CompensationDelay reuseDelay(MixerChannel channel, int samples,
                                          List<MixerChannel> previousChannels, CompensationDelay[] previousDelays) {
        if (audioChannels == state.audioChannels) {
            for (int index = 0; index < previousChannels.size(); index++) {
                if (previousChannels.get(index) == channel && previousDelays[index].getDelaySamples() == samples) {
                    return previousDelays[index];
                }
            }
        }
        return new CompensationDelay(audioChannels, samples);
    }

    private void stopLatencyWatcher() {
        if (latencyWatcher != null) {
            latencyWatcher.interrupt();
            latencyWatcher = null;
        }
    }

    /** Releases the monitor before the project's processors are retired. */
    @Override
    public synchronized void close() {
        closed = true;
        stopLatencyWatcher();
        channels = List.of();
        returnBuses = List.of();
        channelSources = List.of();
        returnBusSources = List.of();
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
        if (channelIndex >= 0 && channelIndex < s.channelDelays.length) {
            s.channelDelays[channelIndex].process(buffer, numFrames);
        }
    }

    /** Matches the master insert delay on channels routed directly to hardware outputs. */
    @RealTimeSafe
    public void applyToDirectOutput(int channelIndex, float[][] buffer, int numFrames) {
        CompensationState captured = state;
        if (channelIndex >= 0 && channelIndex < captured.directOutputDelays.length) {
            captured.directOutputDelays[channelIndex].process(buffer, numFrames);
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
        if (returnBusIndex >= 0 && returnBusIndex < s.returnBusDelays.length) {
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
