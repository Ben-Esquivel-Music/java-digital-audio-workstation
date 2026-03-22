package com.benesquivelmusic.daw.sdk.midi;

/**
 * Describes a single preset (instrument) within a SoundFont.
 *
 * <p>A SoundFont may contain hundreds of presets organized by bank and
 * program number. The General MIDI standard defines 128 instruments in
 * bank 0, but SoundFonts can extend this with additional banks.</p>
 *
 * @param bank    the MIDI bank number (0–16383)
 * @param program the MIDI program number (0–127)
 * @param name    the human-readable preset name (e.g., "Acoustic Grand Piano")
 */
public record SoundFontPreset(int bank, int program, String name) {

    /** Maximum valid bank number (14-bit). */
    public static final int MAX_BANK = 16383;

    /** Maximum valid program number (7-bit). */
    public static final int MAX_PROGRAM = 127;

    public SoundFontPreset {
        if (bank < 0 || bank > MAX_BANK) {
            throw new IllegalArgumentException("bank must be 0–" + MAX_BANK + ": " + bank);
        }
        if (program < 0 || program > MAX_PROGRAM) {
            throw new IllegalArgumentException("program must be 0–" + MAX_PROGRAM + ": " + program);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be null or blank");
        }
    }
}
