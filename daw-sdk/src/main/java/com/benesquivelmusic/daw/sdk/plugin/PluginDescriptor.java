package com.benesquivelmusic.daw.sdk.plugin;

import java.util.Objects;

import com.benesquivelmusic.daw.sdk.editor.PluginCategory;

/**
 * Immutable descriptor containing metadata about a DAW plugin.
 *
 * @param id       unique identifier for the plugin (e.g., {@code "com.example.reverb"})
 * @param name     human-readable name of the plugin
 * @param version  version string (e.g., {@code "1.0.0"})
 * @param vendor   vendor or author name
 * @param type     the type of plugin
 * @param category the browser category (Plugin View Design Book §4.4); defaults
 *                 to {@link PluginCategory#UTILITY} via the legacy five-argument
 *                 constructor
 * @param iconHint a stable iconographic hint the host maps to its own icon pack
 *                 (e.g. {@code "compressor"}, {@code "reverb"}); the empty string
 *                 when the plugin declares none (§4.4)
 */
public record PluginDescriptor(
        String id,
        String name,
        String version,
        String vendor,
        PluginType type,
        PluginCategory category,
        String iconHint
) {
    public PluginDescriptor {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(vendor, "vendor must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(iconHint, "iconHint must not be null");

        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    /**
     * Legacy five-argument constructor, preserved for backwards compatibility
     * (Plugin View Design Book §4.4, §8.1.3). Defaults {@link #category()} to
     * {@link PluginCategory#UTILITY} and {@link #iconHint()} to the empty string,
     * so every existing {@code new PluginDescriptor(id, name, version, vendor,
     * type)} call site keeps compiling unchanged.
     *
     * @param id      unique identifier for the plugin
     * @param name    human-readable name of the plugin
     * @param version version string
     * @param vendor  vendor or author name
     * @param type    the type of plugin
     */
    public PluginDescriptor(String id, String name, String version, String vendor, PluginType type) {
        this(id, name, version, vendor, type, PluginCategory.UTILITY, "");
    }
}
