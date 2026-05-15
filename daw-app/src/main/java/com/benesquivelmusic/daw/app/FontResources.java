package com.benesquivelmusic.daw.app;

import java.util.List;

/**
 * Single source of truth for the bundled JetBrains Mono TTF resources
 * (story 266 / UI Design Book §3.2). Consumed by
 * {@link DawApplication#start} at runtime (to register the family with
 * JavaFX) and by {@code FontResourcesTest} at build time (to verify the
 * .ttf files are on the classpath and well-formed).
 *
 * <p>Paths are absolute classpath resources — i.e. the form
 * {@link Class#getResourceAsStream(String)} accepts when the lookup is
 * rooted at the classpath rather than the class's package.
 */
public final class FontResources {

    /**
     * Absolute classpath directory containing the bundled JetBrains Mono
     * weights and the OFL licence/attribution files. Always ends with a
     * trailing slash so callers can simply concatenate a filename.
     */
    public static final String JETBRAINS_MONO_DIR =
            "/com/benesquivelmusic/daw/app/ui/fonts/jetbrains-mono/";

    /**
     * Bundled JetBrains Mono weights — the three the {@code -font-mono}
     * CSS stack picks up at runtime. Adding a new weight (e.g. Light or
     * ExtraBold) means dropping the .ttf into the directory and adding
     * its filename here; {@link DawApplication#start} and
     * {@code FontResourcesTest} will both pick it up.
     */
    public static final List<String> JETBRAINS_MONO_WEIGHTS = List.of(
            "JetBrainsMono-Regular.ttf",
            "JetBrainsMono-Medium.ttf",
            "JetBrainsMono-Bold.ttf");

    /** Filename of the OFL licence shipped alongside the weights. */
    public static final String LICENSE_FILENAME = "LICENSE";

    private FontResources() {}
}
