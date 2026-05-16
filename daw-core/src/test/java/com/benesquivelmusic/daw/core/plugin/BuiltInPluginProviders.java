package com.benesquivelmusic.daw.core.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;

/**
 * Test helper that enumerates the registered {@link BuiltInDawPlugin}
 * {@link java.util.ServiceLoader} provider <em>classes</em> without
 * instantiating them.
 *
 * <p>Built-in plugin discovery migrated from a {@code sealed} interface +
 * {@code Class.getPermittedSubclasses()} enumeration to the JPMS
 * {@link java.util.ServiceLoader} SPI (the {@code provides … BuiltInDawPlugin
 * with …} clause in {@code daw.core}'s {@code module-info.java}). This helper
 * is the single test-side replacement for the old
 * {@code BuiltInDawPlugin.class.getPermittedSubclasses()} call: it returns
 * exactly the concrete provider classes, in declaration order, so the
 * existing "every built-in plugin is registered / annotated / final / has a
 * public no-arg constructor" invariants are preserved against the new
 * mechanism.</p>
 */
final class BuiltInPluginProviders {

    private BuiltInPluginProviders() {
    }

    /**
     * Returns every registered {@link BuiltInDawPlugin} provider class
     * (concrete plugin classes only — {@code ServiceLoader} never lists the
     * {@code MidiEffectPlugin} marker interface, which has no provider).
     */
    static List<Class<? extends BuiltInDawPlugin>> providerClasses() {
        List<Class<? extends BuiltInDawPlugin>> classes = new ArrayList<>();
        for (Provider<BuiltInDawPlugin> provider
                : ServiceLoader.load(BuiltInDawPlugin.class).stream().toList()) {
            classes.add(provider.type());
        }
        return List.copyOf(classes);
    }
}
