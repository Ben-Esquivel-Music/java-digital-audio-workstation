package com.benesquivelmusic.daw.sdk.transport;

/**
 * Bar-based pre-roll and post-roll configuration for recording and playback.
 *
 * <p>Pre-roll causes playback to begin a fixed number of bars <em>before</em>
 * the playhead (or before punch-in when a {@link PunchRegion} is armed), giving
 * the performer time to prepare. Post-roll causes the transport to continue
 * running a fixed number of bars after the stop command (or after punch-out),
 * capturing release tails such as reverb without recording new input.</p>
 *
 * <p>Pro Tools and Cubase both call this feature "pre-roll / post-roll";
 * Logic calls the pre-roll component "pre-count." Bars — not seconds — are
 * the musically correct unit and the standard across professional DAWs.</p>
 *
 * <p>During a pre-roll or post-roll window the click track <em>continues</em>
 * to sound, but input is <em>not</em> captured. This is the responsibility of
 * the recording pipeline; this record is a pure value type describing the
 * configuration.</p>
 *
 * @param preBars  number of bars of pre-roll before the start/punch-in
 *                 (must be &ge; 0)
 * @param postBars number of bars of post-roll after the stop/punch-out
 *                 (must be &ge; 0)
 * @param enabled  {@code true} to apply pre-roll/post-roll to transport
 *                 commands, {@code false} to keep the configuration stored
 *                 but inert (e.g. for a UI toggle)
 */
public record PreRollPostRoll(int preBars, int postBars, boolean enabled) {

    /** A disabled configuration with zero pre-roll and post-roll. */
    public static final PreRollPostRoll DISABLED = new PreRollPostRoll(0, 0, false);

    /**
     * Canonical constructor; validates invariants.
     *
     * @throws IllegalArgumentException if {@code preBars} or {@code postBars}
     *                                  is negative
     */
    public PreRollPostRoll {
        if (preBars < 0) {
            throw new IllegalArgumentException(
                    "preBars must not be negative: " + preBars);
        }
        if (postBars < 0) {
            throw new IllegalArgumentException(
                    "postBars must not be negative: " + postBars);
        }
    }

    /**
     * Creates an enabled configuration with the given bar counts.
     *
     * @param preBars  pre-roll bar count (&ge; 0)
     * @param postBars post-roll bar count (&ge; 0)
     * @return an enabled {@code PreRollPostRoll}
     */
    public static PreRollPostRoll enabled(int preBars, int postBars) {
        return new PreRollPostRoll(preBars, postBars, true);
    }

    /**
     * Returns a copy of this configuration with the given {@code enabled} flag.
     *
     * @param enabled the new enabled flag
     * @return a new {@code PreRollPostRoll} with the same bar counts and the
     *         supplied enabled flag
     */
    public PreRollPostRoll withEnabled(boolean enabled) {
        return new PreRollPostRoll(preBars, postBars, enabled);
    }

    /**
     * Returns the pre-roll duration, in beats, for a time signature whose
     * numerator gives the number of beats per bar (as reported by
     * {@code Transport.getTimeSignatureNumerator()}).
     *
     * @param beatsPerBar beats per bar (must be positive)
     * @return {@code preBars * beatsPerBar}
     */
    public double preRollBeats(int beatsPerBar) {
        if (beatsPerBar <= 0) {
            throw new IllegalArgumentException(
                    "beatsPerBar must be positive: " + beatsPerBar);
        }
        return (double) preBars * beatsPerBar;
    }

    /**
     * Returns the post-roll duration, in beats, for a time signature whose
     * numerator gives the number of beats per bar.
     *
     * @param beatsPerBar beats per bar (must be positive)
     * @return {@code postBars * beatsPerBar}
     */
    public double postRollBeats(int beatsPerBar) {
        if (beatsPerBar <= 0) {
            throw new IllegalArgumentException(
                    "beatsPerBar must be positive: " + beatsPerBar);
        }
        return (double) postBars * beatsPerBar;
    }
}
