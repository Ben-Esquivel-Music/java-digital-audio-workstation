package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Loads {@link DawPlugin} implementations from external JAR files.
 *
 * <p>This loader uses a {@link URLClassLoader} to load the plugin class from
 * the specified JAR file, eliminating the need for plugin developers to create
 * {@code META-INF/services} files. Users simply provide the JAR path and the
 * fully qualified class name of their plugin implementation.</p>
 */
public final class ExternalPluginLoader {

    /**
     * Holds the loaded plugin together with the classloader that loaded it,
     * so the classloader can be closed when the plugin is unloaded.
     */
    public record LoadResult(DawPlugin plugin, URLClassLoader classLoader) {}

    private ExternalPluginLoader() {
        // utility class
    }

    /**
     * Loads a {@link DawPlugin} instance from the given external plugin entry.
     *
     * <p><b>Note:</b> The caller is responsible for closing the returned
     * {@link LoadResult#classLoader()} when the plugin is no longer needed.</p>
     *
     * @param entry the external plugin entry specifying JAR path and class name
     * @return a result containing the plugin instance and its classloader
     * @throws PluginLoadException if the plugin cannot be loaded or instantiated
     */
    public static LoadResult loadWithClassLoader(ExternalPluginEntry entry) throws PluginLoadException {
        Objects.requireNonNull(entry, "entry must not be null");
        return loadWithClassLoader(entry.jarPath(), entry.className());
    }

    /**
     * Loads a {@link DawPlugin} instance from the given external plugin entry.
     *
     * @param entry the external plugin entry specifying JAR path and class name
     * @return a new instance of the plugin
     * @throws PluginLoadException if the plugin cannot be loaded or instantiated
     */
    public static DawPlugin load(ExternalPluginEntry entry) throws PluginLoadException {
        Objects.requireNonNull(entry, "entry must not be null");
        return load(entry.jarPath(), entry.className());
    }

    /**
     * Loads a {@link DawPlugin} instance from the given JAR path and class name.
     *
     * <p>The {@link URLClassLoader} used to load the plugin is closed before
     * returning. Already-loaded classes remain usable, but lazy class loading
     * from the JAR will no longer work. For full classloader lifecycle control,
     * use {@link PluginRegistry#register(ExternalPluginEntry)} instead.</p>
     *
     * @param jarPath   the filesystem path to the plugin JAR file
     * @param className the fully qualified class name of the DawPlugin implementation
     * @return a new instance of the plugin
     * @throws PluginLoadException if the plugin cannot be loaded or instantiated
     */
    public static DawPlugin load(Path jarPath, String className) throws PluginLoadException {
        ExternalPluginLoader.LoadResult result = loadWithClassLoader(jarPath, className);
        closeQuietly(result.classLoader());
        return result.plugin();
    }

    /**
     * Loads a {@link DawPlugin} instance from the given JAR path and class name,
     * returning both the plugin and the classloader that loaded it.
     *
     * @param jarPath   the filesystem path to the plugin JAR file
     * @param className the fully qualified class name of the DawPlugin implementation
     * @return a result containing the plugin instance and its classloader
     * @throws PluginLoadException if the plugin cannot be loaded or instantiated
     */
    public static LoadResult loadWithClassLoader(Path jarPath, String className) throws PluginLoadException {
        Objects.requireNonNull(jarPath, "jarPath must not be null");
        Objects.requireNonNull(className, "className must not be null");

        if (className.isBlank()) {
            throw new PluginLoadException("className must not be blank");
        }

        if (!Files.exists(jarPath)) {
            throw new PluginLoadException("JAR file does not exist: " + jarPath);
        }
        if (!Files.isRegularFile(jarPath)) {
            throw new PluginLoadException("Path is not a regular file: " + jarPath);
        }

        URLClassLoader classLoader = null;
        try {
            URL jarUrl = jarPath.toUri().toURL();
            classLoader = new URLClassLoader(
                    new URL[]{jarUrl},
                    ExternalPluginLoader.class.getClassLoader());

            Class<?> pluginClass = classLoader.loadClass(className);

            if (!DawPlugin.class.isAssignableFrom(pluginClass)) {
                throw new PluginLoadException(
                        "Class " + className + " does not implement DawPlugin");
            }

            Object instance = pluginClass.getConstructor().newInstance();
            return new LoadResult((DawPlugin) instance, classLoader);
        } catch (PluginLoadException e) {
            closeQuietly(classLoader);
            throw e;
        } catch (ClassNotFoundException e) {
            closeQuietly(classLoader);
            throw new PluginLoadException(
                    "Class not found in JAR: " + className, e);
        } catch (NoSuchMethodException e) {
            closeQuietly(classLoader);
            throw new PluginLoadException(
                    "Plugin class has no public no-arg constructor: " + className, e);
        } catch (ReflectiveOperationException | IOException e) {
            closeQuietly(classLoader);
            throw new PluginLoadException(
                    "Failed to load plugin from " + jarPath + ": " + e.getMessage(), e);
        }
    }

    public static void closeQuietly(URLClassLoader classLoader) {
        if (classLoader != null) {
            try {
                classLoader.close();
            } catch (IOException ignored) {
                // best-effort cleanup
            }
        }
    }
}
