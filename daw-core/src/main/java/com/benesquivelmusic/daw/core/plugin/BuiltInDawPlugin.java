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
 * <p>Every permitted implementation must:</p>
 * <ul>
 *   <li>Declare a <b>public no-arg constructor</b> so that {@link #discoverAll()}
 *       can reflectively instantiate each one via
 *       {@link Class#getConstructor() getConstructor()}{@code .newInstance()}.</li>
 *   <li>Carry the {@link BuiltInPlugin @BuiltInPlugin} class-level annotation so
 *       that the menu layer can read metadata (label, icon, category) without
 *       instantiating the plugin — avoiding any expensive initialization that
 *       only the host should trigger.</li>
 * </ul>
 *
 * @see DawPlugin
 * @see BuiltInPlugin
 * @see BuiltInPluginCategory
 */
public sealed interface BuiltInDawPlugin extends DawPlugin
        permits VirtualKeyboardPlugin,
                ParametricEqPlugin,
                GraphicEqPlugin,
                CompressorPlugin,
                BusCompressorPlugin,
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
     * <p>The default implementation reads the {@link BuiltInPlugin#label()}
     * attribute from the class-level {@link BuiltInPlugin @BuiltInPlugin}
     * annotation, eliminating the need for each plugin class to override
     * this method just to return a compile-time constant.</p>
     *
     * @return the menu label, never {@code null} or blank
     */
    default String getMenuLabel() {
        return requireAnnotation(this.getClass()).label();
    }

    /**
     * Returns an icon identifier for the Plugins menu item.
     *
     * <p>The default implementation reads the {@link BuiltInPlugin#icon()}
     * attribute from the class-level {@link BuiltInPlugin @BuiltInPlugin}
     * annotation.</p>
     *
     * <p>The identifier can be a resource path, an icon-font name, or any
     * string that the UI layer can resolve to a visual icon.</p>
     *
     * @return the icon identifier, never {@code null} or blank
     */
    default String getMenuIcon() {
        return requireAnnotation(this.getClass()).icon();
    }

    /**
     * Returns the category used to group this plugin in the Plugins menu.
     *
     * <p>The default implementation reads the {@link BuiltInPlugin#category()}
     * attribute from the class-level {@link BuiltInPlugin @BuiltInPlugin}
     * annotation.</p>
     *
     * @return the plugin category, never {@code null}
     */
    default BuiltInPluginCategory getCategory() {
        return requireAnnotation(this.getClass()).category();
    }

    /**
     * Returns lightweight metadata entries for all permitted built-in plugins,
     * suitable for constructing menu items without retaining (or even creating)
     * live plugin instances.
     *
     * <p>Metadata is read directly from the class-level
     * {@link BuiltInPlugin @BuiltInPlugin} annotation via reflection.  No plugin
     * is instantiated — this avoids any potentially expensive initialization
     * (loading resources, allocating buffers) that is wasted when only metadata
     * is needed for menu construction.</p>
     *
     * <p>A permitted subclass missing the annotation is skipped with a warning
     * rather than crashing the application.</p>
     *
     * @return an unmodifiable list of menu entries for all built-in plugins
     */
    static List<MenuEntry> menuEntries() {
        Logger log = Logger.getLogger(BuiltInDawPlugin.class.getName());
        Class<?>[] permitted = BuiltInDawPlugin.class.getPermittedSubclasses();
        if (permitted == null) {
            return List.of();
        }
        var results = new ArrayList<MenuEntry>(permitted.length);
        for (Class<?> clazz : permitted) {
            BuiltInPlugin meta = clazz.getAnnotation(BuiltInPlugin.class);
            if (meta == null) {
                log.log(Level.WARNING,
                        "Skipping built-in plugin %s: missing @BuiltInPlugin annotation"
                                .formatted(clazz.getName()));
                continue;
            }
            @SuppressWarnings("unchecked")
            Class<? extends BuiltInDawPlugin> pluginClass =
                    (Class<? extends BuiltInDawPlugin>) clazz;
            results.add(new MenuEntry(pluginClass, meta.label(), meta.icon(), meta.category()));
        }
        return List.copyOf(results);
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

    /**
     * Resolves the {@link BuiltInPlugin} annotation for the given plugin class
     * or throws an {@link IllegalStateException} — a missing annotation on a
     * permitted subclass is a programming error, not a runtime condition to be
     * silently tolerated when a plugin instance already exists.
     */
    private static BuiltInPlugin requireAnnotation(Class<?> clazz) {
        BuiltInPlugin meta = clazz.getAnnotation(BuiltInPlugin.class);
        if (meta == null) {
            throw new IllegalStateException(
                    "Built-in plugin %s is missing the @BuiltInPlugin annotation"
                            .formatted(clazz.getName()));
        }
        return meta;
    }
}
