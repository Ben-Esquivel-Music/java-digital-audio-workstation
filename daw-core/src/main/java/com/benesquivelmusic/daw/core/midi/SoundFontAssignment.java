package com.benesquivelmusic.daw.core.midi;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Holds the SoundFont instrument assignment for a MIDI track.
 *
 * <p>Each MIDI track may have a single SoundFont preset assigned to it,
 * identified by the path to the SF2 file and the bank/program numbers
 * that select the instrument within that SoundFont.</p>
 *
 * @param soundFontPath the file-system path to the SF2 file
 * @param bank          the MIDI bank number (0–16383)
 * @param program       the MIDI program number (0–127)
 * @param presetName    the human-readable preset name (e.g., "Acoustic Grand Piano")
 */
public record SoundFontAssignment(Path soundFontPath, int bank, int program, String presetName) {

    /** Maximum valid bank number (14-bit). */
    public static final int MAX_BANK = 16383;

    /** Maximum valid program number (7-bit). */
    public static final int MAX_PROGRAM = 127;

    public SoundFontAssignment {
        Objects.requireNonNull(soundFontPath, "soundFontPath must not be null");
        if (bank < 0 || bank > MAX_BANK) {
            throw new IllegalArgumentException("bank must be 0–" + MAX_BANK + ": " + bank);
        }
        if (program < 0 || program > MAX_PROGRAM) {
            throw new IllegalArgumentException("program must be 0–" + MAX_PROGRAM + ": " + program);
        }
        Objects.requireNonNull(presetName, "presetName must not be null");
        if (presetName.isBlank()) {
            throw new IllegalArgumentException("presetName must not be blank");
        }
    }
}
