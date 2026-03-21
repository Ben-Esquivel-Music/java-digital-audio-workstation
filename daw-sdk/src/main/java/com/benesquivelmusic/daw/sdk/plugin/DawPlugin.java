package com.benesquivelmusic.daw.sdk.plugin;

/**
 * Service provider interface for DAW plugins.
 *
 * <p>Plugin developers implement this interface to create custom audio effects,
 * instruments, and analyzers. Plugins can be discovered at runtime via the
 * {@link java.util.ServiceLoader} mechanism, or loaded directly from external
 * JAR files through the DAW's plugin manager UI—no {@code META-INF/services}
 * configuration required.</p>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link #getDescriptor()} — called once to obtain plugin metadata</li>
 *   <li>{@link #initialize(PluginContext)} — called once before first use</li>
 *   <li>{@link #activate()} / {@link #deactivate()} — called when the plugin is enabled/disabled</li>
 *   <li>{@link #dispose()} — called once when the plugin is unloaded</li>
 * </ol>
 */
public interface DawPlugin {

    /**
     * Returns the descriptor containing metadata about this plugin.
     *
     * @return the plugin descriptor, never {@code null}
     */
    PluginDescriptor getDescriptor();

    /**
     * Initializes the plugin with the provided context.
     *
     * @param context the host-provided context for accessing DAW services
     */
    void initialize(PluginContext context);

    /**
     * Activates the plugin, making it ready to process audio or handle events.
     */
    void activate();

    /**
     * Deactivates the plugin, suspending audio processing and event handling.
     */
    void deactivate();

    /**
     * Disposes of all resources held by this plugin. Called once during shutdown.
     */
    void dispose();
}
