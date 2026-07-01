package com.benesquivelmusic.daw.sdk.editor;

/**
 * Categorises a plugin for the plugin browser (Plugin View Design Book §4.4,
 * §6.7). The category drives which section of the browser a plugin appears in
 * and replaces the keyword-sniffing heuristic the legacy install dialog used
 * to guess a plugin's kind from its name.
 *
 * <p>Declared on {@link com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor}
 * and carried in the {@code META-INF/daw-plugin.json} manifest
 * ({@link PluginManifest}). A plugin that does not declare a category defaults
 * to {@link #UTILITY}.</p>
 */
public enum PluginCategory {

    /** Compressors, limiters, gates, expanders, transient shapers. */
    DYNAMICS("Dynamics"),

    /** Parametric / graphic EQs and filters. */
    EQ_AND_FILTER("EQ & Filter"),

    /** Chorus, flanger, phaser, tremolo and other modulation effects. */
    MODULATION("Modulation"),

    /** Reverbs, delays and other time-based effects. */
    REVERB_AND_DELAY("Reverb & Delay"),

    /** Panners, binaural / immersive and mid-side tools. */
    SPATIAL("Spatial"),

    /** Gain, dither, meters and general-purpose utilities. Default category. */
    UTILITY("Utility"),

    /** Spectrum analyzers, oscilloscopes, loudness and correlation meters. */
    ANALYZER("Analyzer"),

    /** Virtual instruments that generate audio. */
    INSTRUMENT("Instrument"),

    /** MIDI effects (arpeggiators, chord generators, note filters). */
    MIDI_EFFECT("MIDI Effect");

    private final String displayName;

    PluginCategory(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns the human-readable label for this category (for example
     * {@code "EQ & Filter"}), suitable for a browser section header.
     *
     * @return the display name, never {@code null}
     */
    public String displayName() {
        return displayName;
    }
}
