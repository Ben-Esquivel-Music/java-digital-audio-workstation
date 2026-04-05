package com.benesquivelmusic.daw.core.plugin;

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

    public SoundWaveTelemetryPlugin() {
    }

    @Override
    public String getMenuLabel() {
        return "Sound Wave Telemetry";
    }

    @Override
    public String getMenuIcon() {
        return "surround";
    }

    @Override
    public BuiltInPluginCategory getCategory() {
        return BuiltInPluginCategory.ANALYZER;
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
    }

    @Override
    public void deactivate() {
        active = false;
    }

    @Override
    public void dispose() {
        active = false;
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
}
