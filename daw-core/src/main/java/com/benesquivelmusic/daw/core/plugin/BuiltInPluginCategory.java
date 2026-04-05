package com.benesquivelmusic.daw.core.plugin;

/**
 * Categories for grouping built-in plugins in the Plugins menu.
 */
public enum BuiltInPluginCategory {

    /** A virtual instrument that generates audio (e.g., virtual keyboard, synthesizer). */
    INSTRUMENT("Instruments"),

    /** An audio effect processor (e.g., EQ, compressor, reverb). */
    EFFECT("Effects"),

    /** An audio analyzer (e.g., spectrum analyzer, level meter). */
    ANALYZER("Analyzers"),

    /** A utility plugin (e.g., tuner, metronome). */
    UTILITY("Utilities");

    private final String displayName;

    BuiltInPluginCategory(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns a human-readable name suitable for display in menus and headers.
     *
     * @return the display name, never {@code null}
     */
    public String displayName() {
        return displayName;
    }
}
