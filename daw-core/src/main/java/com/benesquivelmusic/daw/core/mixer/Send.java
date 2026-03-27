package com.benesquivelmusic.daw.core.mixer;

import java.util.Objects;

/**
 * Represents a send routing from a mixer channel to a return bus.
 *
 * <p>Each {@code Send} holds a reference to the target return bus, a send level
 * (0.0–1.0), and a {@link SendMode} that determines whether the audio is tapped
 * before or after the channel fader.</p>
 */
public final class Send {

    private final MixerChannel target;
    private double level;
    private SendMode mode;

    /**
     * Creates a new send with the specified target return bus.
     * The initial send level is {@code 0.0} and the mode is {@link SendMode#POST_FADER}.
     *
     * @param target the return bus to route audio to
     */
    public Send(MixerChannel target) {
        this(target, 0.0, SendMode.POST_FADER);
    }

    /**
     * Creates a new send with the specified target, level, and mode.
     *
     * @param target the return bus to route audio to
     * @param level  the send level (0.0–1.0)
     * @param mode   the send mode (pre-fader or post-fader)
     */
    public Send(MixerChannel target, double level, SendMode mode) {
        this.target = Objects.requireNonNull(target, "target must not be null");
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
        setLevel(level);
    }

    /**
     * Returns the target return bus.
     *
     * @return the target return bus
     */
    public MixerChannel getTarget() {
        return target;
    }

    /**
     * Returns the send level (0.0–1.0).
     *
     * @return the send level
     */
    public double getLevel() {
        return level;
    }

    /**
     * Sets the send level.
     *
     * @param level the send level (0.0–1.0)
     * @throws IllegalArgumentException if level is out of range
     */
    public void setLevel(double level) {
        if (level < 0.0 || level > 1.0) {
            throw new IllegalArgumentException("level must be between 0.0 and 1.0: " + level);
        }
        this.level = level;
    }

    /**
     * Returns the send mode (pre-fader or post-fader).
     *
     * @return the send mode
     */
    public SendMode getMode() {
        return mode;
    }

    /**
     * Sets the send mode.
     *
     * @param mode the send mode
     */
    public void setMode(SendMode mode) {
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
    }
}
