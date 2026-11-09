package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;

import java.util.Objects;

/**
 * A plugin constructed and initialized, not yet activated, with what releases
 * it: {@code disposal} disposes the plugin, then closes the class loader that
 * loaded it (story 334).
 *
 * @param plugin   the initialized plugin
 * @param disposal disposes {@code plugin} and closes its loader
 */
public record LoadedPlugin(DawPlugin plugin, Runnable disposal) {

    public LoadedPlugin {
        Objects.requireNonNull(plugin, "plugin must not be null");
        Objects.requireNonNull(disposal, "disposal must not be null");
    }
}
