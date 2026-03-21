package com.benesquivelmusic.daw.core.plugin;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Represents the configuration needed to load an external plugin from a JAR file.
 *
 * <p>Users provide the path to a pre-compiled JAR and the fully qualified class name
 * of their {@link com.benesquivelmusic.daw.sdk.plugin.DawPlugin} implementation.
 * This eliminates the need for {@code META-INF/services} configuration inside the JAR.</p>
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
