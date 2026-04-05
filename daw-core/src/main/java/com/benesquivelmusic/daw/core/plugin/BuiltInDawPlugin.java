package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

/**
 * Sealed interface for first-party built-in plugins that ship with the DAW.
 *
 * <p>Because this interface is <b>sealed</b>, only the explicitly permitted classes
 * can implement it.  This gives the DAW full control over the set of built-in
 * plugins and enables exhaustive pattern matching over them.</p>
 *
 * <p>Every permitted implementation must provide a <b>public no-arg constructor</b>
 * so that {@link #discoverAll()} can reflectively instantiate each one via
 * {@link Class#getConstructor() getConstructor()}{@code .newInstance()}.</p>
 *
 * @see DawPlugin
 * @see BuiltInPluginCategory
 */
public sealed interface BuiltInDawPlugin extends DawPlugin
        permits VirtualKeyboardPlugin,
                ParametricEqPlugin,
                CompressorPlugin,
                ReverbPlugin,
                SpectrumAnalyzerPlugin {

    /**
     * Returns the human-readable label to display in the Plugins menu.
     *
     * @return the menu label, never {@code null} or blank
     */
    String getMenuLabel();

    /**
     * Returns an icon identifier for the Plugins menu item.
     *
     * <p>The identifier can be a resource path, an icon-font name, or any
     * string that the UI layer can resolve to a visual icon.</p>
     *
     * @return the icon identifier, never {@code null} or blank
     */
    String getMenuIcon();

    /**
     * Returns the category used to group this plugin in the Plugins menu.
     *
     * @return the plugin category, never {@code null}
     */
    BuiltInPluginCategory getCategory();

    /**
     * Discovers all permitted subclasses of {@code BuiltInDawPlugin},
     * instantiates each via its public no-arg constructor, and returns
     * the list of built-in plugin instances.
     *
     * <p>This method uses {@link Class#getPermittedSubclasses()} to enumerate
     * the permitted implementations, so no manual registration, service-loader
     * files, or classpath scanning is required.</p>
     *
     * @return an unmodifiable list of all built-in plugin instances
     * @throws IllegalStateException if a permitted subclass cannot be
     *         instantiated (e.g., missing no-arg constructor or access error)
     */
    static List<BuiltInDawPlugin> discoverAll() {
        Class<?>[] permitted = BuiltInDawPlugin.class.getPermittedSubclasses();
        if (permitted == null) {
            return List.of();
        }
        var plugins = new ArrayList<BuiltInDawPlugin>(permitted.length);
        for (Class<?> clazz : permitted) {
            try {
                plugins.add((BuiltInDawPlugin) clazz.getConstructor().newInstance());
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(
                        "Built-in plugin %s must have a public no-arg constructor".formatted(clazz.getName()), e);
            } catch (InvocationTargetException e) {
                throw new IllegalStateException(
                        "Failed to instantiate built-in plugin %s".formatted(clazz.getName()), e);
            } catch (InstantiationException | IllegalAccessException e) {
                throw new IllegalStateException(
                        "Cannot instantiate built-in plugin %s".formatted(clazz.getName()), e);
            }
        }
        return List.copyOf(plugins);
    }
}
