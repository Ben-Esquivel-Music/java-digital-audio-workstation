package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

/**
 * Pan laws applied by the {@link Mixer} summing stages (story 322, Audio
 * Engine Wiring Design Book §5.6 "Master / return pan").
 *
 * <p>Two laws live in the mixer and they are deliberately different:</p>
 * <ul>
 *   <li><b>Channel law</b> (unchanged, applied inline by the channel summing
 *       in {@code Mixer}): the raw constant-power taper
 *       {@code angle = (pan + 1)·π/4; L = cos(angle); R = sin(angle)} — centre
 *       is −3 dB per lane so an equal-power image is preserved as a source
 *       sweeps across the field.</li>
 *   <li><b>Bus law</b> (this class, applied to return buses and the master):
 *       the <em>unity-at-centre</em> constant-power taper
 *       {@code L = min(1, √2·cos(angle)); R = min(1, √2·sin(angle))} — centre
 *       is {@code (1, 1)}, hard left is {@code (1, 0)}, hard right is
 *       {@code (0, 1)}, and the attenuated lane follows the constant-power
 *       cos/sin curve. A bus therefore never exceeds its fader gain, and a
 *       bus at centre passes its fader gain unchanged. Rationale: the raw
 *       −3 dB-at-centre channel law on a bus would drop every existing
 *       project's master by 3 dB the moment the pan sliders went live.</li>
 * </ul>
 *
 * <p>Both bus methods are pure, allocation-free and lock-free; the mixer
 * evaluates them once per bus per block and threads the resulting per-lane
 * gains into its lane loops. Buses with a single lane are not panned; lanes
 * beyond the stereo pair (surround) receive the fader gain only.</p>
 */
public final class PanLaw {

    private static final double SQRT_2 = Math.sqrt(2.0);

    private PanLaw() {
        // static utility
    }

    /**
     * Left-lane gain of the unity-at-centre bus law for {@code pan} in
     * {@code [-1, 1]}: {@code 1.0} from hard left through centre, then the
     * constant-power cosine taper down to {@code 0.0} at hard right.
     *
     * @param pan the bus pan position (−1.0 = full left, 0.0 = centre, 1.0 = full right)
     * @return the left-lane multiplier in {@code [0, 1]}
     */
    @RealTimeSafe
    public static double busLeftGain(double pan) {
        if (pan == 0.0) {
            // Exact unity at centre: every project whose buses were never
            // panned renders bit-identically to the pre-322 volume-only path.
            return 1.0;
        }
        double angle = (pan + 1.0) * 0.25 * Math.PI;
        return Math.min(1.0, SQRT_2 * Math.cos(angle));
    }

    /**
     * Right-lane gain of the unity-at-centre bus law for {@code pan} in
     * {@code [-1, 1]}: the constant-power sine taper up from {@code 0.0} at
     * hard left to {@code 1.0} at centre, then {@code 1.0} through hard
     * right.
     *
     * @param pan the bus pan position (−1.0 = full left, 0.0 = centre, 1.0 = full right)
     * @return the right-lane multiplier in {@code [0, 1]}
     */
    @RealTimeSafe
    public static double busRightGain(double pan) {
        if (pan == 0.0) {
            return 1.0;
        }
        double angle = (pan + 1.0) * 0.25 * Math.PI;
        return Math.min(1.0, SQRT_2 * Math.sin(angle));
    }
}
