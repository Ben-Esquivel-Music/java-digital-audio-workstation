package com.benesquivelmusic.daw.core.midi;

import java.util.Objects;

/**
 * Immutable preset configuration for a virtual keyboard instrument.
 *
 * <p>A keyboard preset bundles together all the settings needed to configure
 * the virtual keyboard for a particular instrument sound: the General MIDI
 * bank/program selection, the playable octave range, a transposition offset,
 * the default velocity, and the velocity response curve.</p>
 *
 * <p>Several factory methods provide common General MIDI presets
 * (piano, organ, strings, etc.).</p>
 *
 * @param name            human-readable preset name (e.g., "Grand Piano")
 * @param bank            MIDI bank number (0–16383)
 * @param program         MIDI program number (0–127)
 * @param lowestOctave    the lowest octave displayed on the virtual keyboard (−1 to 9)
 * @param highestOctave   the highest octave displayed on the virtual keyboard (−1 to 9)
 * @param transpose       semitone transposition offset (−48 to +48)
 * @param defaultVelocity the default MIDI velocity for note-on events (1–127)
 * @param velocityCurve   the velocity response curve
 */
public record KeyboardPreset(
        String name,
        int bank,
        int program,
        int lowestOctave,
        int highestOctave,
        int transpose,
        int defaultVelocity,
        VelocityCurve velocityCurve
) {

    /** Maximum valid bank number (14-bit). */
    public static final int MAX_BANK = 16383;

    /** Maximum valid program number (7-bit). */
    public static final int MAX_PROGRAM = 127;

    /** Minimum allowed octave value. */
    public static final int MIN_OCTAVE = -1;

    /** Maximum allowed octave value. */
    public static final int MAX_OCTAVE = 9;

    /** Maximum absolute transposition in semitones. */
    public static final int MAX_TRANSPOSE = 48;

    /** Maximum MIDI velocity. */
    public static final int MAX_VELOCITY = 127;

    public KeyboardPreset {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (bank < 0 || bank > MAX_BANK) {
            throw new IllegalArgumentException("bank must be 0–" + MAX_BANK + ": " + bank);
        }
        if (program < 0 || program > MAX_PROGRAM) {
            throw new IllegalArgumentException("program must be 0–" + MAX_PROGRAM + ": " + program);
        }
        if (lowestOctave < MIN_OCTAVE || lowestOctave > MAX_OCTAVE) {
            throw new IllegalArgumentException(
                    "lowestOctave must be " + MIN_OCTAVE + "–" + MAX_OCTAVE + ": " + lowestOctave);
        }
        if (highestOctave < MIN_OCTAVE || highestOctave > MAX_OCTAVE) {
            throw new IllegalArgumentException(
                    "highestOctave must be " + MIN_OCTAVE + "–" + MAX_OCTAVE + ": " + highestOctave);
        }
        if (highestOctave < lowestOctave) {
            throw new IllegalArgumentException(
                    "highestOctave (%d) must be >= lowestOctave (%d)".formatted(highestOctave, lowestOctave));
        }
        if (transpose < -MAX_TRANSPOSE || transpose > MAX_TRANSPOSE) {
            throw new IllegalArgumentException(
                    "transpose must be −" + MAX_TRANSPOSE + "–+" + MAX_TRANSPOSE + ": " + transpose);
        }
        if (defaultVelocity < 1 || defaultVelocity > MAX_VELOCITY) {
            throw new IllegalArgumentException(
                    "defaultVelocity must be 1–" + MAX_VELOCITY + ": " + defaultVelocity);
        }
        Objects.requireNonNull(velocityCurve, "velocityCurve must not be null");
    }

    // ── Factory presets ────────────────────────────────────────────────

    /** Acoustic Grand Piano — GM program 0, bank 0. */
    public static KeyboardPreset grandPiano() {
        return new KeyboardPreset("Grand Piano", 0, 0, 2, 6, 0, 100, VelocityCurve.LINEAR);
    }

    /** Electric Piano (Rhodes) — GM program 4, bank 0. */
    public static KeyboardPreset electricPiano() {
        return new KeyboardPreset("Electric Piano", 0, 4, 2, 6, 0, 90, VelocityCurve.SOFT);
    }

    /** Church Organ — GM program 19, bank 0. */
    public static KeyboardPreset organ() {
        return new KeyboardPreset("Organ", 0, 19, 2, 6, 0, 100, VelocityCurve.FIXED);
    }

    /** String Ensemble — GM program 48, bank 0. */
    public static KeyboardPreset strings() {
        return new KeyboardPreset("Strings", 0, 48, 3, 6, 0, 80, VelocityCurve.SOFT);
    }

    /** Synth Lead (Square) — GM program 80, bank 0. */
    public static KeyboardPreset synthLead() {
        return new KeyboardPreset("Synth Lead", 0, 80, 3, 6, 0, 100, VelocityCurve.HARD);
    }

    /** Synth Pad (New Age) — GM program 88, bank 0. */
    public static KeyboardPreset synthPad() {
        return new KeyboardPreset("Synth Pad", 0, 88, 2, 6, 0, 80, VelocityCurve.SOFT);
    }

    /** Acoustic Bass — GM program 32, bank 0. */
    public static KeyboardPreset bass() {
        return new KeyboardPreset("Bass", 0, 32, 1, 3, 0, 100, VelocityCurve.LINEAR);
    }

    /** Choir Aahs — GM program 52, bank 0. */
    public static KeyboardPreset choir() {
        return new KeyboardPreset("Choir", 0, 52, 3, 5, 0, 90, VelocityCurve.SOFT);
    }

    /**
     * Returns an array of all built-in factory presets.
     *
     * @return the factory presets
     */
    public static KeyboardPreset[] factoryPresets() {
        return new KeyboardPreset[]{
                grandPiano(),
                electricPiano(),
                organ(),
                strings(),
                synthLead(),
                synthPad(),
                bass(),
                choir()
        };
    }

    /**
     * Returns a copy of this preset with a different transposition.
     *
     * @param newTranspose the new transpose value in semitones
     * @return a new preset with the updated transposition
     */
    public KeyboardPreset withTranspose(int newTranspose) {
        return new KeyboardPreset(name, bank, program, lowestOctave, highestOctave,
                newTranspose, defaultVelocity, velocityCurve);
    }

    /**
     * Returns a copy of this preset with a different default velocity.
     *
     * @param newVelocity the new default velocity
     * @return a new preset with the updated velocity
     */
    public KeyboardPreset withDefaultVelocity(int newVelocity) {
        return new KeyboardPreset(name, bank, program, lowestOctave, highestOctave,
                transpose, newVelocity, velocityCurve);
    }

    /**
     * Returns a copy of this preset with a different velocity curve.
     *
     * @param newCurve the new velocity curve
     * @return a new preset with the updated curve
     */
    public KeyboardPreset withVelocityCurve(VelocityCurve newCurve) {
        return new KeyboardPreset(name, bank, program, lowestOctave, highestOctave,
                transpose, defaultVelocity, newCurve);
    }

    /**
     * Returns a copy of this preset with a different octave range.
     *
     * @param newLowest  the new lowest octave
     * @param newHighest the new highest octave
     * @return a new preset with the updated octave range
     */
    public KeyboardPreset withOctaveRange(int newLowest, int newHighest) {
        return new KeyboardPreset(name, bank, program, newLowest, newHighest,
                transpose, defaultVelocity, velocityCurve);
    }
}
