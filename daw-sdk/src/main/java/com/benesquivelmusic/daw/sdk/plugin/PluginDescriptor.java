package com.benesquivelmusic.daw.sdk.plugin;

import java.util.Objects;

/**
 * Immutable descriptor containing metadata about a DAW plugin.
 *
 * @param id      unique identifier for the plugin (e.g., {@code "com.example.reverb"})
 * @param name    human-readable name of the plugin
 * @param version version string (e.g., {@code "1.0.0"})
 * @param vendor  vendor or author name
 * @param type    the type of plugin
 */
public record PluginDescriptor(
        String id,
        String name,
        String version,
        String vendor,
        PluginType type
) {
    public PluginDescriptor {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(vendor, "vendor must not be null");
        Objects.requireNonNull(type, "type must not be null");

        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
