package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.audio.AudioGraphScheduler;
import com.benesquivelmusic.daw.core.audio.PluginDelayCompensation;
import com.benesquivelmusic.daw.core.automation.ReflectiveParameterBinder;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
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
     * Optional multi-core graph scheduler. When non-null and the block size
     * meets the scheduler's parallel threshold, per-channel insert chains
     * without sidechain routing are dispatched across worker threads; the
     * summing, send-routing, and delay-compensation phases remain sequential
     * so output is bit-exact with the single-threaded path.
     */
    private AudioGraphScheduler graphScheduler;
    /**
     * Reusable flag array marking which channels had their insert chain
     * already applied by the parallel pre-pass. Lazily grown to the current
     * channel count; reset at the start of every {@link #mixDown} call to
     * preserve allocation-free real-time behavior.
     */
    private boolean[] insertsProcessedFlags = new boolean[0];

    /** Creates a new mixer with an empty channel list, a default master channel, and a reverb return aux bus. */
    public Mixer() {
        this.masterChannel = new MixerChannel("Master");
        MixerChannel defaultReturn = new MixerChannel("Reverb Return");
        defaultReturn.setOnEffectsChainChanged(this::recalculateDelayCompensation);
        returnBuses.add(defaultReturn);
    }

    /**
     * Adds a new channel to the mixer.
     *
     * @param channel the channel to add
     */
    public void addChannel(MixerChannel channel) {
        Objects.requireNonNull(channel, "channel must not be null");
        channel.setOnEffectsChainChanged(this::recalculateDelayCompensation);
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
        returnBus.setOnEffectsChainChanged(this::recalculateDelayCompensation);
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
        for (MixerChannel channel : channels) {
            channel.prepareEffectsChain(audioChannels, blockSize);
        }
        for (MixerChannel returnBus : returnBuses) {
            returnBus.prepareEffectsChain(audioChannels, blockSize);
        }
        masterChannel.prepareEffectsChain(audioChannels, blockSize);
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
        // Clear output
        for (float[] ch : outputBuffer) {
            Arrays.fill(ch, 0, numFrames, 0.0f);
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
            if (anySolo && !channel.isSolo()) {
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

            sumChannelToOutput(channel, src, outputBuffer, numFrames);
        }

        // Apply master volume
        float masterVolume = (float) masterChannel.getVolume();
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
            if (anySolo && !channel.isSolo()) {
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
        // Clear output
        for (float[] ch : outputBuffer) {
            Arrays.fill(ch, 0, numFrames, 0.0f);
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
            if (anySolo && !channel.isSolo()) {
                continue;
            }

            float[][] src = channelBuffers[i];

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
            List<Send> sends = channel.getSends();
            for (int s = 0; s < sends.size(); s++) {
                Send send = sends.get(s);
                float sendLevel = (float) send.getLevel();
                if (sendLevel <= 0.0f) {
                    continue;
                }

                // Find the return bus index via identity comparison to avoid
                // the O(n) equals()-based indexOf call per send per block
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
                int returnAudioChannels = Math.min(src.length, returnBuf.length);

                if (send.getMode() == SendMode.PRE_FADER) {
                    for (int ch = 0; ch < returnAudioChannels; ch++) {
                        for (int f = 0; f < numFrames; f++) {
                            returnBuf[ch][f] += src[ch][f] * sendLevel;
                        }
                    }
                } else {
                    // Post-fader: apply channel volume to send
                    for (int ch = 0; ch < returnAudioChannels; ch++) {
                        for (int f = 0; f < numFrames; f++) {
                            returnBuf[ch][f] += src[ch][f] * volume * sendLevel;
                        }
                    }
                }
            }

            // Apply plugin delay compensation AFTER sends are tapped so
            // that the direct path is aligned at the summing bus
            delayCompensation.applyToChannel(i, src, numFrames);

            // Skip channels routed to direct hardware outputs
            if (!channel.getOutputRouting().isMaster()) {
                continue;
            }

            sumChannelToOutput(channel, src, outputBuffer, numFrames);
        }

        // Process return bus effects, apply compensation, and sum into main output
        for (int r = 0; r < returnBusCount; r++) {
            MixerChannel returnBus = returnBuses.get(r);
            float[][] returnBuf = returnBuffers[r];

            // Apply return bus insert effects
            if (!returnBus.getEffectsChain().isEmpty()) {
                returnBus.getEffectsChain().process(returnBuf, returnBuf, numFrames);
            }

            // Apply delay compensation for the return bus
            delayCompensation.applyToReturnBus(r, returnBuf, numFrames);

            float returnVolume = (float) returnBus.getVolume();
            int returnAudioChannels = Math.min(returnBuf.length, outputBuffer.length);

            if (returnBus.isMuted()) {
                for (float[] ch : returnBuf) {
                    Arrays.fill(ch, 0, numFrames, 0.0f);
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
            if (anySolo && !channel.isSolo()) {
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
