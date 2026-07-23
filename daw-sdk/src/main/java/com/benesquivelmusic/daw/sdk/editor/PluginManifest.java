package com.benesquivelmusic.daw.sdk.editor;

import java.util.Objects;

import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;

/**
 * The parsed form of a plugin's {@code META-INF/daw-plugin.json} manifest
 * (Plugin View Design Book §4.5). A manifest declares everything a
 * {@link PluginDescriptor} exposes plus the fully-qualified name of the
 * {@link com.benesquivelmusic.daw.sdk.plugin.DawPlugin} implementation class, so
 * the host can install a plugin from a dropped JAR without the user ever typing a
 * class name (§1.4, §6.8).
 *
 * <p>Read with {@link PluginManifestReader}, written with
 * {@link PluginManifestWriter}. A JAR may carry a single manifest object or a
 * top-level array of them — a <em>bundle</em> declaring several plugins that the
 * §6.8 install flow registers together (story 303). Either form round-trips
 * through the reader/writer to equal manifests.</p>
 *
 * @param pluginClass the fully-qualified class name of the {@code DawPlugin}
 *                    implementation (never {@code null} or blank)
 * @param descriptor  the plugin metadata this manifest declares (never {@code null})
 */
public record PluginManifest(String pluginClass, PluginDescriptor descriptor) {
    public PluginManifest {
        Objects.requireNonNull(pluginClass, "pluginClass must not be null");
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        if (pluginClass.isBlank()) {
            throw new IllegalArgumentException("pluginClass must not be blank");
        }
    }
}
