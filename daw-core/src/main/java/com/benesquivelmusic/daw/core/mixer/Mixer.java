package com.benesquivelmusic.daw.core.mixer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The DAW mixer manages a collection of {@link MixerChannel}s and produces
 * the final stereo mix routed to the master output.
 */
public final class Mixer {

    private final List<MixerChannel> channels = new ArrayList<>();
    private final MixerChannel masterChannel;

    /** Creates a new mixer with an empty channel list and a default master channel. */
    public Mixer() {
        this.masterChannel = new MixerChannel("Master");
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
     * Returns the total number of channels (excluding master).
     *
     * @return channel count
     */
    public int getChannelCount() {
        return channels.size();
    }
}
