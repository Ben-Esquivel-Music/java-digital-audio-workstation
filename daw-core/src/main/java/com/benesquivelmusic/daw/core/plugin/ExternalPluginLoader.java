package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
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
 * {@code META-INF/services} files. The class name comes from the JAR's
 * manifest ({@code META-INF/daw-plugin.json}), never typed by a user; the
 * installed-plugin registry ({@link PluginRegistry}) keeps the entries and
 * their loaders.</p>
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
     * Loads a fresh instance of the plugin {@code entry} names, with a class
     * loader of its own, and initializes it with {@code context} — the
     * instance an insert slot runs (story 334). The result's disposal disposes
     * the plugin, then closes its class loader; the caller activates the
     * plugin and owns the disposal. When {@code initialize} throws — anything,
     * a checked exception it does not declare included, as a plugin written in
     * Kotlin can throw (finding FX2-2) — the disposal runs at once (a failure
     * of its own is suppressed into the thrown one, unless it is the very
     * exception {@code initialize} threw, which cannot suppress itself) and
     * the failure propagates as thrown.
     *
     * @param entry   the external plugin entry specifying JAR path and class name
     * @param context what the plugin is initialized with
     * @return the initialized plugin and its disposal
     * @throws PluginLoadException if the plugin cannot be loaded or instantiated
     */
    public static LoadedPlugin instantiate(ExternalPluginEntry entry, PluginContext context)
            throws PluginLoadException {
        Objects.requireNonNull(context, "context must not be null");
        LoadResult loaded = loadWithClassLoader(entry);
        DawPlugin plugin = loaded.plugin();
        Runnable disposal = () -> {
            try {
                plugin.dispose();
            } finally {
                closeQuietly(loaded.classLoader());
            }
        };
        try {
            plugin.initialize(context);
        } catch (Throwable failure) {
            try {
                disposal.run();
            } catch (Throwable disposalFailure) {
                if (disposalFailure != failure) {
                    failure.addSuppressed(disposalFailure);
                }
            }
            throw failure;
        }
        return new LoadedPlugin(plugin, disposal);
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
     * use {@link #loadWithClassLoader(ExternalPluginEntry)} or
     * {@link #instantiate(ExternalPluginEntry, PluginContext)} instead.</p>
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
     * <p>Whatever loading throws, the class loader is closed before this
     * method throws. What the plugin's constructor throws arrives wrapped in
     * an {@link InvocationTargetException} and is the plugin failing to load —
     * a {@link StackOverflowError} included — except the virtual machine's
     * own error, which propagates as thrown
     * ({@link PluginResolution.FailedToLoad#rethrowIfFatal}; story 334,
     * decision H7 and the final review's NIT-3). The {@link PluginLoadException}
     * for a constructor that threw names what it threw
     * ({@link PluginResolution.FailedToLoad#messageOrName}), before the class
     * loader is closed, since that exception's message or simple name may
     * need the plugin's JAR: the wrapper's own message is always
     * {@code null}.</p>
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
            Throwable failure = e;
            if (e instanceof InvocationTargetException constructorFailed
                    && constructorFailed.getTargetException() != null) {
                failure = constructorFailed.getTargetException();
            }
            // Named before the class loader closes: what the constructor threw may need the
            // plugin's JAR to give its message or its simple name. When the constructor threw,
            // the error names what it threw: the wrapper's own message is always null.
            String named = null;
            try {
                if (!PluginResolution.FailedToLoad.isFatal(failure)) {
                    named = PluginResolution.FailedToLoad.messageOrName(failure);
                }
            } finally {
                closeQuietly(classLoader);
            }
            PluginResolution.FailedToLoad.rethrowIfFatal(failure);
            throw new PluginLoadException("Failed to load plugin from " + jarPath + ": " + named, e);
        } catch (RuntimeException | Error failure) {
            closeQuietly(classLoader);
            throw failure;
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
