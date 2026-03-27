package com.benesquivelmusic.daw.core.mixer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a single channel strip in the mixer.
 *
 * <p>Each mixer channel has independent volume, pan, mute, solo, and send level
 * controls. The send level controls how much of this channel's audio is routed
 * to the auxiliary/return bus (e.g., reverb, delay return).</p>
 *
 * <p>A channel may have multiple {@link Send} objects, each routing audio to a
 * different return bus with independent level and pre/post-fader mode.</p>
 */
public final class MixerChannel {

    private final String name;
    private double volume;
    private double pan;
    private boolean muted;
    private boolean solo;
    private double sendLevel;
    private boolean phaseInverted;
    private final List<Send> sends = new ArrayList<>();

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
        this.phaseInverted = false;
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

    /** Returns whether this channel's phase is inverted. */
    public boolean isPhaseInverted() {
        return phaseInverted;
    }

    /** Sets the phase-inverted state. */
    public void setPhaseInverted(boolean phaseInverted) {
        this.phaseInverted = phaseInverted;
    }

    /**
     * Adds a send routing to a return bus.
     *
     * @param send the send to add
     */
    public void addSend(Send send) {
        Objects.requireNonNull(send, "send must not be null");
        sends.add(send);
    }

    /**
     * Removes a send routing.
     *
     * @param send the send to remove
     * @return {@code true} if the send was removed
     */
    public boolean removeSend(Send send) {
        return sends.remove(send);
    }

    /**
     * Returns an unmodifiable view of the sends on this channel.
     *
     * @return the list of sends
     */
    public List<Send> getSends() {
        return Collections.unmodifiableList(sends);
    }

    /**
     * Returns the send targeting the specified return bus, or {@code null} if
     * no such send exists.
     *
     * @param target the return bus to look up
     * @return the send for the given target, or {@code null}
     */
    public Send getSendForTarget(MixerChannel target) {
        for (Send send : sends) {
            if (send.getTarget() == target) {
                return send;
            }
        }
        return null;
    }
}
