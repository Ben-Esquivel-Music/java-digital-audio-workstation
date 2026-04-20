package com.benesquivelmusic.daw.sdk.transport;

/**
 * Routing configuration for the metronome click track.
 *
 * <p>Every professional DAW exposes a way to send the click to a dedicated
 * hardware output so that it reaches only the drummer's headphones and is
 * never printed into the overhead / room microphones. Logic calls this
 * "Click Output," Pro Tools' "Click" plugin can be routed to any bus, and
 * Cubase has "Metronome Output." A {@code ClickOutput} value captures the
 * three pieces of state that define this routing: which physical channel
 * the side output feeds, how loud the click is at the output, and whether
 * the click is also mixed into the main control-room bus during tracking.</p>
 *
 * <p>The side output bypasses every track and every bus — it is summed
 * directly into the hardware buffer at {@link #hardwareChannelIndex()} so
 * no microphone can pick it up after the fact. Enabling both
 * {@link #mainMixEnabled()} and {@link #sideOutputEnabled()} is the usual
 * choice when the engineer wants to hear the click in the control room as
 * well as the drummer hearing it in the cue.</p>
 *
 * @param hardwareChannelIndex 0-based physical output channel for the side
 *                             output (must be &ge; 0)
 * @param gain                 linear gain applied to the side-output click,
 *                             {@code [0.0, 1.0]}
 * @param mainMixEnabled       {@code true} to include the click in the main
 *                             control-room mix; {@code false} to mute it
 *                             from the recorded output
 * @param sideOutputEnabled    {@code true} to write the click to the direct
 *                             hardware channel specified by
 *                             {@link #hardwareChannelIndex()};
 *                             {@code false} to leave that output silent
 */
public record ClickOutput(int hardwareChannelIndex,
                          double gain,
                          boolean mainMixEnabled,
                          boolean sideOutputEnabled) {

    /**
     * Default configuration: click is mixed into the main bus only, side
     * output disabled, hardware channel {@code 0}, unity gain. Matches the
     * pre-story-136 behaviour for projects that have no saved routing.
     */
    public static final ClickOutput MAIN_MIX_ONLY =
            new ClickOutput(0, 1.0, true, false);

    /**
     * Canonical constructor; validates invariants.
     *
     * @throws IllegalArgumentException if {@code hardwareChannelIndex} is
     *                                  negative or {@code gain} is outside
     *                                  {@code [0.0, 1.0]}
     */
    public ClickOutput {
        if (hardwareChannelIndex < 0) {
            throw new IllegalArgumentException(
                    "hardwareChannelIndex must not be negative: " + hardwareChannelIndex);
        }
        if (gain < 0.0 || gain > 1.0) {
            throw new IllegalArgumentException(
                    "gain must be between 0.0 and 1.0: " + gain);
        }
    }

    /**
     * Returns a copy of this configuration with the given hardware channel.
     *
     * @param newIndex the new 0-based output channel index
     * @return a new {@code ClickOutput} with the same gain and flags
     */
    public ClickOutput withHardwareChannelIndex(int newIndex) {
        return new ClickOutput(newIndex, gain, mainMixEnabled, sideOutputEnabled);
    }

    /**
     * Returns a copy of this configuration with the given side-output gain.
     *
     * @param newGain the new linear gain in {@code [0.0, 1.0]}
     * @return a new {@code ClickOutput} with the same channel and flags
     */
    public ClickOutput withGain(double newGain) {
        return new ClickOutput(hardwareChannelIndex, newGain, mainMixEnabled, sideOutputEnabled);
    }

    /**
     * Returns a copy of this configuration with the given main-mix flag.
     *
     * @param enabled whether to include the click in the main mix
     * @return a new {@code ClickOutput} with the same routing values
     */
    public ClickOutput withMainMixEnabled(boolean enabled) {
        return new ClickOutput(hardwareChannelIndex, gain, enabled, sideOutputEnabled);
    }

    /**
     * Returns a copy of this configuration with the given side-output flag.
     *
     * @param enabled whether to write the click to the side output
     * @return a new {@code ClickOutput} with the same routing values
     */
    public ClickOutput withSideOutputEnabled(boolean enabled) {
        return new ClickOutput(hardwareChannelIndex, gain, mainMixEnabled, enabled);
    }
}
