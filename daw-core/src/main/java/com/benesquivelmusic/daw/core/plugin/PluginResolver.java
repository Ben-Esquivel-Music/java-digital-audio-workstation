package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.sdk.plugin.PluginContext;

/**
 * Turns a saved JAR or CLAP plugin identity into a fresh, initialized plugin
 * instance for an insert the project reader rebuilds (story 334). The
 * installed-plugin registry is the production resolver (Plugin View book
 * rejection #5: only the registry turns an id into a plugin). A first-party
 * plugin never goes through a resolver: the reader resolves it from this
 * build's own providers ({@link BuiltInDawPlugin#createById}). Called on the
 * thread that reads the project: a resolver that loads plugins — it may
 * construct a plugin and wait for the registry's startup restore — runs on
 * a project open's worker, off the FX thread; the snapshot restore reads on
 * the FX thread and so uses the lookup-only
 * {@link PluginRegistry#withoutLoading()}, which never constructs and never
 * waits (studio ruling R1).
 */
@FunctionalInterface
public interface PluginResolver {

    /** Resolves nothing: every JAR and CLAP identity is {@linkplain PluginResolution.NotInstalled not installed}. */
    PluginResolver NONE = (identity, context) -> new PluginResolution.NotInstalled();

    /**
     * Resolves {@code identity} to a plugin initialized with {@code context},
     * not yet activated.
     *
     * @param identity the saved identity, of kind {@link PluginSourceKind#JAR} or {@link PluginSourceKind#CLAP}
     * @param context  the context to initialize the plugin with: the project's audio format
     * @return the plugin, or why there is none
     */
    PluginResolution resolve(PluginIdentity identity, PluginContext context);
}
