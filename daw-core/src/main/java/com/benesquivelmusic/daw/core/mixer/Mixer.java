package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.audio.AudioGraphScheduler;
import com.benesquivelmusic.daw.core.audio.PluginDelayCompensation;
import com.benesquivelmusic.daw.core.automation.ReflectiveParameterBinder;
import com.benesquivelmusic.daw.core.plugin.PluginInvocationSupervisor;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.MixPrecision;
import com.benesquivelmusic.daw.sdk.audio.SidechainAwareProcessor;

import java.util.*;

/**
 * The DAW mixer manages a collection of {@link MixerChannel}s and produces
 * the final stereo mix routed to the master output.
 *
 * <p>The mixer supports multiple auxiliary return buses. Each track channel can
 * have {@link Send} objects that route audio to any return bus with independent
 * level and pre/post-fader mode. Return bus outputs are summed into the master
 * bus during mix-down.</p>
 *
 * <p>The mixer supports sidechain routing: when an insert slot on a channel
 * has a {@linkplain InsertSlot#getSidechainSource() sidechain source} configured
 * and the processor implements {@link SidechainAwareProcessor}, the source
 * channel's audio is passed as the detection input to that processor.</p>
 *
 * <p>The {@link #mixDown(float[][][], float[][], int)} method sums all channel
 * contributions into a single output buffer without allocations or locks —
 * safe to call on the real-time audio thread.</p>
 */
public final class Mixer {

    /** Maximum number of return buses supported by the mixer and audio engine. */
    public static final int MAX_RETURN_BUSES = 16;

    private final List<MixerChannel> channels = new ArrayList<>();
    private final List<MixerChannel> returnBuses = new ArrayList<>();
    private final MixerChannel masterChannel;
    private final PluginDelayCompensation delayCompensation = new PluginDelayCompensation();
    private final ReflectiveParameterBinder reflectiveParameterBinder = new ReflectiveParameterBinder();
    private int preparedAudioChannels;
    private float[][] scratchBufferA;
    private float[][] scratchBufferB;
    /**
     * Pre-allocated per-channel scratch buffers used to capture each
     * channel's <em>pre-insert</em> signal so that sends configured with
     * {@link SendTap#PRE_INSERTS} can tap audio before the insert chain has
     * run. The capture is performed in a sequential pre-pass (see
     * {@link #capturePreInsertsForActiveChannels}) <em>before</em> the
     * optional parallel insert pre-pass, so the captured signal remains
     * pre-insert regardless of whether the
     * {@link AudioGraphScheduler} ran inserts in parallel.
     *
     * <p>The outer array is grown lazily to {@code channelCount}; inner
     * buffers are allocated on demand only for channels that actually have
     * an active {@link SendTap#PRE_INSERTS} send (so channels without
     * pre-insert sends pay no allocation cost).</p>
     */
    private float[][][] preInsertScratchPerChannel = new float[0][][];
    /**
     * Parallel array tracking which channels have a captured pre-insert
     * buffer for the current block. Reset at the start of each
     * {@link #mixDown} invocation. Allows {@link #routeSends} to look up
     * the captured buffer without recomputing the gate.
     */
    private boolean[] preInsertCaptured = new boolean[0];
    /**
     * Optional multi-core graph scheduler. When non-null and the block size
     * meets the scheduler's parallel threshold, per-channel insert chains
     * without sidechain routing are dispatched across worker threads; the
     * summing, send-routing, and delay-compensation phases remain sequential
     * so output is bit-exact with the single-threaded path.
     */
    private AudioGraphScheduler graphScheduler;
    private PluginInvocationSupervisor pluginSupervisor;
    /**
     * Reusable flag array marking which channels had their insert chain
     * already applied by the parallel pre-pass. Lazily grown to the current
     * channel count; reset at the start of every {@link #mixDown} call to
     * preserve allocation-free real-time behavior.
     */
    private boolean[] insertsProcessedFlags = new boolean[0];

    /**
     * The numeric precision of the internal mix bus. {@link MixPrecision#DOUBLE_64}
     * (the default) sums channels and return buses in 64-bit double precision
     * before narrowing the final result to the output float buffer; this
     * matches the summing precision of every professional DAW and prevents
     * low-bit accumulation error on large sessions. {@link MixPrecision#FLOAT_32}
     * retains legacy 32-bit summation and is bit-exact with prior DAW
     * versions — useful for low-CPU machines or regression renders.
     */
    private MixPrecision mixPrecision = MixPrecision.DEFAULT;

    /**
     * Pre-allocated 64-bit summing accumulator reused on every
     * {@link #mixDown} invocation when {@link #mixPrecision} is
     * {@link MixPrecision#DOUBLE_64}. Lazily (re)sized to match the
     * current {@code outputBuffer} dimensions so real-time invocations
     * remain allocation-free after the first block.
     */
    private double[][] mixAccumulator = new double[0][0];

    /**
     * Pre-allocated 64-bit scratch buffer used when processing return bus
     * insert effects via {@code EffectsChain.processDouble()} under the
     * {@link MixPrecision#DOUBLE_64} path. Lazily grown on the first
     * block when the double bus is active.
     */
    private double[][] returnBusScratchDouble = new double[0][0];

    /** Creates a new mixer with an empty channel list, a default master channel, and a reverb return aux bus. */
    public Mixer() {
        this.masterChannel = new MixerChannel("Master");
        MixerChannel defaultReturn = new MixerChannel("Reverb Return");
        defaultReturn.setSoloSafe(true);
        defaultReturn.setOnEffectsChainChanged(this::recalculateDelayCompensation);
        returnBuses.add(defaultReturn);
    }

    /**
     * Returns the precision of the internal summing bus.
     *
     * @return the current mix precision (never {@code null})
     * @see MixPrecision
     */
    public MixPrecision getMixPrecision() {
        return mixPrecision;
    }

    /**
     * Sets the precision of the internal summing bus.
     *
     * <p>This call is safe to issue at any time; the next
     * {@link #mixDown} invocation will pick up the new precision.
     * Changing precision does not change plugin I/O — plugins continue
     * to process at their own declared precision via
     * {@link com.benesquivelmusic.daw.sdk.audio.AudioProcessor#supportsDouble()}.</p>
     *
     * @param mixPrecision the new mix precision (must not be {@code null})
     */
    public void setMixPrecision(MixPrecision mixPrecision) {
        this.mixPrecision = Objects.requireNonNull(mixPrecision, "mixPrecision must not be null");
    }

    /**
     * Installs a {@link PluginInvocationSupervisor} on every current channel
     * (regular, return bus, master) and remembers it so that channels added
     * later via {@link #addChannel(MixerChannel)} or
     * {@link #addReturnBus(String)} inherit it automatically. Passing
     * {@code null} clears the supervisor from all current channels and stops
     * newly-added channels from inheriting one until another supervisor is set.
     *
     * @param supervisor the supervisor to cascade, or {@code null} to clear
     *                   current channels and stop decorating newly-added channels
     */
    public void setPluginSupervisor(PluginInvocationSupervisor supervisor) {
        this.pluginSupervisor = supervisor;
        for (MixerChannel channel : channels) {
            setPluginSupervisorWithoutEffectsChainCallback(channel, supervisor);
        }
        for (MixerChannel returnBus : returnBuses) {
            setPluginSupervisorWithoutEffectsChainCallback(returnBus, supervisor);
        }
        if (masterChannel != null) {
            setPluginSupervisorWithoutEffectsChainCallback(masterChannel, supervisor);
        }
        recalculateDelayCompensation();
    }

    private void setPluginSupervisorWithoutEffectsChainCallback(
            MixerChannel channel,
            PluginInvocationSupervisor supervisor
    ) {
        channel.setOnEffectsChainChanged(null);
        channel.setPluginSupervisor(supervisor);
        channel.setOnEffectsChainChanged(this::recalculateDelayCompensation);
    }

    /**
     * Adds a new channel to the mixer.
     *
     * @param channel the channel to add
     */
    public void addChannel(MixerChannel channel) {
        Objects.requireNonNull(channel, "channel must not be null");
        channel.setOnEffectsChainChanged(this::recalculateDelayCompensation);
        if (pluginSupervisor != null) {
            channel.setPluginSupervisor(pluginSupervisor);
        }
        channels.add(channel);
        recalculateDelayCompensation();
    }

    /**
     * Removes a channel from the mixer.
     *
     * @param channel the channel to remove
     * @return {@code true} if the channel was removed
     */
    public boolean removeChannel(MixerChannel channel) {
        boolean removed = channels.remove(channel);
        if (removed) {
            channel.setOnEffectsChainChanged(null);
            reflectiveParameterBinder.forget(channel);
            recalculateDelayCompensation();
        }
        return removed;
    }

    /**
     * Returns an unmodifiable view of the current mixer channels.
     *
     * @return the list of channels
     */
    public List<MixerChannel> getChannels() {
        return Collections.unmodifiableList(channels);
    }

    /**
     * Returns the master output channel.
     *
     * @return the master channel
     */
    public MixerChannel getMasterChannel() {
        return masterChannel;
    }

    /**
     * Returns the shared auxiliary/return bus used for send effects (e.g., reverb).
     * This is the first return bus created by default.
     *
     * @return the aux bus channel
     */
    public MixerChannel getAuxBus() {
        return returnBuses.get(0);
    }

    /**
     * Returns an unmodifiable view of all return buses.
     *
     * @return the list of return buses
     */
    public List<MixerChannel> getReturnBuses() {
        return Collections.unmodifiableList(returnBuses);
    }

    /**
     * Adds a new return bus with the given name.
     *
     * @param name the display name for the return bus
     * @return the newly created return bus channel
     * @throws IllegalStateException if the maximum number of return buses has been reached
     */
    public MixerChannel addReturnBus(String name) {
        Objects.requireNonNull(name, "name must not be null");
        if (returnBuses.size() >= MAX_RETURN_BUSES) {
            throw new IllegalStateException(
                    "cannot exceed " + MAX_RETURN_BUSES + " return buses");
        }
        MixerChannel returnBus = new MixerChannel(name);
        returnBus.setSoloSafe(true);
        returnBus.setOnEffectsChainChanged(this::recalculateDelayCompensation);
        if (pluginSupervisor != null) {
            returnBus.setPluginSupervisor(pluginSupervisor);
        }
        returnBuses.add(returnBus);
        recalculateDelayCompensation();
        return returnBus;
    }

    /**
     * Adds an existing return bus channel to the mixer. This is used by undo
     * operations to re-add a previously removed return bus.
     *
     * @param returnBus the return bus to add
     */
    public void addReturnBus(MixerChannel returnBus) {
        Objects.requireNonNull(returnBus, "returnBus must not be null");
        if (!returnBuses.contains(returnBus)) {
            returnBus.setOnEffectsChainChanged(this::recalculateDelayCompensation);
            if (pluginSupervisor != null) {
                returnBus.setPluginSupervisor(pluginSupervisor);
            }
            returnBuses.add(returnBus);
            recalculateDelayCompensation();
        }
    }

    /**
     * Removes a return bus from the mixer. The sends on each channel that
     * target this bus are also removed.
     *
     * @param returnBus the return bus to remove
     * @return {@code true} if the return bus was removed
     */
    public boolean removeReturnBus(MixerChannel returnBus) {
        boolean removed = returnBuses.remove(returnBus);
        if (removed) {
            returnBus.setOnEffectsChainChanged(null);
            for (MixerChannel channel : channels) {
                Send send = channel.getSendForTarget(returnBus);
                if (send != null) {
                    channel.removeSend(send);
                }
            }
            recalculateDelayCompensation();
        }
        return removed;
    }

    /**
     * Returns the total number of return buses.
     *
     * @return return bus count
     */
    public int getReturnBusCount() {
        return returnBuses.size();
    }

    /**
     * Resets the {@linkplain MixerChannel#isSoloSafe() solo-safe} flag of
     * every channel back to its default: return buses become solo-safe, while
     * regular track channels and the master are not. This is the maintenance
     * action exposed by the mixer's "Reset solo safe to defaults" menu item.
     */
    public void resetSoloSafeToDefaults() {
        for (MixerChannel channel : channels) {
            channel.setSoloSafe(false);
        }
        for (MixerChannel returnBus : returnBuses) {
            returnBus.setSoloSafe(true);
        }
        masterChannel.setSoloSafe(false);
    }

    /**
     * Moves a channel from one position to another in the channel list.
     *
     * @param fromIndex the current index of the channel to move
     * @param toIndex   the target index for the channel
     * @throws IndexOutOfBoundsException if either index is out of range
     */
    public void moveChannel(int fromIndex, int toIndex) {
        if (fromIndex < 0 || fromIndex >= channels.size()) {
            throw new IndexOutOfBoundsException("fromIndex out of range: " + fromIndex);
        }
        if (toIndex < 0 || toIndex >= channels.size()) {
            throw new IndexOutOfBoundsException("toIndex out of range: " + toIndex);
        }
        if (fromIndex == toIndex) {
            return;
        }
        MixerChannel channel = channels.remove(fromIndex);
        channels.add(toIndex, channel);
    }

    /**
     * Returns the total number of channels (excluding master).
     *
     * @return channel count
     */
    public int getChannelCount() {
        return channels.size();
    }

    /**
     * Pre-allocates intermediate buffers for every channel's effects chain so
     * that {@link #mixDown} remains zero-allocation on the audio thread.
     *
     * <p>Call this method when the audio engine starts or when the buffer size
     * changes. It also stores the dimensions on each channel so that adding or
     * removing insert effects automatically re-allocates.</p>
     *
     * @param audioChannels the number of audio channels (e.g., 2 for stereo)
     * @param blockSize     the number of sample frames per processing block
     */
    public void prepareForPlayback(int audioChannels, int blockSize) {
        this.preparedAudioChannels = audioChannels;
        this.scratchBufferA = new float[audioChannels][blockSize];
        this.scratchBufferB = new float[audioChannels][blockSize];
        // Lazily-grown per-channel pre-insert buffers; outer array sized in
        // mixDown when the channel count is known. Keep the placeholders
        // empty here so prepareForPlayback remains O(channels + returns).
        for (MixerChannel channel : channels) {
            channel.prepareEffectsChain(audioChannels, blockSize);
        }
        for (MixerChannel returnBus : returnBuses) {
            returnBus.prepareEffectsChain(audioChannels, blockSize);
        }
        masterChannel.prepareEffectsChain(audioChannels, blockSize);
        // Pre-allocate the parallel pre-pass flag array so the first mixDown
        // call is allocation-free (satisfies the @RealTimeSafe contract).
        this.insertsProcessedFlags = new boolean[channels.size()];
        recalculateDelayCompensation();
        rebindAllReflectiveParameterBindings();
    }

    /**
     * Sums all channel audio into the output buffer, applying per-channel
     * insert effects, volume, mute, and solo. This method is allocation-free
     * and lock-free when intermediate buffers have been pre-allocated via
     * {@link #prepareForPlayback(int, int)}.
     *
     * <p>The {@code channelBuffers} array must have one entry per mixer channel
     * (in the same order as {@link #getChannels()}), each sized
     * {@code [audioChannels][frames]}.</p>
     *
     * <p><strong>Note:</strong> Insert effects are applied in-place, so the
     * contents of {@code channelBuffers} will be modified after this call.</p>
     *
     * @param channelBuffers per-channel audio data {@code [mixerChannel][audioChannel][frame]}
     * @param outputBuffer   the destination output buffer {@code [audioChannel][frame]}
     * @param numFrames      the number of sample frames to mix
     */
    @RealTimeSafe
    public void mixDown(float[][][] channelBuffers, float[][] outputBuffer, int numFrames) {
        boolean useDouble = mixPrecision == MixPrecision.DOUBLE_64;
        double[][] acc = useDouble ? ensureAccumulator(outputBuffer.length, numFrames) : null;

        // Clear output (and double accumulator when the 64-bit mix bus is active)
        for (float[] ch : outputBuffer) {
            Arrays.fill(ch, 0, numFrames, 0.0f);
        }
        if (useDouble) {
            for (int ch = 0; ch < outputBuffer.length; ch++) {
                Arrays.fill(acc[ch], 0, numFrames, 0.0);
            }
        }

        boolean anySolo = false;
        for (MixerChannel channel : channels) {
            if (channel.isSolo()) {
                anySolo = true;
                break;
            }
        }

        int channelCount = Math.min(channels.size(), channelBuffers.length);
        for (int i = 0; i < channelCount; i++) {
            MixerChannel channel = channels.get(i);
            if (channel.isMuted()) {
                continue;
            }
            if (anySolo && !channel.isSolo() && !channel.isSoloSafe()) {
                continue;
            }

            float[][] src = channelBuffers[i];

            if (!channel.getEffectsChain().isEmpty()) {
                if (hasSidechainRouting(channel)) {
                    processInsertsWithSidechain(channel, src, channelBuffers, null, numFrames);
                } else {
                    channel.getEffectsChain().process(src, src, numFrames);
                }
            }

            // Apply plugin delay compensation to align this channel with
            // the highest-latency channel at the summing bus
            delayCompensation.applyToChannel(i, src, numFrames);

            // Skip channels routed to direct hardware outputs — their
            // processed audio remains in channelBuffers[i] for the
            // AudioEngine to write to the assigned output channels
            if (!channel.getOutputRouting().isMaster()) {
                continue;
            }

            if (useDouble) {
                sumChannelToOutputDouble(channel, src, acc, outputBuffer.length, numFrames);
            } else {
                sumChannelToOutput(channel, src, outputBuffer, numFrames);
            }
        }

        // Apply master volume
        float masterVolume = (float) masterChannel.getVolume();
        if (useDouble) {
            finalizeAccumulator(acc, outputBuffer, numFrames,
                    masterChannel.isMuted() ? 0.0 : masterChannel.getVolume());
            return;
        }
        if (!masterChannel.isMuted()) {
            for (float[] ch : outputBuffer) {
                for (int f = 0; f < numFrames; f++) {
                    ch[f] *= masterVolume;
                }
            }
        } else {
            for (float[] ch : outputBuffer) {
                Arrays.fill(ch, 0, numFrames, 0.0f);
            }
        }
    }

    /**
     * Sums all channel audio into the output buffer and routes send audio
     * into the auxiliary output buffer, applying per-channel insert effects,
     * volume, mute, solo, and send level. This method is allocation-free
     * and lock-free when intermediate buffers have been pre-allocated.
     *
     * <p>For each non-muted channel with a non-zero send level, the channel's
     * post-insert audio is scaled by the send level and summed into
     * {@code auxOutputBuffer}. The aux bus volume is applied to the final
     * aux output.</p>
     *
     * @param channelBuffers  per-channel audio data {@code [mixerChannel][audioChannel][frame]}
     * @param outputBuffer    the destination main output buffer {@code [audioChannel][frame]}
     * @param auxOutputBuffer the destination aux/send output buffer {@code [audioChannel][frame]}
     * @param numFrames       the number of sample frames to mix
     */
    @RealTimeSafe
    public void mixDown(float[][][] channelBuffers, float[][] outputBuffer,
                        float[][] auxOutputBuffer, int numFrames) {
        // Perform the main mix-down into outputBuffer
        mixDown(channelBuffers, outputBuffer, numFrames);

        // Clear aux output
        for (float[] ch : auxOutputBuffer) {
            Arrays.fill(ch, 0, numFrames, 0.0f);
        }

        boolean anySolo = false;
        for (MixerChannel channel : channels) {
            if (channel.isSolo()) {
                anySolo = true;
                break;
            }
        }

        MixerChannel auxBus = getAuxBus();

        // Route sends to aux bus
        int channelCount = Math.min(channels.size(), channelBuffers.length);
        for (int i = 0; i < channelCount; i++) {
            MixerChannel channel = channels.get(i);
            if (channel.isMuted()) {
                continue;
            }
            if (anySolo && !channel.isSolo() && !channel.isSoloSafe()) {
                continue;
            }

            float sendLevel = (float) channel.getSendLevel();
            if (sendLevel <= 0.0f) {
                continue;
            }

            float[][] src = channelBuffers[i];
            int audioChannels = Math.min(src.length, auxOutputBuffer.length);
            for (int ch = 0; ch < audioChannels; ch++) {
                for (int f = 0; f < numFrames; f++) {
                    auxOutputBuffer[ch][f] += src[ch][f] * sendLevel;
                }
            }
        }

        // Apply aux bus insert effects
        if (!auxBus.getEffectsChain().isEmpty()) {
            auxBus.getEffectsChain().process(auxOutputBuffer, auxOutputBuffer, numFrames);
        }

        // Apply delay compensation for the aux bus (return bus index 0)
        delayCompensation.applyToReturnBus(0, auxOutputBuffer, numFrames);

        // Apply aux bus volume
        float auxVolume = (float) auxBus.getVolume();
        if (!auxBus.isMuted()) {
            for (float[] ch : auxOutputBuffer) {
                for (int f = 0; f < numFrames; f++) {
                    ch[f] *= auxVolume;
                }
            }
        } else {
            for (float[] ch : auxOutputBuffer) {
                Arrays.fill(ch, 0, numFrames, 0.0f);
            }
        }
    }

    /**
     * Performs a full mix-down with multi-bus send routing. Each channel's
     * insert effects are applied in-place before volume, pan, and send
     * routing. Channel audio is summed into {@code outputBuffer}. Each
     * return bus receives send contributions from channels that have
     * {@link Send} objects targeting it. Return bus outputs are written to
     * {@code returnBuffers} and also summed into the main output (before
     * master volume).
     *
     * <p>Pre-fader sends tap the post-insert channel audio (independent of
     * channel volume). Post-fader sends tap the audio after applying the
     * channel volume.</p>
     *
     * @param channelBuffers per-channel audio data {@code [mixerChannel][audioChannel][frame]}
     * @param outputBuffer   the destination main output buffer {@code [audioChannel][frame]}
     * @param returnBuffers  per-return-bus output buffers {@code [returnBus][audioChannel][frame]}
     * @param numFrames      the number of sample frames to mix
     */
    @RealTimeSafe
    public void mixDown(float[][][] channelBuffers, float[][] outputBuffer,
                        float[][][] returnBuffers, int numFrames) {
        boolean useDouble = mixPrecision == MixPrecision.DOUBLE_64;
        double[][] acc = useDouble ? ensureAccumulator(outputBuffer.length, numFrames) : null;

        // Clear output (and double accumulator when the 64-bit mix bus is active)
        for (float[] ch : outputBuffer) {
            Arrays.fill(ch, 0, numFrames, 0.0f);
        }
        if (useDouble) {
            for (int ch = 0; ch < outputBuffer.length; ch++) {
                Arrays.fill(acc[ch], 0, numFrames, 0.0);
            }
        }

        // Clear all return buffers
        int returnBusCount = Math.min(returnBuses.size(), returnBuffers.length);
        for (int r = 0; r < returnBusCount; r++) {
            for (float[] ch : returnBuffers[r]) {
                Arrays.fill(ch, 0, numFrames, 0.0f);
            }
        }

        boolean anySolo = false;
        for (MixerChannel channel : channels) {
            if (channel.isSolo()) {
                anySolo = true;
                break;
            }
        }

        int channelCount = Math.min(channels.size(), channelBuffers.length);

        // Capture pre-insert signals BEFORE any insert processing runs (whether
        // sequential below or via the optional parallel scheduler) so that
        // SendTap.PRE_INSERTS sends always tap a truly dry signal.
        capturePreInsertsForActiveChannels(channelBuffers, channelCount, anySolo, numFrames);

        // Parallel pre-pass: dispatch insert-chain processing for channels
        // without sidechain routing to worker threads. The scheduler itself
        // falls back to no-op when the pool size is 1 or the block size is
        // below the parallel threshold — preserving bit-exact behavior with
        // the single-threaded path in both cases.
        boolean[] insertsDone = ensureInsertsProcessedFlags(channelCount);
        AudioGraphScheduler scheduler = this.graphScheduler;
        if (scheduler != null && channelCount >= 2) {
            scheduler.processInsertsParallel(channels, channelBuffers, numFrames,
                    anySolo, Mixer::hasSidechainRouting, insertsDone);
        }

        for (int i = 0; i < channelCount; i++) {
            MixerChannel channel = channels.get(i);
            if (channel.isMuted()) {
                continue;
            }
            if (anySolo && !channel.isSolo() && !channel.isSoloSafe()) {
                continue;
            }

            float[][] src = channelBuffers[i];

            // The pre-insert signal was captured up front; look it up here.
            float[][] preInsertSrc = preInsertSourceFor(i);

            if (!channel.getEffectsChain().isEmpty() && !insertsDone[i]) {
                if (hasSidechainRouting(channel)) {
                    processInsertsWithSidechain(channel, src, channelBuffers, returnBuffers, numFrames);
                } else {
                    channel.getEffectsChain().process(src, src, numFrames);
                }
            }

            float volume = (float) channel.getVolume();

            // Route sends to return buses BEFORE applying delay compensation
            // so that return bus paths are compensated independently
            routeSends(channel, preInsertSrc, src, volume,
                    returnBuffers, returnBusCount, numFrames);

            // Apply plugin delay compensation AFTER sends are tapped so
            // that the direct path is aligned at the summing bus
            delayCompensation.applyToChannel(i, src, numFrames);

            // Skip channels routed to direct hardware outputs
            if (!channel.getOutputRouting().isMaster()) {
                continue;
            }

            if (useDouble) {
                sumChannelToOutputDouble(channel, src, acc, outputBuffer.length, numFrames);
            } else {
                sumChannelToOutput(channel, src, outputBuffer, numFrames);
            }
        }

        // Process return bus effects, apply compensation, and sum into main output
        for (int r = 0; r < returnBusCount; r++) {
            MixerChannel returnBus = returnBuses.get(r);
            float[][] returnBuf = returnBuffers[r];

            // Apply return bus insert effects
            if (!returnBus.getEffectsChain().isEmpty()) {
                if (useDouble) {
                    // Process return bus effects in double precision: widen
                    // float→double, apply effects via processDouble, and let
                    // the accumulation loop below consume the double result.
                    double[][] dblBuf = ensureReturnBusScratchDouble(
                            returnBuf.length, numFrames);
                    for (int ch = 0; ch < returnBuf.length; ch++) {
                        for (int f = 0; f < numFrames; f++) {
                            dblBuf[ch][f] = returnBuf[ch][f];
                        }
                    }
                    returnBus.getEffectsChain().processDouble(dblBuf, dblBuf, numFrames);
                    // Narrow back to the float return buffer for PDC and
                    // any downstream consumer that reads returnBuffers[r].
                    for (int ch = 0; ch < returnBuf.length; ch++) {
                        for (int f = 0; f < numFrames; f++) {
                            returnBuf[ch][f] = (float) dblBuf[ch][f];
                        }
                    }
                } else {
                    returnBus.getEffectsChain().process(returnBuf, returnBuf, numFrames);
                }
            }

            // Apply delay compensation for the return bus
            delayCompensation.applyToReturnBus(r, returnBuf, numFrames);

            float returnVolume = (float) returnBus.getVolume();
            int returnAudioChannels = Math.min(returnBuf.length, outputBuffer.length);

            if (returnBus.isMuted()) {
                for (float[] ch : returnBuf) {
                    Arrays.fill(ch, 0, numFrames, 0.0f);
                }
            } else if (useDouble) {
                double returnVolumeD = returnBus.getVolume();
                for (int ch = 0; ch < returnAudioChannels; ch++) {
                    for (int f = 0; f < numFrames; f++) {
                        // Apply return-bus volume in 64-bit and sum into the
                        // double accumulator (keep returnBuf coherent by also
                        // writing back the scaled value as float).
                        double scaled = returnBuf[ch][f] * returnVolumeD;
                        returnBuf[ch][f] = (float) scaled;
                        acc[ch][f] += scaled;
                    }
                }
            } else {
                for (int ch = 0; ch < returnAudioChannels; ch++) {
                    for (int f = 0; f < numFrames; f++) {
                        returnBuf[ch][f] *= returnVolume;
                        outputBuffer[ch][f] += returnBuf[ch][f];
                    }
                }
            }
        }

        // Apply master volume
        float masterVolume = (float) masterChannel.getVolume();
        if (useDouble) {
            finalizeAccumulator(acc, outputBuffer, numFrames,
                    masterChannel.isMuted() ? 0.0 : masterChannel.getVolume());
            return;
        }
        if (!masterChannel.isMuted()) {
            for (float[] ch : outputBuffer) {
                for (int f = 0; f < numFrames; f++) {
                    ch[f] *= masterVolume;
                }
            }
        } else {
            for (float[] ch : outputBuffer) {
                Arrays.fill(ch, 0, numFrames, 0.0f);
            }
        }
    }

    /**
     * Sums all channel audio into the output buffer like
     * {@link #mixDown(float[][][], float[][], float[][][], int)}, but
     * additionally instruments per-track mixer processing (insert chain,
     * send routing, delay compensation, and summing) and feeds the
     * measurement to the supplied {@link TrackCpuBudgetEnforcer}.
     *
     * <p>The enforcer receives one
     * {@link TrackCpuBudgetEnforcer#recordTrackCpu(String, long)} call per
     * active track per block, using the track's stable id. This enables
     * per-track CPU budget evaluation and graceful degradation.</p>
     *
     * <p>Tracks are automatically registered (or re-registered) with the
     * enforcer using each mixer channel's
     * {@link MixerChannel#getCpuBudget() cpuBudget} property, ensuring
     * budgets take effect without requiring external registration.</p>
     *
     * <p>When an {@link AudioGraphScheduler} is configured, the parallel
     * insert-chain pre-pass runs before per-track timing begins, matching
     * the uninstrumented {@code mixDown} behavior. Per-track CPU
     * measurement still covers the sequential portion (send routing, delay
     * compensation, and summing) for each channel.</p>
     *
     * <p><strong>RT-safety note:</strong> this method acquires a lock inside
     * the enforcer for each {@code recordTrackCpu} and
     * {@code registerTrack} call. The enforcer pre-allocates internal
     * buffers to minimize GC pressure, but the lock acquisitions mean this
     * path is not fully lock-free.</p>
     *
     * @param channelBuffers    per-channel audio data
     * @param outputBuffer      the destination output buffer
     * @param returnBuffers     per-return-bus output buffers
     * @param numFrames         the number of sample frames to mix
     * @param tracks            the track list (for stable id lookup)
     * @param enforcer          the CPU budget enforcer (must not be null)
     */
    @RealTimeSafe
    public void mixDownInstrumented(float[][][] channelBuffers,
                                    float[][] outputBuffer,
                                    float[][][] returnBuffers,
                                    int numFrames,
                                    java.util.List<com.benesquivelmusic.daw.core.track.Track> tracks,
                                    com.benesquivelmusic.daw.core.audio.performance.TrackCpuBudgetEnforcer enforcer) {
        boolean useDouble = mixPrecision == MixPrecision.DOUBLE_64;
        double[][] acc = useDouble ? ensureAccumulator(outputBuffer.length, numFrames) : null;

        for (float[] ch : outputBuffer) {
            Arrays.fill(ch, 0, numFrames, 0.0f);
        }
        if (useDouble) {
            for (int ch = 0; ch < outputBuffer.length; ch++) {
                Arrays.fill(acc[ch], 0, numFrames, 0.0);
            }
        }

        int returnBusCount = Math.min(returnBuses.size(), returnBuffers.length);
        for (int r = 0; r < returnBusCount; r++) {
            for (float[] ch : returnBuffers[r]) {
                Arrays.fill(ch, 0, numFrames, 0.0f);
            }
        }

        boolean anySolo = false;
        for (MixerChannel channel : channels) {
            if (channel.isSolo()) {
                anySolo = true;
                break;
            }
        }

        int channelCount = Math.min(channels.size(), channelBuffers.length);
        int trackListSize = tracks.size();

        // Capture pre-insert signals BEFORE any insert processing runs so
        // that SendTap.PRE_INSERTS sends always tap the truly dry signal,
        // even when the parallel scheduler runs inserts in another thread.
        capturePreInsertsForActiveChannels(channelBuffers, channelCount, anySolo, numFrames);

        // Parallel pre-pass: dispatch insert-chain processing for channels
        // without sidechain routing to worker threads, matching the
        // uninstrumented mixDown() behavior.
        boolean[] insertsDone = ensureInsertsProcessedFlags(channelCount);
        AudioGraphScheduler scheduler = this.graphScheduler;
        if (scheduler != null && channelCount >= 2) {
            scheduler.processInsertsParallel(channels, channelBuffers, numFrames,
                    anySolo, Mixer::hasSidechainRouting, insertsDone);
        }

        for (int i = 0; i < channelCount; i++) {
            MixerChannel channel = channels.get(i);
            if (channel.isMuted()) {
                continue;
            }
            if (anySolo && !channel.isSolo() && !channel.isSoloSafe()) {
                continue;
            }

            // Auto-register the track with the enforcer so budgets take
            // effect without requiring explicit external registration.
            if (i < trackListSize) {
                enforcer.registerTrack(tracks.get(i).getId(), channel.getCpuBudget());
            }

            // Start timing this track's mixer processing
            long trackStart = System.nanoTime();

            float[][] src = channelBuffers[i];

            // Pre-insert signal was captured up front; look it up here.
            float[][] preInsertSrc = preInsertSourceFor(i);

            if (!channel.getEffectsChain().isEmpty() && !insertsDone[i]) {
                if (hasSidechainRouting(channel)) {
                    processInsertsWithSidechain(channel, src, channelBuffers, returnBuffers, numFrames);
                } else {
                    channel.getEffectsChain().process(src, src, numFrames);
                }
            }

            float volume = (float) channel.getVolume();

            routeSends(channel, preInsertSrc, src, volume,
                    returnBuffers, returnBusCount, numFrames);

            delayCompensation.applyToChannel(i, src, numFrames);

            if (!channel.getOutputRouting().isMaster()) {
                // Record timing even for direct-output tracks
                long trackElapsed = System.nanoTime() - trackStart;
                if (i < trackListSize) {
                    enforcer.recordTrackCpu(tracks.get(i).getId(), trackElapsed);
                }
                continue;
            }

            if (useDouble) {
                sumChannelToOutputDouble(channel, src, acc, outputBuffer.length, numFrames);
            } else {
                sumChannelToOutput(channel, src, outputBuffer, numFrames);
            }

            // Record per-track CPU measurement
            long trackElapsed = System.nanoTime() - trackStart;
            if (i < trackListSize) {
                enforcer.recordTrackCpu(tracks.get(i).getId(), trackElapsed);
            }
        }

        // Process return bus effects, apply compensation, and sum into main output
        for (int r = 0; r < returnBusCount; r++) {
            MixerChannel returnBus = returnBuses.get(r);
            float[][] returnBuf = returnBuffers[r];
            if (!returnBus.getEffectsChain().isEmpty()) {
                if (useDouble) {
                    double[][] dblBuf = ensureReturnBusScratchDouble(returnBuf.length, numFrames);
                    for (int ch = 0; ch < returnBuf.length; ch++) {
                        for (int f = 0; f < numFrames; f++) {
                            dblBuf[ch][f] = returnBuf[ch][f];
                        }
                    }
                    returnBus.getEffectsChain().processDouble(dblBuf, dblBuf, numFrames);
                    for (int ch = 0; ch < returnBuf.length; ch++) {
                        for (int f = 0; f < numFrames; f++) {
                            returnBuf[ch][f] = (float) dblBuf[ch][f];
                        }
                    }
                } else {
                    returnBus.getEffectsChain().process(returnBuf, returnBuf, numFrames);
                }
            }
            delayCompensation.applyToReturnBus(r, returnBuf, numFrames);

            float returnVolume = (float) returnBus.getVolume();
            int returnAudioChannels = Math.min(returnBuf.length, outputBuffer.length);
            if (returnBus.isMuted()) {
                for (float[] ch : returnBuf) {
                    Arrays.fill(ch, 0, numFrames, 0.0f);
                }
            } else if (useDouble) {
                double returnVolumeD = returnBus.getVolume();
                for (int ch = 0; ch < returnAudioChannels; ch++) {
                    for (int f = 0; f < numFrames; f++) {
                        double scaled = returnBuf[ch][f] * returnVolumeD;
                        returnBuf[ch][f] = (float) scaled;
                        acc[ch][f] += scaled;
                    }
                }
            } else {
                for (int ch = 0; ch < returnAudioChannels; ch++) {
                    for (int f = 0; f < numFrames; f++) {
                        returnBuf[ch][f] *= returnVolume;
                        outputBuffer[ch][f] += returnBuf[ch][f];
                    }
                }
            }
        }

        float masterVolume = (float) masterChannel.getVolume();
        if (useDouble) {
            finalizeAccumulator(acc, outputBuffer, numFrames,
                    masterChannel.isMuted() ? 0.0 : masterChannel.getVolume());
            return;
        }
        if (!masterChannel.isMuted()) {
            for (float[] ch : outputBuffer) {
                for (int f = 0; f < numFrames; f++) {
                    ch[f] *= masterVolume;
                }
            }
        } else {
            for (float[] ch : outputBuffer) {
                Arrays.fill(ch, 0, numFrames, 0.0f);
            }
        }
    }

    // ── Channel → output summing ─────────────────────────────────────────

    /**
     * Ensures the pre-allocated 64-bit mix accumulator has at least
     * {@code channels} rows and {@code frames} samples per row. Reuses the
     * existing array whenever possible so steady-state {@link #mixDown}
     * invocations remain allocation-free. A one-time growth occurs when
     * the block size or audio channel count increases.
     */
    private double[][] ensureAccumulator(int channels, int frames) {
        double[][] acc = this.mixAccumulator;
        if (acc.length < channels || (acc.length > 0 && acc[0].length < frames)) {
            int rows = Math.max(channels, acc.length);
            int cols = Math.max(frames, acc.length > 0 ? acc[0].length : 0);
            acc = new double[rows][cols];
            this.mixAccumulator = acc;
        }
        return acc;
    }

    /**
     * Ensures the return-bus double scratch buffer has at least
     * {@code channels} rows and {@code frames} samples per row.
     */
    private double[][] ensureReturnBusScratchDouble(int channels, int frames) {
        double[][] buf = this.returnBusScratchDouble;
        if (buf.length < channels || (buf.length > 0 && buf[0].length < frames)) {
            int rows = Math.max(channels, buf.length);
            int cols = Math.max(frames, (buf.length > 0 && buf[0].length > 0) ? buf[0].length : 0);
            buf = new double[rows][cols];
            this.returnBusScratchDouble = buf;
        }
        return buf;
    }

    /**
     * Returns {@code true} if {@code channel} has at least one send whose
     * tap point is {@link SendTap#PRE_INSERTS} <em>and</em> whose level is
     * audible. This is a quick gate so we only copy the pre-insert signal
     * into a scratch buffer when it will actually be consumed.
     */
    @RealTimeSafe
    private static boolean channelHasActivePreInsertSend(MixerChannel channel) {
        List<Send> sends = channel.getSends();
        for (int s = 0, n = sends.size(); s < n; s++) {
            Send send = sends.get(s);
            if (send.getTap() == SendTap.PRE_INSERTS && send.getLevel() > 0.0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pre-pass that captures each channel's pre-insert signal into
     * {@link #preInsertScratchPerChannel} <em>before</em> any insert
     * processing runs. Doing this up front (rather than inline in the
     * per-channel mixing loop) is what makes {@link SendTap#PRE_INSERTS}
     * correct even when the optional {@link AudioGraphScheduler} processes
     * insert chains in parallel — by the time the parallel pre-pass runs
     * and mutates {@code channelBuffers}, the dry signal has already been
     * preserved.
     *
     * <p>Allocations are amortised: the outer array grows once to the
     * channel count, and per-channel inner buffers are allocated only when
     * a channel actually has an active {@link SendTap#PRE_INSERTS} send.
     * After the first block at a given channel/block-size configuration
     * the method is allocation-free.</p>
     */
    @RealTimeSafe
    private void capturePreInsertsForActiveChannels(float[][][] channelBuffers,
                                                    int channelCount,
                                                    boolean anySolo,
                                                    int numFrames) {
        // Grow the per-channel slot arrays once when channel count grows,
        // preserving any existing per-channel scratch buffers so capacity
        // increases remain amortized and reusable on the RT thread.
        if (preInsertScratchPerChannel.length < channelCount) {
            preInsertScratchPerChannel = Arrays.copyOf(preInsertScratchPerChannel, channelCount);
            preInsertCaptured = Arrays.copyOf(preInsertCaptured, channelCount);
        }
        Arrays.fill(preInsertCaptured, 0, channelCount, false);

        for (int i = 0; i < channelCount; i++) {
            MixerChannel channel = channels.get(i);
            if (channel.isMuted() || (anySolo && !channel.isSolo() && !channel.isSoloSafe())) {
                continue;
            }
            if (!channelHasActivePreInsertSend(channel)) {
                continue;
            }
            float[][] src = channelBuffers[i];
            float[][] dst = preInsertScratchPerChannel[i];
            // Allocate / grow the per-channel buffer on demand. Zero-alloc
            // on subsequent blocks once the channel has been seen.
            if (dst == null || dst.length < src.length
                    || (dst.length > 0 && dst[0].length < numFrames)) {
                dst = new float[src.length][numFrames];
                preInsertScratchPerChannel[i] = dst;
            }
            for (int ch = 0; ch < src.length; ch++) {
                System.arraycopy(src[ch], 0, dst[ch], 0, numFrames);
            }
            preInsertCaptured[i] = true;
        }
    }

    /**
     * Returns the pre-insert capture for {@code channelIndex} populated by
     * {@link #capturePreInsertsForActiveChannels}, or {@code null} if no
     * capture exists for this channel in the current block (i.e. the
     * channel has no active {@link SendTap#PRE_INSERTS} send, or the
     * capture pre-pass did not run).
     */
    @RealTimeSafe
    private float[][] preInsertSourceFor(int channelIndex) {
        if (channelIndex < 0 || channelIndex >= preInsertCaptured.length) {
            return null;
        }
        return preInsertCaptured[channelIndex]
                ? preInsertScratchPerChannel[channelIndex]
                : null;
    }

    /**
     * Routes every send on {@code channel} to its target return bus, picking
     * the correct signal source according to each send's {@link SendTap}:
     *
     * <ul>
     *   <li>{@link SendTap#PRE_INSERTS} reads from {@code preInsertSrc}
     *       (the input captured before the insert chain ran). Falls back to
     *       {@code postInsertSrc} when no pre-insert capture is available
     *       (i.e. the channel had no active pre-inserts send at the start
     *       of the block — that case is gated out before this method runs).</li>
     *   <li>{@link SendTap#PRE_FADER} reads from {@code postInsertSrc}
     *       (post-inserts, before the channel fader/pan).</li>
     *   <li>{@link SendTap#POST_FADER} reads from {@code postInsertSrc}
     *       and scales by the channel volume.</li>
     * </ul>
     *
     * <p>This method is real-time safe: it allocates nothing and uses
     * identity comparisons to locate the return bus index in
     * {@code O(returnBusCount)} per send.</p>
     */
    @RealTimeSafe
    private void routeSends(MixerChannel channel,
                            float[][] preInsertSrc,
                            float[][] postInsertSrc,
                            float volume,
                            float[][][] returnBuffers,
                            int returnBusCount,
                            int numFrames) {
        List<Send> sends = channel.getSends();
        for (int s = 0, n = sends.size(); s < n; s++) {
            Send send = sends.get(s);
            float sendLevel = (float) send.getLevel();
            if (sendLevel <= 0.0f) {
                continue;
            }

            // Find the return bus index via identity comparison to avoid
            // the O(n) equals()-based indexOf call per send per block.
            MixerChannel target = send.getTarget();
            int returnIndex = -1;
            for (int r = 0; r < returnBusCount; r++) {
                if (returnBuses.get(r) == target) {
                    returnIndex = r;
                    break;
                }
            }
            if (returnIndex < 0) {
                continue;
            }

            float[][] returnBuf = returnBuffers[returnIndex];
            SendTap tap = send.getTap();
            // Choose the source bus and the gain applied while summing.
            // Note: PRE_INSERTS gracefully falls back to postInsertSrc when
            // the capture buffer is unavailable (e.g. mixer not prepared).
            float[][] source = switch (tap) {
                case PRE_INSERTS -> preInsertSrc != null ? preInsertSrc : postInsertSrc;
                case PRE_FADER, POST_FADER -> postInsertSrc;
            };
            float gain = switch (tap) {
                case PRE_INSERTS, PRE_FADER -> sendLevel;
                case POST_FADER -> sendLevel * volume;
            };

            int returnAudioChannels = Math.min(source.length, returnBuf.length);
            for (int ch = 0; ch < returnAudioChannels; ch++) {
                float[] in = source[ch];
                float[] out = returnBuf[ch];
                for (int f = 0; f < numFrames; f++) {
                    out[f] += in[f] * gain;
                }
            }
        }
    }

    /**
     * Narrows the 64-bit mix accumulator into the float {@code outputBuffer},
     * applying the master channel volume in double precision as the final
     * summing-bus stage.
     */
    @RealTimeSafe
    private static void finalizeAccumulator(double[][] acc, float[][] outputBuffer,
                                            int numFrames, double masterVolume) {
        int channelCount = Math.min(acc.length, outputBuffer.length);
        if (masterVolume == 0.0) {
            for (int ch = 0; ch < outputBuffer.length; ch++) {
                Arrays.fill(outputBuffer[ch], 0, numFrames, 0.0f);
            }
            return;
        }
        for (int ch = 0; ch < channelCount; ch++) {
            for (int f = 0; f < numFrames; f++) {
                outputBuffer[ch][f] = (float) (acc[ch][f] * masterVolume);
            }
        }
        // Zero any trailing hardware-output channels that the mixer did not
        // drive (they may later receive direct-output audio).
        for (int ch = channelCount; ch < outputBuffer.length; ch++) {
            Arrays.fill(outputBuffer[ch], 0, numFrames, 0.0f);
        }
    }

    /**
     * Double-precision counterpart to {@link #sumChannelToOutput}: sums a
     * channel's post-insert audio into the 64-bit accumulator using the same
     * constant-power pan law as the single-precision path.
     */
    @RealTimeSafe
    private static void sumChannelToOutputDouble(MixerChannel channel, float[][] src,
                                                 double[][] acc, int outChannels,
                                                 int numFrames) {
        double volume = channel.getVolume();
        int audioChannels = Math.min(src.length, outChannels);

        if (outChannels >= 2 && audioChannels >= 1) {
            double pan = channel.getPan();
            double angle = (pan + 1.0) * 0.25 * Math.PI;
            double leftGain = Math.cos(angle) * volume;
            double rightGain = Math.sin(angle) * volume;

            for (int f = 0; f < numFrames; f++) {
                acc[0][f] += src[0][f] * leftGain;
            }
            if (audioChannels >= 2) {
                for (int f = 0; f < numFrames; f++) {
                    acc[1][f] += src[1][f] * rightGain;
                }
            } else {
                for (int f = 0; f < numFrames; f++) {
                    acc[1][f] += src[0][f] * rightGain;
                }
            }
            for (int ch = 2; ch < audioChannels; ch++) {
                for (int f = 0; f < numFrames; f++) {
                    acc[ch][f] += src[ch][f] * volume;
                }
            }
        } else {
            for (int ch = 0; ch < audioChannels; ch++) {
                for (int f = 0; f < numFrames; f++) {
                    acc[ch][f] += src[ch][f] * volume;
                }
            }
        }
    }

    /**
     * Sums a single channel's post-insert audio into the given output buffer,
     * applying the channel's volume and constant-power pan law.
     */
    @RealTimeSafe
    private static void sumChannelToOutput(MixerChannel channel, float[][] src,
                                           float[][] outputBuffer, int numFrames) {
        float volume = (float) channel.getVolume();
        int audioChannels = Math.min(src.length, outputBuffer.length);

        if (outputBuffer.length >= 2 && audioChannels >= 1) {
            double pan = channel.getPan();
            double angle = (pan + 1.0) * 0.25 * Math.PI;
            float leftGain = (float) (Math.cos(angle) * volume);
            float rightGain = (float) (Math.sin(angle) * volume);

            for (int f = 0; f < numFrames; f++) {
                outputBuffer[0][f] += src[0][f] * leftGain;
            }
            if (audioChannels >= 2) {
                for (int f = 0; f < numFrames; f++) {
                    outputBuffer[1][f] += src[1][f] * rightGain;
                }
            } else {
                for (int f = 0; f < numFrames; f++) {
                    outputBuffer[1][f] += src[0][f] * rightGain;
                }
            }
            for (int ch = 2; ch < audioChannels; ch++) {
                for (int f = 0; f < numFrames; f++) {
                    outputBuffer[ch][f] += src[ch][f] * volume;
                }
            }
        } else {
            for (int ch = 0; ch < audioChannels; ch++) {
                for (int f = 0; f < numFrames; f++) {
                    outputBuffer[ch][f] += src[ch][f] * volume;
                }
            }
        }
    }

    // ── Direct output rendering ───────────────────────────────────────────

    /**
     * Writes audio from channels with non-master output routing into the
     * given hardware output buffer. Insert effects and delay compensation
     * have already been applied to {@code channelBuffers} by a prior
     * {@link #mixDown} call. This method applies volume and pan before
     * writing to the assigned output channels.
     *
     * <p>For each channel whose {@link MixerChannel#getOutputRouting()} is
     * not master, its post-processed audio is summed into the appropriate
     * region of {@code hwOutputBuffer} based on the routing's first-channel
     * index. The buffer must be large enough to hold the highest configured
     * output channel.</p>
     *
     * @param channelBuffers per-channel audio data (post-insert, post-PDC)
     *                       {@code [mixerChannel][audioChannel][frame]}
     * @param hwOutputBuffer the hardware output buffer {@code [outputChannel][frame]}
     * @param numFrames      the number of sample frames
     */
    @RealTimeSafe
    public void renderDirectOutputs(float[][][] channelBuffers, float[][] hwOutputBuffer,
                                    int numFrames) {
        boolean anySolo = false;
        for (MixerChannel channel : channels) {
            if (channel.isSolo()) {
                anySolo = true;
                break;
            }
        }

        int channelCount = Math.min(channels.size(), channelBuffers.length);
        for (int i = 0; i < channelCount; i++) {
            MixerChannel channel = channels.get(i);
            OutputRouting routing = channel.getOutputRouting();
            if (routing.isMaster()) {
                continue;
            }
            if (channel.isMuted()) {
                continue;
            }
            if (anySolo && !channel.isSolo() && !channel.isSoloSafe()) {
                continue;
            }

            float[][] src = channelBuffers[i];
            float volume = (float) channel.getVolume();
            int firstOut = routing.firstChannel();
            int outChannels = routing.channelCount();

            // Apply constant-power pan law for stereo direct outputs
            if (outChannels >= 2 && src.length >= 1) {
                double pan = channel.getPan();
                double angle = (pan + 1.0) * 0.25 * Math.PI;
                float leftGain = (float) (Math.cos(angle) * volume);
                float rightGain = (float) (Math.sin(angle) * volume);

                int leftDest = firstOut;
                int rightDest = firstOut + 1;
                if (leftDest < hwOutputBuffer.length) {
                    for (int f = 0; f < numFrames; f++) {
                        hwOutputBuffer[leftDest][f] += src[0][f] * leftGain;
                    }
                }
                if (rightDest < hwOutputBuffer.length) {
                    if (src.length >= 2) {
                        for (int f = 0; f < numFrames; f++) {
                            hwOutputBuffer[rightDest][f] += src[1][f] * rightGain;
                        }
                    } else {
                        // Mono source panned into stereo output pair
                        for (int f = 0; f < numFrames; f++) {
                            hwOutputBuffer[rightDest][f] += src[0][f] * rightGain;
                        }
                    }
                }
                // Extra channels beyond the stereo pair (surround)
                for (int ch = 2; ch < Math.min(outChannels, src.length); ch++) {
                    int destCh = firstOut + ch;
                    if (destCh < hwOutputBuffer.length) {
                        for (int f = 0; f < numFrames; f++) {
                            hwOutputBuffer[destCh][f] += src[ch][f] * volume;
                        }
                    }
                }
            } else {
                // Mono direct output: volume only, no pan
                for (int ch = 0; ch < Math.min(outChannels, src.length); ch++) {
                    int destCh = firstOut + ch;
                    if (destCh < hwOutputBuffer.length) {
                        for (int f = 0; f < numFrames; f++) {
                            hwOutputBuffer[destCh][f] += src[ch][f] * volume;
                        }
                    }
                }
            }
        }
    }

    // ── Sidechain routing ──────────────────────────────────────────────────

    /**
     * Returns {@code true} if any non-bypassed insert slot on the channel has
     * a sidechain source configured with a {@link SidechainAwareProcessor}.
     *
     * <p>Package-private so the {@link AudioGraphScheduler} can consult this
     * predicate when deciding which channels can run their insert chains on
     * worker threads.</p>
     */
    static boolean hasSidechainRouting(MixerChannel channel) {
        for (InsertSlot slot : channel.getInsertSlots()) {
            if (!slot.isBypassed()
                    && slot.getSidechainSource() != null
                    && slot.getProcessor() instanceof SidechainAwareProcessor) {
                return true;
            }
        }
        return false;
    }

    /**
     * Processes a channel's insert effects, routing sidechain buffers where
     * configured. This replaces the standard {@code EffectsChain.process()}
     * call when at least one insert slot has a sidechain source.
     *
     * <p>For each non-bypassed insert slot:
     * <ul>
     *   <li>If the slot has a sidechain source and the processor is a
     *       {@link SidechainAwareProcessor}, look up the source channel's
     *       buffer from {@code channelBuffers} (or {@code returnBuffers} for
     *       return bus sources) and call {@code processSidechain()}.</li>
     *   <li>Otherwise, call the standard {@code process()} method.</li>
     * </ul>
     *
     * <p>Uses {@code src} as both the initial input and the final output
     * destination. Two pre-allocated scratch buffers ({@link #scratchBufferA}
     * and {@link #scratchBufferB}) are used for intermediate results,
     * ping-ponging between them to avoid buffer aliasing when multiple
     * non-bypassed inserts are present. The last active processor always
     * writes directly to {@code src}.</p>
     */
    @RealTimeSafe
    private void processInsertsWithSidechain(MixerChannel channel, float[][] src,
                                             float[][][] channelBuffers,
                                             float[][][] returnBuffers,
                                             int numFrames) {
        List<InsertSlot> slots = channel.getInsertSlots();

        // Count active (non-bypassed) slots
        int activeCount = 0;
        for (int s = 0; s < slots.size(); s++) {
            if (!slots.get(s).isBypassed()) {
                activeCount++;
            }
        }
        if (activeCount == 0) {
            return;
        }

        // Process each active slot, ping-ponging between scratchBufferA and
        // scratchBufferB for intermediates. The last active slot writes
        // directly to src.
        //
        // For a single active slot: process(src, src) — in-place, matching
        // the existing EffectsChain behavior for single-processor chains.
        //
        // For 2+ active slots, intermediates use scratch buffers so that
        // currentInput and currentOutput are always distinct arrays:
        //   Slot 1: read src,      write scratchA  → currentInput = scratchA
        //   Slot 2: read scratchA, write scratchB  → currentInput = scratchB
        //   Slot 3: read scratchB, write scratchA  → currentInput = scratchA
        //   ...
        //   Last:   read scratchX, write src       → no aliasing (scratchX ≠ src)
        float[][] currentInput = src;
        int processed = 0;
        boolean usePingA = true;

        for (int s = 0; s < slots.size(); s++) {
            InsertSlot slot = slots.get(s);
            if (slot.isBypassed()) {
                continue;
            }
            processed++;
            boolean isLast = (processed == activeCount);

            float[][] currentOutput;
            if (isLast) {
                currentOutput = src;
            } else {
                currentOutput = usePingA ? scratchBufferA : scratchBufferB;
                usePingA = !usePingA;
            }

            MixerChannel scSource = slot.getSidechainSource();
            if (scSource != null && slot.getProcessor() instanceof SidechainAwareProcessor sap) {
                float[][] scBuffer = findChannelBuffer(scSource, channelBuffers, returnBuffers);
                if (scBuffer != null) {
                    sap.processSidechain(currentInput, scBuffer, currentOutput, numFrames);
                } else {
                    slot.getProcessor().process(currentInput, currentOutput, numFrames);
                }
            } else {
                slot.getProcessor().process(currentInput, currentOutput, numFrames);
            }

            currentInput = currentOutput;
        }
    }

    /**
     * Finds the audio buffer for the given mixer channel from the channel buffers
     * array, or from the return buffers if the target is a return bus. Returns
     * {@code null} if the channel is not found in either set.
     *
     * @param target         the mixer channel to look up
     * @param channelBuffers per-channel audio buffers (regular channels)
     * @param returnBuffers  per-return-bus audio buffers, or {@code null} if unavailable
     * @return the audio buffer for the target, or {@code null}
     */
    @RealTimeSafe
    private float[][] findChannelBuffer(MixerChannel target, float[][][] channelBuffers,
                                        float[][][] returnBuffers) {
        int count = Math.min(channels.size(), channelBuffers.length);
        for (int i = 0; i < count; i++) {
            if (channels.get(i) == target) {
                return channelBuffers[i];
            }
        }
        if (returnBuffers != null) {
            int rbCount = Math.min(returnBuses.size(), returnBuffers.length);
            for (int i = 0; i < rbCount; i++) {
                if (returnBuses.get(i) == target) {
                    return returnBuffers[i];
                }
            }
        }
        return null;
    }

    // ── Plugin Delay Compensation ──────────────────────────────────────────

    /**
     * Returns the {@link PluginDelayCompensation} instance managing per-channel
     * delay compensation for this mixer.
     *
     * @return the delay compensation manager
     */
    public PluginDelayCompensation getDelayCompensation() {
        return delayCompensation;
    }

    /**
     * Returns the total system latency in samples — the maximum insert chain
     * latency across all channels and return buses.
     *
     * <p>The transport can use this value to offset the playback start position
     * so that the first audible sample aligns with beat 1.</p>
     *
     * @return the system latency in sample frames
     */
    public int getSystemLatencySamples() {
        return delayCompensation.getMaxLatencySamples();
    }

    /**
     * Recalculates delay compensation for all channels and return buses.
     *
     * <p>Called automatically when insert effects are added, removed, reordered,
     * or bypassed on any channel managed by this mixer, and when
     * {@link #prepareForPlayback(int, int)} is called.</p>
     */
    private void recalculateDelayCompensation() {
        int audioChannels = preparedAudioChannels;
        if (audioChannels <= 0) {
            // Not yet prepared — use a safe default for latency calculation only
            audioChannels = 2;
        }
        delayCompensation.recalculate(channels, returnBuses, audioChannels);
        // Insert-chain mutations invalidate every channel's reflective parameter
        // bindings. Rebinding here keeps the real-time apply() path allocation-free
        // without forcing every call site that mutates inserts to remember.
        rebindAllReflectiveParameterBindings();
    }

    /**
     * Returns the mixer's shared {@link ReflectiveParameterBinder}, which
     * maps automation lanes to {@code @ProcessorParam}-annotated setters on
     * insert-slot processors.
     *
     * <p>Host code may use the binder to enumerate automatable plugin
     * parameters per channel ({@link ReflectiveParameterBinder#getAutomatablePluginParameters(MixerChannel)})
     * and to apply automation on the audio thread
     * ({@link ReflectiveParameterBinder#apply(MixerChannel,
     * com.benesquivelmusic.daw.core.automation.AutomationData, double)}).</p>
     *
     * @return the binder; never {@code null}
     */
    public ReflectiveParameterBinder getReflectiveParameterBinder() {
        return reflectiveParameterBinder;
    }

    /**
     * Installs the multi-core audio graph scheduler used to distribute
     * per-channel insert-effect processing across worker threads during
     * {@link #mixDown(float[][][], float[][], float[][][], int)}. Pass
     * {@code null} to restore single-threaded behavior.
     *
     * <p>When a scheduler is installed, channels whose insert chain has no
     * sidechain routing run their inserts on worker threads; the summing,
     * send-routing, and delay-compensation phases remain sequential so
     * the output is bit-exact with the single-threaded path.</p>
     *
     * @param scheduler the scheduler, or {@code null} to disable parallelism
     */
    public void setGraphScheduler(AudioGraphScheduler scheduler) {
        this.graphScheduler = scheduler;
    }

    /**
     * Returns the currently installed graph scheduler, or {@code null} if
     * parallel graph processing is disabled.
     *
     * @return the scheduler, or {@code null}
     */
    public AudioGraphScheduler getGraphScheduler() {
        return graphScheduler;
    }

    private void rebindAllReflectiveParameterBindings() {
        for (MixerChannel channel : channels) {
            reflectiveParameterBinder.rebind(channel);
        }
        for (MixerChannel returnBus : returnBuses) {
            reflectiveParameterBinder.rebind(returnBus);
        }
        reflectiveParameterBinder.rebind(masterChannel);
    }

    /**
     * Returns the insert-processed flag array grown to at least
     * {@code minLength} entries and cleared to {@code false}.
     *
     * <p>Allocation-free on the steady state: the array is retained across
     * {@link #mixDown} invocations and only reallocated when the channel
     * count grows. This preserves real-time safety on the audio thread.</p>
     */
    private boolean[] ensureInsertsProcessedFlags(int minLength) {
        boolean[] flags = this.insertsProcessedFlags;
        if (flags.length < minLength) {
            flags = new boolean[minLength];
            this.insertsProcessedFlags = flags;
        } else {
            Arrays.fill(flags, 0, minLength, false);
        }
        return flags;
    }
}
