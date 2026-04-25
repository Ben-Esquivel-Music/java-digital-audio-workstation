package com.benesquivelmusic.daw.core.mixer;

import java.util.Objects;

/**
 * Represents a send routing from a mixer channel to a return bus.
 *
 * <p>Each {@code Send} holds a reference to the target return bus, a send level
 * (0.0–1.0), and a {@link SendTap tap} point that determines where in the
 * channel's signal flow the send is taken from:</p>
 *
 * <ul>
 *   <li>{@link SendTap#PRE_INSERTS} — before any insert effect</li>
 *   <li>{@link SendTap#PRE_FADER} — after inserts, before the fader</li>
 *   <li>{@link SendTap#POST_FADER} — after the fader (default)</li>
 * </ul>
 *
 * <p>The legacy {@link SendMode} accessor remains for backwards compatibility
 * and reflects whether the tap is pre- or post-fader (treating
 * {@code PRE_INSERTS} as pre-fader for the purpose of that two-state view).
 * The authoritative state is the {@code tap} field.</p>
 */
public final class Send {

    private final MixerChannel target;
    private double level;
    private SendTap tap;

    /**
     * Creates a new send with the specified target return bus.
     * The initial send level is {@code 0.0} and the tap point is
     * {@link SendTap#POST_FADER} (the default that matches legacy behaviour).
     *
     * @param target the return bus to route audio to
     */
    public Send(MixerChannel target) {
        this(target, 0.0, SendTap.POST_FADER);
    }

    /**
     * Creates a new send with the specified target, level, and legacy mode.
     * The {@code mode} is mapped to a {@link SendTap}: {@code PRE_FADER} →
     * {@link SendTap#PRE_FADER}, {@code POST_FADER} → {@link SendTap#POST_FADER}.
     *
     * @param target the return bus to route audio to
     * @param level  the send level (0.0–1.0)
     * @param mode   the send mode (pre-fader or post-fader)
     */
    public Send(MixerChannel target, double level, SendMode mode) {
        this(target, level, modeToTap(Objects.requireNonNull(mode, "mode must not be null")));
    }

    /**
     * Creates a new send with the specified target, level, and tap point.
     *
     * @param target the return bus to route audio to
     * @param level  the send level (0.0–1.0)
     * @param tap    the tap point (pre-inserts, pre-fader, or post-fader)
     */
    public Send(MixerChannel target, double level, SendTap tap) {
        this.target = Objects.requireNonNull(target, "target must not be null");
        this.tap = Objects.requireNonNull(tap, "tap must not be null");
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
     * Returns the send mode (pre-fader or post-fader). This is a legacy view
     * derived from the underlying {@link SendTap}: any pre-fader tap
     * (including {@link SendTap#PRE_INSERTS}) is reported as
     * {@link SendMode#PRE_FADER}.
     *
     * @return the send mode
     */
    public SendMode getMode() {
        return tap == SendTap.POST_FADER ? SendMode.POST_FADER : SendMode.PRE_FADER;
    }

    /**
     * Sets the send mode. {@link SendMode#PRE_FADER} maps to
     * {@link SendTap#PRE_FADER} and {@link SendMode#POST_FADER} maps to
     * {@link SendTap#POST_FADER}. To select {@link SendTap#PRE_INSERTS}, use
     * {@link #setTap(SendTap)} instead.
     *
     * @param mode the send mode
     */
    public void setMode(SendMode mode) {
        this.tap = modeToTap(Objects.requireNonNull(mode, "mode must not be null"));
    }

    /**
     * Returns the tap point at which this send draws audio from the channel.
     *
     * @return the tap point (never {@code null})
     */
    public SendTap getTap() {
        return tap;
    }

    /**
     * Sets the tap point at which this send draws audio from the channel.
     *
     * @param tap the tap point
     */
    public void setTap(SendTap tap) {
        this.tap = Objects.requireNonNull(tap, "tap must not be null");
    }

    private static SendTap modeToTap(SendMode mode) {
        return mode == SendMode.PRE_FADER ? SendTap.PRE_FADER : SendTap.POST_FADER;
    }
}
