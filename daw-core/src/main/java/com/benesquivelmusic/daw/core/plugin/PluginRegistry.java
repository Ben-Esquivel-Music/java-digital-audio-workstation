package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.plugin.clap.ClapPluginHost;
import com.benesquivelmusic.daw.core.plugin.clap.ClapPluginScanner;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.ExternalPluginHost;

import java.net.URLClassLoader;
import java.nio.file.Path;
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
 *
 * <p>The registry also supports CLAP native plugin discovery via
 * {@link #scanClapPlugins()} and registration of native plugin hosts via
 * {@link #registerNativePlugin(ExternalPluginHost)}.</p>
 */
public final class PluginRegistry {

    private final List<ExternalPluginEntry> entries = new ArrayList<>();
    private final Map<ExternalPluginEntry, DawPlugin> loadedPlugins = new LinkedHashMap<>();
    private final Map<ExternalPluginEntry, URLClassLoader> classLoaders = new LinkedHashMap<>();
    private final List<ExternalPluginHost> nativePlugins = new ArrayList<>();

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
        ExternalPluginLoader.closeQuietly(classLoaders.remove(entry));
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
     * Registers a native plugin host (e.g., CLAP, LV2) in the registry.
     *
     * @param host the native plugin host to register
     */
    public void registerNativePlugin(ExternalPluginHost host) {
        Objects.requireNonNull(host, "host must not be null");
        nativePlugins.add(host);
    }

    /**
     * Unregisters a native plugin host and disposes it.
     *
     * @param host the host to remove
     * @return {@code true} if the host was registered and removed
     */
    public boolean unregisterNativePlugin(ExternalPluginHost host) {
        Objects.requireNonNull(host, "host must not be null");
        if (!nativePlugins.remove(host)) {
            return false;
        }
        host.dispose();
        return true;
    }

    /**
     * Returns an unmodifiable view of the registered native plugin hosts.
     *
     * @return the list of native plugin hosts
     */
    public List<ExternalPluginHost> getNativePlugins() {
        return Collections.unmodifiableList(nativePlugins);
    }

    /**
     * Scans the default CLAP plugin directories and returns discovered plugin paths.
     *
     * @return a list of paths to discovered {@code .clap} files
     */
    public List<Path> scanClapPlugins() {
        return new ClapPluginScanner().scan();
    }

    /**
     * Scans the specified directories for CLAP plugins.
     *
     * @param searchPaths the directories to scan
     * @return a list of paths to discovered {@code .clap} files
     */
    public List<Path> scanClapPlugins(List<Path> searchPaths) {
        return new ClapPluginScanner(searchPaths).scan();
    }

    /**
     * Creates a CLAP plugin host for the first plugin in the given library.
     *
     * <p>The returned host is <b>not</b> automatically registered or initialized.
     * Call {@link ClapPluginHost#initialize(com.benesquivelmusic.daw.sdk.plugin.PluginContext)}
     * and {@link #registerNativePlugin(ExternalPluginHost)} after creation.</p>
     *
     * @param libraryPath path to the {@code .clap} shared library
     * @return a new CLAP plugin host
     */
    public ClapPluginHost createClapHost(Path libraryPath) {
        Objects.requireNonNull(libraryPath, "libraryPath must not be null");
        return new ClapPluginHost(libraryPath);
    }

    /**
     * Disposes all loaded plugins and clears the registry.
     */
    public void disposeAll() {
        for (DawPlugin plugin : loadedPlugins.values()) {
            plugin.dispose();
        }
        for (URLClassLoader cl : classLoaders.values()) {
            ExternalPluginLoader.closeQuietly(cl);
        }
        for (ExternalPluginHost host : nativePlugins) {
            host.dispose();
        }
        classLoaders.clear();
        loadedPlugins.clear();
        entries.clear();
        nativePlugins.clear();
    }

}
