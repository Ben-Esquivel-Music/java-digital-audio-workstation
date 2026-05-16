package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service-provider interface for first-party built-in plugins that ship with
 * the DAW.
 *
 * <p>Built-in plugins are discovered through the JPMS
 * {@link java.util.ServiceLoader} SPI: {@code daw.core}'s
 * {@code module-info.java} declares
 * {@code uses com.benesquivelmusic.daw.core.plugin.BuiltInDawPlugin} and a
 * {@code provides … with …} clause listing every concrete built-in plugin.
 * This replaces the previous {@code sealed} interface +
 * {@link Class#getPermittedSubclasses()} reflection enumeration: the set of
 * built-in plugins is now governed by the {@code provides} directive, which is
 * verified by the module system at link time and keeps discovery working under
 * strong encapsulation across the {@code daw.core} → {@code daw.app} module
 * boundary.</p>
 *
 * <p>Every provider implementation must:</p>
 * <ul>
 *   <li>Declare a <b>public no-arg constructor</b> (the {@code ServiceLoader}
 *       provider contract) so that {@link #discoverAll()} can instantiate it.</li>
 *   <li>Carry the {@link BuiltInPlugin @BuiltInPlugin} class-level annotation so
 *       that the menu layer can read metadata (label, icon, category) without
 *       instantiating the plugin — {@link #menuEntries()} uses
 *       {@link Provider#type()} to read the annotation off the provider
 *       <em>class</em> without ever constructing it, preserving the original
 *       no-instantiation guarantee.</li>
 *   <li>Be listed in the {@code provides …
 *       com.benesquivelmusic.daw.core.plugin.BuiltInDawPlugin with …} clause of
 *       {@code daw-core/src/main/java/module-info.java}.</li>
 * </ul>
 *
 * @see DawPlugin
 * @see BuiltInPlugin
 * @see BuiltInPluginCategory
 * @see java.util.ServiceLoader
 */
public interface BuiltInDawPlugin extends DawPlugin {

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
        var results = new ArrayList<MenuEntry>();
        // ServiceLoader.stream() exposes each provider's class via
        // Provider#type() WITHOUT instantiating it — this preserves the
        // original guarantee that menu construction never constructs a plugin
        // just to read its label/icon/category.
        for (Provider<BuiltInDawPlugin> provider : serviceLoader().stream().toList()) {
            Class<? extends BuiltInDawPlugin> clazz = provider.type();
            BuiltInPlugin meta = clazz.getAnnotation(BuiltInPlugin.class);
            if (meta == null) {
                log.log(Level.WARNING,
                        "Skipping built-in plugin %s: missing @BuiltInPlugin annotation"
                                .formatted(clazz.getName()));
                continue;
            }
            results.add(new MenuEntry(clazz, meta.label(), meta.icon(), meta.category()));
        }
        return List.copyOf(results);
    }

    /**
     * Discovers all permitted subclasses of {@code BuiltInDawPlugin},
     * instantiates each via its public no-arg constructor, and returns
     * the list of built-in plugin instances.
     *
     * <p>This method resolves providers through the JPMS
     * {@link java.util.ServiceLoader} SPI (the {@code provides …
     * BuiltInDawPlugin with …} clause in {@code daw.core}'s
     * {@code module-info.java}), so no manual registration or classpath
     * scanning is required.</p>
     *
     * <p>If a provider cannot be instantiated (e.g. its constructor throws),
     * a warning is logged and that plugin is skipped rather than crashing the
     * application.</p>
     *
     * @return an unmodifiable list of all successfully instantiated built-in plugin instances
     */
    static List<BuiltInDawPlugin> discoverAll() {
        return discoverWith(Function.identity());
    }

    /**
     * Shared helper that iterates all {@link java.util.ServiceLoader}
     * providers, instantiates each via its public no-arg constructor, applies
     * {@code mapper} to the instance, and collects the results.
     *
     * <p>Each provider is resolved individually so a single faulty provider
     * (constructor throwing) is logged and skipped without aborting discovery
     * of the rest — matching the previous fail-soft behaviour.</p>
     */
    private static <T> List<T> discoverWith(Function<BuiltInDawPlugin, T> mapper) {
        Logger log = Logger.getLogger(BuiltInDawPlugin.class.getName());
        var results = new ArrayList<T>();
        for (Provider<BuiltInDawPlugin> provider : serviceLoader().stream().toList()) {
            try {
                results.add(mapper.apply(provider.get()));
            } catch (ServiceConfigurationError e) {
                log.log(Level.WARNING,
                        "Skipping built-in plugin %s: provider could not be instantiated"
                                .formatted(provider.type().getName()), e);
            }
        }
        return List.copyOf(results);
    }

    /**
     * Creates a fresh {@link ServiceLoader} for {@code BuiltInDawPlugin}.
     *
     * <p>A new loader is created per call so discovery never caches stale
     * instances and remains a pure, side-effect-free query — matching the
     * previous {@code getPermittedSubclasses()} semantics where every call
     * produced fresh plugin instances. The loader is bound to this
     * interface's own module layer, which carries the {@code provides}
     * directive.</p>
     */
    private static ServiceLoader<BuiltInDawPlugin> serviceLoader() {
        return ServiceLoader.load(BuiltInDawPlugin.class);
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
