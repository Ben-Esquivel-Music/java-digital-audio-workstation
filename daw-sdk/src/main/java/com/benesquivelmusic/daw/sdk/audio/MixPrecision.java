package com.benesquivelmusic.daw.sdk.audio;

/**
 * The numeric precision used by the DAW's internal mix (summing) bus.
 *
 * <p>The summing bus is the stage where per-channel audio is gain-staged,
 * routed to sends, and summed into the master output. Sessions with many
 * active tracks accumulate rounding error in 32-bit float summation: every
 * professional DAW (Pro Tools HDX, Logic, Cubase, Studio One, Reaper,
 * Ableton) therefore sums in 64-bit {@code double} internally, even when
 * individual plugins process in 32-bit {@code float}.</p>
 *
 * <ul>
 *   <li>{@link #FLOAT_32} — legacy single-precision summing. Selectable for
 *       very low-CPU machines where the extra memory bandwidth of a 64-bit
 *       bus is not acceptable. Bit-exact with pre-existing DAW renders.</li>
 *   <li>{@link #DOUBLE_64} — double-precision summing bus. The default.
 *       Roughly doubles mix-bus memory bandwidth but total CPU impact is
 *       typically modest because plugin processing still runs at each
 *       plugin's preferred precision.</li>
 * </ul>
 *
 * <p>Individual plugins may opt in to double-precision I/O by overriding
 * {@link AudioProcessor#supportsDouble()} and
 * {@link AudioProcessor#processDouble(double[][], double[][], int)};
 * plugins that do not opt in are wrapped in a transparent {@code double
 * -> float -> double} adapter around their {@code float} processing
 * callback.</p>
 */
public enum MixPrecision {

    /** 32-bit single-precision {@code float} summing bus (legacy). */
    FLOAT_32,

    /** 64-bit double-precision {@code double} summing bus (default). */
    DOUBLE_64;

    /** The default precision for new sessions: {@link #DOUBLE_64}. */
    public static final MixPrecision DEFAULT = DOUBLE_64;

    /**
     * Returns the number of bytes occupied by a single sample at this
     * precision. Useful for memory-bandwidth estimation.
     *
     * @return {@code 4} for {@link #FLOAT_32}, {@code 8} for {@link #DOUBLE_64}
     */
    public int bytesPerSample() {
        return this == DOUBLE_64 ? Double.BYTES : Float.BYTES;
    }
}
