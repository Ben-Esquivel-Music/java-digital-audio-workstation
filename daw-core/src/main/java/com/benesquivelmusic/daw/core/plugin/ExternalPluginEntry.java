package com.benesquivelmusic.daw.core.plugin;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Represents the configuration needed to load an external plugin from a JAR file:
 * the JAR and the fully qualified name of the
 * {@link com.benesquivelmusic.daw.sdk.plugin.DawPlugin} class it holds, as the
 * JAR's manifest ({@code META-INF/daw-plugin.json}) declares it — no
 * {@code META-INF/services} configuration is needed inside the JAR. An
 * installed plugin's entry names the registry's own copy of the JAR (story
 * 334); its identity is the manifest's plugin id ({@link InstalledPlugin}),
 * never this path.
 *
 * @param jarPath   the filesystem path to the plugin JAR file
 * @param className the fully qualified class name of the DawPlugin implementation
 */
public record ExternalPluginEntry(Path jarPath, String className) {

    public ExternalPluginEntry {
        Objects.requireNonNull(jarPath, "jarPath must not be null");
        Objects.requireNonNull(className, "className must not be null");
        if (className.isBlank()) {
            throw new IllegalArgumentException("className must not be blank");
        }
    }
}
