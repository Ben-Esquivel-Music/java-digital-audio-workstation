package com.benesquivelmusic.daw.core.mixer;

import java.util.Objects;

/**
 * Represents a single channel strip in the mixer.
 *
 * <p>Each mixer channel has independent volume, pan, mute, solo, and send level
 * controls. The send level controls how much of this channel's audio is routed
 * to the auxiliary/return bus (e.g., reverb, delay return).</p>
 */
public final class MixerChannel {

    private final String name;
    private double volume;
    private double pan;
    private boolean muted;
    private boolean solo;
    private double sendLevel;

    /**
     * Creates a new mixer channel with the specified name.
     *
     * @param name the display name
     */
    public MixerChannel(String name) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.volume = 1.0;
        this.pan = 0.0;
        this.muted = false;
        this.solo = false;
        this.sendLevel = 0.0;
    }

    /** Returns the channel name. */
    public String getName() {
        return name;
    }

    /** Returns the volume level (0.0 – 1.0). */
    public double getVolume() {
        return volume;
    }

    /** Sets the volume level. */
    public void setVolume(double volume) {
        if (volume < 0.0 || volume > 1.0) {
            throw new IllegalArgumentException("volume must be between 0.0 and 1.0: " + volume);
        }
        this.volume = volume;
    }

    /** Returns the pan position (−1.0 to 1.0). */
    public double getPan() {
        return pan;
    }

    /** Sets the pan position. */
    public void setPan(double pan) {
        if (pan < -1.0 || pan > 1.0) {
            throw new IllegalArgumentException("pan must be between -1.0 and 1.0: " + pan);
        }
        this.pan = pan;
    }

    /** Returns whether this channel is muted. */
    public boolean isMuted() {
        return muted;
    }

    /** Sets the muted state. */
    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    /** Returns whether this channel is soloed. */
    public boolean isSolo() {
        return solo;
    }

    /** Sets the solo state. */
    public void setSolo(boolean solo) {
        this.solo = solo;
    }

    /** Returns the send level (0.0 – 1.0). */
    public double getSendLevel() {
        return sendLevel;
    }

    /** Sets the send level. */
    public void setSendLevel(double sendLevel) {
        if (sendLevel < 0.0 || sendLevel > 1.0) {
            throw new IllegalArgumentException("sendLevel must be between 0.0 and 1.0: " + sendLevel);
        }
        this.sendLevel = sendLevel;
    }
}
