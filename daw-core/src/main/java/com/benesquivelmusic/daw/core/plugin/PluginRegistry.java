package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;

import java.io.IOException;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Manages the registration and lifecycle of external DAW plugins.
 *
 * <p>The registry maintains a list of {@link ExternalPluginEntry} configurations
 * and their loaded {@link DawPlugin} instances. Plugins are loaded from external
 * JAR files without requiring {@code META-INF/services} configuration.</p>
 */
public final class PluginRegistry {

    private final List<ExternalPluginEntry> entries = new ArrayList<>();
    private final Map<ExternalPluginEntry, DawPlugin> loadedPlugins = new LinkedHashMap<>();
    private final Map<ExternalPluginEntry, URLClassLoader> classLoaders = new LinkedHashMap<>();

    /**
     * Registers an external plugin entry and attempts to load it.
     *
     * @param entry the external plugin entry to register
     * @return the loaded plugin instance
     * @throws PluginLoadException if the plugin cannot be loaded
     */
    public DawPlugin register(ExternalPluginEntry entry) throws PluginLoadException {
        Objects.requireNonNull(entry, "entry must not be null");
        if (entries.contains(entry)) {
            throw new PluginLoadException("Plugin entry already registered: " + entry);
        }
        var result = ExternalPluginLoader.loadWithClassLoader(entry);
        entries.add(entry);
        loadedPlugins.put(entry, result.plugin());
        classLoaders.put(entry, result.classLoader());
        return result.plugin();
    }

    /**
     * Unregisters an external plugin entry and disposes its loaded plugin.
     *
     * @param entry the entry to remove
     * @return {@code true} if the entry was registered and removed
     */
    public boolean unregister(ExternalPluginEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        if (!entries.remove(entry)) {
            return false;
        }
        DawPlugin plugin = loadedPlugins.remove(entry);
        if (plugin != null) {
            plugin.dispose();
        }
        closeClassLoader(classLoaders.remove(entry));
        return true;
    }

    /**
     * Returns an unmodifiable view of the registered plugin entries.
     *
     * @return the list of registered entries
     */
    public List<ExternalPluginEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    /**
     * Returns an unmodifiable view of the loaded plugins.
     *
     * @return the map of entries to loaded plugin instances
     */
    public Map<ExternalPluginEntry, DawPlugin> getLoadedPlugins() {
        return Collections.unmodifiableMap(loadedPlugins);
    }

    /**
     * Returns the loaded plugin for the given entry, or {@code null} if not loaded.
     *
     * @param entry the plugin entry
     * @return the loaded plugin, or {@code null}
     */
    public DawPlugin getPlugin(ExternalPluginEntry entry) {
        return loadedPlugins.get(entry);
    }

    /**
     * Disposes all loaded plugins and clears the registry.
     */
    public void disposeAll() {
        for (DawPlugin plugin : loadedPlugins.values()) {
            plugin.dispose();
        }
        for (URLClassLoader cl : classLoaders.values()) {
            closeClassLoader(cl);
        }
        classLoaders.clear();
        loadedPlugins.clear();
        entries.clear();
    }

    private static void closeClassLoader(URLClassLoader classLoader) {
        if (classLoader != null) {
            try {
                classLoader.close();
            } catch (IOException ignored) {
                // best-effort cleanup
            }
        }
    }
}
