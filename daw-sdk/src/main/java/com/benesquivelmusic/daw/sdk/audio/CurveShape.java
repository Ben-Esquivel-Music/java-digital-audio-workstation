package com.benesquivelmusic.daw.sdk.audio;

/**
 * The interpolation shape used between two breakpoints of a
 * {@link ClipGainEnvelope}.
 *
 * <p>Given a normalized progress {@code t \u2208 [0, 1]} between two
 * breakpoints (with dB values {@code a} at {@code t=0} and {@code b} at
 * {@code t=1}), the shape defines the blending weight {@code w(t)} so that
 * the interpolated dB value is {@code a + (b - a) * w(t)}.</p>
 *
 * <ul>
 *   <li>{@link #LINEAR} &ndash; straight-line interpolation in dB.
 *       {@code w(t) = t}.</li>
 *   <li>{@link #EXPONENTIAL} &ndash; slow start, fast finish in dB.
 *       {@code w(t) = t * t}.</li>
 *   <li>{@link #S_CURVE} &ndash; slow\u2013fast\u2013slow smoothstep.
 *       {@code w(t) = t * t * (3 - 2 * t)}.</li>
 * </ul>
 */
public enum CurveShape {

    /** Straight-line dB interpolation. */
    LINEAR {
        @Override
        protected double weightRaw(double t) {
            return t;
        }
    },

    /** Exponential (quadratic) ease-in curve. */
    EXPONENTIAL {
        @Override
        protected double weightRaw(double t) {
            return t * t;
        }
    },

    /** Smooth S-shaped interpolation (cubic smoothstep). */
    S_CURVE {
        @Override
        protected double weightRaw(double t) {
            return t * t * (3.0 - 2.0 * t);
        }
    };

    /**
     * Returns the blending weight for the given normalized progress,
     * clamping {@code t} to {@code [0, 1]} before evaluation so callers
     * don't have to pre-clamp.
     *
     * @param t normalized progress; values outside {@code [0, 1]} are clamped
     * @return the blending weight used for interpolation, in {@code [0, 1]}
     */
    public final double weight(double t) {
        return weightRaw(Math.clamp(t, 0.0, 1.0));
    }

    /**
     * Shape-specific weight function evaluated on a pre-clamped {@code t}.
     *
     * @param t normalized progress, guaranteed to be in {@code [0, 1]}
     */
    protected abstract double weightRaw(double t);
}
