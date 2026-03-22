package com.benesquivelmusic.daw.sdk.plugin;

/**
 * Enumerates the external plugin format standards supported by the DAW.
 *
 * <p>These represent native audio plugin formats loaded via the
 * Foreign Function &amp; Memory API (JEP 454) rather than Java-based
 * {@link DawPlugin} implementations loaded from JAR files.</p>
 */
public enum ExternalPluginFormat {

    /**
     * CLever Audio Plugin format.
     *
     * <p>A modern, open-source plugin standard with a clean C API designed
     * for straightforward FFM integration. CLAP plugins are distributed
     * as platform-specific shared libraries ({@code .clap} bundles).</p>
     *
     * @see <a href="https://github.com/free-audio/clap">CLAP specification</a>
     */
    CLAP,

    /**
     * LADSPA Version 2 plugin format.
     *
     * <p>An open-source, extensible plugin standard commonly used on Linux.
     * LV2 plugins are distributed as bundles containing a shared library
     * and TTL metadata files.</p>
     *
     * @see <a href="https://lv2plug.in/">LV2 specification</a>
     */
    LV2
}
