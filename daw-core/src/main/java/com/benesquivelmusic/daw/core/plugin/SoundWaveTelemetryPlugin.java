package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.telemetry.ArmedTrackSourceProvider;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.Objects;

/**
 * Built-in sound wave telemetry plugin.
 *
 * <p>Wraps the DAW's room acoustics analysis tool as a first-class plugin
 * so it appears in the Plugins menu under "Analyzers" alongside the
 * Spectrum Analyzer, rather than occupying a main DAW view slot.</p>
 *
 * <p>The plugin itself does not create or manage JavaFX windows. Window
 * creation, showing, hiding, and animation timer lifecycle management
 * are the responsibility of the {@code daw-app} UI layer (e.g.,
 * {@code MainController.openBuiltInPluginView}).</p>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link #initialize(PluginContext)} — stores the host context.</li>
 *   <li>{@link #activate()} — marks the plugin as active.</li>
 *   <li>{@link #deactivate()} — marks the plugin as inactive.</li>
 *   <li>{@link #dispose()} — releases held resources.</li>
 * </ol>
 */
@BuiltInPlugin(label = "Sound Wave Telemetry", icon = "surround", category = BuiltInPluginCategory.ANALYZER)
public final class SoundWaveTelemetryPlugin implements BuiltInDawPlugin {

    /** Stable plugin identifier — used by the host to map plugins to views. */
    public static final String PLUGIN_ID = "com.benesquivelmusic.daw.builtin.sound-wave-telemetry";

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
            PLUGIN_ID,
            "Sound Wave Telemetry",
            "1.0.0",
            "DAW Built-in",
            PluginType.ANALYZER
    );

    private PluginContext context;
    private boolean active;
    private ArmedTrackSourceProvider armedTrackSourceProvider;
    private ArmedTrackSourceProvider.Listener armedTrackListener;

    public SoundWaveTelemetryPlugin() {
    }

    /**
     * Supplies the {@link ArmedTrackSourceProvider} the plugin should use to
     * keep its room configuration in sync with the project's armed tracks.
     *
     * <p>The plugin does not subscribe to the provider until {@link #activate()}
     * is called, and unsubscribes on {@link #deactivate()} / {@link #dispose()}.
     * Setting the provider while the plugin is active re-subscribes to the new
     * provider and triggers an immediate sync so the plugin's state matches
     * the new project.</p>
     *
     * @param provider the provider to observe, or {@code null} to clear
     */
    public void setArmedTrackSourceProvider(ArmedTrackSourceProvider provider) {
        if (active) {
            unsubscribeFromProvider();
        }
        this.armedTrackSourceProvider = provider;
        if (active) {
            subscribeToProvider();
        }
    }

    /** Returns the currently wired provider, or {@code null} if none. */
    public ArmedTrackSourceProvider getArmedTrackSourceProvider() {
        return armedTrackSourceProvider;
    }

    @Override
    public PluginDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void initialize(PluginContext context) {
        Objects.requireNonNull(context, "context must not be null");
        this.context = context;
    }

    @Override
    public void activate() {
        active = true;
        subscribeToProvider();
    }

    @Override
    public void deactivate() {
        unsubscribeFromProvider();
        active = false;
    }

    @Override
    public void dispose() {
        unsubscribeFromProvider();
        active = false;
        armedTrackSourceProvider = null;
        context = null;
    }

    /**
     * Returns whether the plugin is currently active.
     *
     * @return {@code true} if active
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Returns whether the plugin is currently subscribed to an
     * {@link ArmedTrackSourceProvider}. Useful for tests that verify the
     * plugin wires up and unsubscribes at the correct lifecycle transitions.
     */
    public boolean isSubscribedToArmedTrackSourceProvider() {
        return armedTrackListener != null;
    }

    private void subscribeToProvider() {
        if (armedTrackSourceProvider == null || armedTrackListener != null) {
            return;
        }
        // Lightweight listener — the plugin doesn't need to react itself,
        // but retaining a subscription lets the UI / host treat the plugin
        // as an active observer of the arm/disarm workflow.
        armedTrackListener = _ -> { /* no-op: state lives in the provider */ };
        armedTrackSourceProvider.addListener(armedTrackListener);
        // Reconcile immediately so opening the plugin reflects the current
        // set of armed tracks without waiting for the next arm event.
        armedTrackSourceProvider.sync();
    }

    private void unsubscribeFromProvider() {
        if (armedTrackSourceProvider != null && armedTrackListener != null) {
            armedTrackSourceProvider.removeListener(armedTrackListener);
        }
        armedTrackListener = null;
    }
}
