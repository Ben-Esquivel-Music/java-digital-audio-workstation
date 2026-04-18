package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

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
                GraphicEqPlugin,
                CompressorPlugin,
                ReverbPlugin,
                SpectrumAnalyzerPlugin,
                TunerPlugin,
                SoundWaveTelemetryPlugin,
                SignalGeneratorPlugin,
                MetronomePlugin,
                AcousticReverbPlugin,
                BinauralMonitorPlugin,
                WaveshaperPlugin {

    /**
     * Lightweight metadata record used by the menu layer to populate plugin
     * entries without retaining live plugin instances.
     *
     * @param pluginClass the concrete plugin class
     * @param label       the human-readable label for the menu item
     * @param icon        the icon identifier for the menu item
     * @param category    the category used to group the plugin in the menu
     */
    record MenuEntry(
            Class<? extends BuiltInDawPlugin> pluginClass,
            String label,
            String icon,
            BuiltInPluginCategory category
    ) {}

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
     * Returns lightweight metadata entries for all discovered built-in
     * plugins, suitable for constructing menu items without retaining
     * live plugin instances.
     *
     * <p>Plugin instances are created briefly to extract metadata and
     * then discarded.  The host manages its own plugin lifecycle
     * independently via the class references in the returned entries.</p>
     *
     * <p>If a permitted subclass cannot be instantiated (e.g., missing no-arg
     * constructor or access error), a warning is logged and that plugin is
     * skipped rather than crashing the application.</p>
     *
     * @return an unmodifiable list of menu entries for all built-in plugins
     */
    static List<MenuEntry> menuEntries() {
        return discoverWith(instance -> {
            @SuppressWarnings("unchecked")
            Class<? extends BuiltInDawPlugin> pluginClass =
                    (Class<? extends BuiltInDawPlugin>) instance.getClass();
            return new MenuEntry(
                    pluginClass,
                    instance.getMenuLabel(),
                    instance.getMenuIcon(),
                    instance.getCategory());
        });
    }

    /**
     * Discovers all permitted subclasses of {@code BuiltInDawPlugin},
     * instantiates each via its public no-arg constructor, and returns
     * the list of built-in plugin instances.
     *
     * <p>This method uses {@link Class#getPermittedSubclasses()} to enumerate
     * the permitted implementations, so no manual registration, service-loader
     * files, or classpath scanning is required.</p>
     *
     * <p>If a permitted subclass cannot be instantiated (e.g., missing no-arg
     * constructor or access error), a warning is logged and that plugin is
     * skipped rather than crashing the application.</p>
     *
     * @return an unmodifiable list of all successfully instantiated built-in plugin instances
     */
    static List<BuiltInDawPlugin> discoverAll() {
        return discoverWith(Function.identity());
    }

    /**
     * Shared helper that iterates all permitted subclasses, instantiates
     * each via its public no-arg constructor, applies {@code mapper} to
     * the instance, and collects the results.
     */
    private static <T> List<T> discoverWith(Function<BuiltInDawPlugin, T> mapper) {
        Logger log = Logger.getLogger(BuiltInDawPlugin.class.getName());
        Class<?>[] permitted = BuiltInDawPlugin.class.getPermittedSubclasses();
        if (permitted == null) {
            return List.of();
        }
        var results = new ArrayList<T>(permitted.length);
        for (Class<?> clazz : permitted) {
            try {
                BuiltInDawPlugin instance =
                        (BuiltInDawPlugin) clazz.getConstructor().newInstance();
                results.add(mapper.apply(instance));
            } catch (NoSuchMethodException e) {
                log.log(Level.WARNING,
                        "Skipping built-in plugin %s: missing public no-arg constructor"
                                .formatted(clazz.getName()), e);
            } catch (InvocationTargetException e) {
                log.log(Level.WARNING,
                        "Skipping built-in plugin %s: constructor threw an exception"
                                .formatted(clazz.getName()), e);
            } catch (InstantiationException | IllegalAccessException e) {
                log.log(Level.WARNING,
                        "Skipping built-in plugin %s: cannot instantiate"
                                .formatted(clazz.getName()), e);
            }
        }
        return List.copyOf(results);
    }
}
