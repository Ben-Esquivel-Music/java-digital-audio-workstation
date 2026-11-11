package com.benesquivelmusic.daw.core.plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This build's first-party plugin providers by descriptor id, for
 * {@link BuiltInDawPlugin#createById} (story 334): a saved first-party insert
 * resolves through the closed set of providers the module declares, never a
 * class named in a file. Built once, on first use: each provider is
 * instantiated once to read its descriptor id, and only the
 * {@link Provider} is kept, so every lookup makes a fresh instance.
 */
final class BuiltInPluginIndex {

    private BuiltInPluginIndex() {
    }

    /** The index, built the first time a lookup needs it (lazy holder). */
    private static final class Holder {
        private static final Map<String, Provider<BuiltInDawPlugin>> PROVIDERS_BY_ID = index();
    }

    /**
     * A fresh instance of the provider whose descriptor id is {@code pluginId}.
     *
     * @param pluginId the descriptor id
     * @return the new instance, or empty when no provider has that id
     */
    static Optional<BuiltInDawPlugin> create(String pluginId) {
        Objects.requireNonNull(pluginId, "pluginId must not be null");
        Provider<BuiltInDawPlugin> provider = Holder.PROVIDERS_BY_ID.get(pluginId);
        return provider == null ? Optional.empty() : Optional.of(provider.get());
    }

    /**
     * Reads every provider's descriptor id; a provider that cannot be
     * instantiated is logged and skipped, as {@link BuiltInDawPlugin#discoverAll()}
     * skips it.
     */
    private static Map<String, Provider<BuiltInDawPlugin>> index() {
        Logger log = Logger.getLogger(BuiltInPluginIndex.class.getName());
        Map<String, Provider<BuiltInDawPlugin>> providers = new HashMap<>();
        for (Provider<BuiltInDawPlugin> provider : ServiceLoader.load(BuiltInDawPlugin.class).stream().toList()) {
            try {
                providers.putIfAbsent(provider.get().getDescriptor().id(), provider);
            } catch (ServiceConfigurationError e) {
                log.log(Level.WARNING, "Skipping built-in plugin %s: provider could not be instantiated"
                        .formatted(provider.type().getName()), e);
            }
        }
        return Map.copyOf(providers);
    }
}
