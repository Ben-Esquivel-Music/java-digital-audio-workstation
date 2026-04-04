package com.benesquivelmusic.daw.core.midi;

/**
 * Velocity response curves for a virtual keyboard.
 *
 * <p>Determines how the raw velocity (e.g., from a mouse click or key press)
 * is mapped to a MIDI velocity value (0–127). Different curves suit different
 * playing styles and instrument types.</p>
 */
public enum VelocityCurve {

    /**
     * Linear mapping — raw input maps 1:1 to MIDI velocity.
     */
    LINEAR {
        @Override
        public int apply(int rawVelocity) {
            return clamp(rawVelocity);
        }
    },

    /**
     * Soft curve — lower velocities are boosted, making it easier to play
     * quietly. Uses a square-root curve.
     */
    SOFT {
        @Override
        public int apply(int rawVelocity) {
            int clamped = clamp(rawVelocity);
            double normalized = clamped / 127.0;
            return (int) Math.round(Math.sqrt(normalized) * 127.0);
        }
    },

    /**
     * Hard curve — higher velocities are emphasized, requiring more force
     * for loud notes. Uses a squared curve.
     */
    HARD {
        @Override
        public int apply(int rawVelocity) {
            int clamped = clamp(rawVelocity);
            double normalized = clamped / 127.0;
            return (int) Math.round(normalized * normalized * 127.0);
        }
    },

    /**
     * Fixed velocity — all notes are output at exactly the configured
     * velocity, regardless of input.
     */
    FIXED {
        @Override
        public int apply(int rawVelocity) {
            return clamp(rawVelocity);
        }
    };

    /** Maximum MIDI velocity value. */
    public static final int MAX_VELOCITY = 127;

    /**
     * Applies this velocity curve to the given raw velocity.
     *
     * @param rawVelocity the input velocity (0–127)
     * @return the mapped MIDI velocity (0–127)
     */
    public abstract int apply(int rawVelocity);

    /**
     * Clamps a velocity value to the valid MIDI range [0, 127].
     *
     * @param velocity the raw velocity
     * @return the clamped value
     */
    static int clamp(int velocity) {
        return Math.clamp(velocity, 0, MAX_VELOCITY);
    }
}
