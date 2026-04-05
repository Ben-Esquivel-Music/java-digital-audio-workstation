package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

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

    /** Creates a new mixer with an empty channel list, a default master channel, and a reverb return aux bus. */
    public Mixer() {
        this.masterChannel = new MixerChannel("Master");
        MixerChannel defaultReturn = new MixerChannel("Reverb Return");
        returnBuses.add(defaultReturn);
    }

    /**
     * Adds a new channel to the mixer.
     *
     * @param channel the channel to add
     */
    public void addChannel(MixerChannel channel) {
        Objects.requireNonNull(channel, "channel must not be null");
        channels.add(channel);
    }

    /**
     * Removes a channel from the mixer.
     *
     * @param channel the channel to remove
     * @return {@code true} if the channel was removed
     */
    public boolean removeChannel(MixerChannel channel) {
        return channels.remove(channel);
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
        returnBuses.add(returnBus);
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
            returnBuses.add(returnBus);
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
            for (MixerChannel channel : channels) {
                Send send = channel.getSendForTarget(returnBus);
                if (send != null) {
                    channel.removeSend(send);
                }
            }
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
     * Sums all channel audio into the output buffer, applying per-channel
     * volume, mute, and solo. This method is allocation-free and lock-free.
     *
     * <p>The {@code channelBuffers} array must have one entry per mixer channel
     * (in the same order as {@link #getChannels()}), each sized
     * {@code [audioChannels][frames]}.</p>
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

            float volume = (float) channel.getVolume();
            float[][] src = channelBuffers[i];
            int audioChannels = Math.min(src.length, outputBuffer.length);

            // Apply constant-power pan law for stereo output
            if (outputBuffer.length >= 2 && audioChannels >= 1) {
                double pan = channel.getPan();
                // pan: -1.0 = full left, 0.0 = center, 1.0 = full right
                double angle = (pan + 1.0) * 0.25 * Math.PI; // [0, π/2]
                float leftGain = (float) (Math.cos(angle) * volume);
                float rightGain = (float) (Math.sin(angle) * volume);

                // Mix mono source or first channel into stereo output
                for (int f = 0; f < numFrames; f++) {
                    outputBuffer[0][f] += src[0][f] * leftGain;
                }
                if (audioChannels >= 2) {
                    for (int f = 0; f < numFrames; f++) {
                        outputBuffer[1][f] += src[1][f] * rightGain;
                    }
                } else {
                    // Mono source panned into stereo
                    for (int f = 0; f < numFrames; f++) {
                        outputBuffer[1][f] += src[0][f] * rightGain;
                    }
                }
                // Copy remaining channels (surround) without pan
                for (int ch = 2; ch < audioChannels; ch++) {
                    for (int f = 0; f < numFrames; f++) {
                        outputBuffer[ch][f] += src[ch][f] * volume;
                    }
                }
            } else {
                // Non-stereo output: apply volume only (no pan)
                for (int ch = 0; ch < audioChannels; ch++) {
                    for (int f = 0; f < numFrames; f++) {
                        outputBuffer[ch][f] += src[ch][f] * volume;
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

    /**
     * Sums all channel audio into the output buffer and routes send audio
     * into the auxiliary output buffer, applying per-channel volume, mute,
     * solo, and send level. This method is allocation-free and lock-free.
     *
     * <p>For each non-muted channel with a non-zero send level, a copy of
     * the channel's pre-fader audio is scaled by the send level and summed
     * into {@code auxOutputBuffer}. The aux bus volume is applied to the
     * final aux output.</p>
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
     * Performs a full mix-down with multi-bus send routing. Channel audio is
     * summed into {@code outputBuffer}. Each return bus receives send contributions
     * from channels that have {@link Send} objects targeting it. Return bus outputs
     * are written to {@code returnBuffers} and also summed into the main output
     * (before master volume).
     *
     * <p>Pre-fader sends tap the raw channel audio (independent of channel volume).
     * Post-fader sends tap the audio after applying the channel volume.</p>
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
        for (int i = 0; i < channelCount; i++) {
            MixerChannel channel = channels.get(i);
            if (channel.isMuted()) {
                continue;
            }
            if (anySolo && !channel.isSolo()) {
                continue;
            }

            float volume = (float) channel.getVolume();
            float[][] src = channelBuffers[i];
            int audioChannels = Math.min(src.length, outputBuffer.length);

            // Mix channel into main output with volume and pan
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

            // Route sends to return buses
            List<Send> sends = channel.getSends();
            for (int s = 0; s < sends.size(); s++) {
                Send send = sends.get(s);
                float sendLevel = (float) send.getLevel();
                if (sendLevel <= 0.0f) {
                    continue;
                }

                int returnIndex = returnBuses.indexOf(send.getTarget());
                if (returnIndex < 0 || returnIndex >= returnBusCount) {
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
        }

        // Apply return bus volume and sum into main output
        for (int r = 0; r < returnBusCount; r++) {
            MixerChannel returnBus = returnBuses.get(r);
            float[][] returnBuf = returnBuffers[r];
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
}
