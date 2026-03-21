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

    private ExternalPluginLoader() {
        // utility class
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
     * @param jarPath   the filesystem path to the plugin JAR file
     * @param className the fully qualified class name of the DawPlugin implementation
     * @return a new instance of the plugin
     * @throws PluginLoadException if the plugin cannot be loaded or instantiated
     */
    public static DawPlugin load(Path jarPath, String className) throws PluginLoadException {
        Objects.requireNonNull(jarPath, "jarPath must not be null");
        Objects.requireNonNull(className, "className must not be null");

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
            return (DawPlugin) instance;
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

    private static void closeQuietly(URLClassLoader classLoader) {
        if (classLoader != null) {
            try {
                classLoader.close();
            } catch (IOException ignored) {
                // best-effort cleanup
            }
        }
    }
}
