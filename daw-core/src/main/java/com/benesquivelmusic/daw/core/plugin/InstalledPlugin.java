package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.sdk.editor.PluginManifest;

import java.util.Objects;

/**
 * An installed external JAR plugin and the manifest it was installed from —
 * its provenance (story 334, studio rulings E9 and E20). Its identity is the
 * manifest's plugin id, never the JAR path: the path is where this
 * application keeps its copy of the JAR, and {@code originalFileName} is
 * the name of the file the engineer installed.
 *
 * @param entry            the copy of the JAR and the plugin class it holds
 * @param manifest         the manifest the plugin was installed from
 * @param originalFileName the file name of the JAR as the engineer picked it
 */
public record InstalledPlugin(ExternalPluginEntry entry, PluginManifest manifest, String originalFileName) {

    public InstalledPlugin {
        Objects.requireNonNull(entry, "entry must not be null");
        Objects.requireNonNull(manifest, "manifest must not be null");
        Objects.requireNonNull(originalFileName, "originalFileName must not be null");
        if (!manifest.pluginClass().equals(entry.className())) {
            throw new IllegalArgumentException("the manifest declares " + manifest.pluginClass()
                    + ", but the entry loads " + entry.className());
        }
        if (originalFileName.isBlank()) {
            throw new IllegalArgumentException("originalFileName must not be blank");
        }
    }

    /** The plugin's stable id, as its manifest declares it. */
    public String id() {
        return manifest.descriptor().id();
    }

    /** The plugin's version, as its manifest declares it. */
    public String version() {
        return manifest.descriptor().version();
    }

    /** The plugin's name, as its manifest declares it. */
    public String name() {
        return manifest.descriptor().name();
    }
}
