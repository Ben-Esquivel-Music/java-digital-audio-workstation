package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The DAW mixer manages a collection of {@link MixerChannel}s and produces
 * the final stereo mix routed to the master output.
 *
 * <p>The {@link #mixDown(float[][][], float[][], int)} method sums all channel
 * contributions into a single output buffer without allocations or locks —
 * safe to call on the real-time audio thread.</p>
 */
public final class Mixer {

    private final List<MixerChannel> channels = new ArrayList<>();
    private final MixerChannel masterChannel;
    private final MixerChannel auxBus;

    /** Creates a new mixer with an empty channel list, a default master channel, and a reverb return aux bus. */
    public Mixer() {
        this.masterChannel = new MixerChannel("Master");
        this.auxBus = new MixerChannel("Reverb Return");
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
     *
     * @return the aux bus channel
     */
    public MixerChannel getAuxBus() {
        return auxBus;
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
}
