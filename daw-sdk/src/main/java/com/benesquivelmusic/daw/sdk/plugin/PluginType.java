package com.benesquivelmusic.daw.sdk.plugin;

/**
 * Enumerates the types of plugins supported by the DAW.
 */
public enum PluginType {

    /** An audio effect processor (e.g., reverb, delay, EQ). */
    EFFECT,

    /** A virtual instrument that generates audio (e.g., synthesizer, sampler). */
    INSTRUMENT,

    /** An audio analyzer (e.g., spectrum analyzer, level meter). */
    ANALYZER,

    /** A MIDI effect processor (e.g., arpeggiator, chord generator). */
    MIDI_EFFECT
}
