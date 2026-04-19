package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.recording.ClickSound;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.recording.Metronome;
import com.benesquivelmusic.daw.core.recording.Subdivision;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.Objects;

/**
 * Built-in metronome configuration plugin.
 *
 * <p>Wraps the DAW's existing {@link Metronome} engine as a first-class
 * plugin so it appears in the Plugins menu under "Utilities". This plugin
 * serves as the configuration surface for the metronome — allowing users
 * to adjust click sounds, volume, count-in behavior, and subdivision
 * without cluttering the transport bar.</p>
 *
 * <p>The plugin itself does not create or manage JavaFX windows. Window
 * creation, showing, hiding, and animation timer lifecycle management
 * are the responsibility of the {@code daw-app} UI layer (e.g.,
 * {@code MainController.openBuiltInPluginView}).</p>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link #initialize(PluginContext)} — stores the host context and
 *       creates a default {@link Metronome} instance.</li>
 *   <li>{@link #activate()} — marks the plugin as active (opens the
 *       configuration panel in the UI layer).</li>
 *   <li>{@link #deactivate()} — marks the plugin as inactive (hides the
 *       configuration panel; the metronome continues running if enabled
 *       via the transport bar).</li>
 *   <li>{@link #dispose()} — disconnects from the metronome engine.</li>
 * </ol>
 *
 * <h2>Configuration</h2>
 * <ul>
 *   <li><b>Click sound</b> — built-in presets via {@link ClickSound}</li>
 *   <li><b>Volume</b> — independent of master bus, range [0.0, 1.0]</li>
 *   <li><b>Count-in</b> — configurable bars via {@link CountInMode}</li>
 *   <li><b>Subdivision</b> — quarter, eighth, or sixteenth notes via {@link Subdivision}</li>
 * </ul>
 */
@BuiltInPlugin(label = "Metronome", icon = "metronome", category = BuiltInPluginCategory.UTILITY)
public final class MetronomePlugin implements BuiltInDawPlugin {

    /** Stable plugin identifier — used by the host to map plugins to views. */
    public static final String PLUGIN_ID = "com.benesquivelmusic.daw.metronome";

    /** Default number of audio channels for the metronome. */
    private static final int DEFAULT_CHANNELS = 2;

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
            PLUGIN_ID,
            "Metronome",
            "1.0.0",
            "DAW Built-in",
            PluginType.MIDI_EFFECT
    );

    private PluginContext context;
    private Metronome metronome;
    private boolean active;
    private CountInMode countInMode = CountInMode.ONE_BAR;

    public MetronomePlugin() {
    }

    @Override
    public PluginDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void initialize(PluginContext context) {
        Objects.requireNonNull(context, "context must not be null");
        this.context = context;
        metronome = new Metronome(context.getSampleRate(), DEFAULT_CHANNELS);
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
        metronome = null;
        context = null;
    }

    // ── Configuration Delegates ────────────────────────────────────────

    /**
     * Returns whether the metronome click is enabled.
     *
     * @return {@code true} if the metronome is enabled
     * @throws IllegalStateException if the plugin has not been initialized
     */
    public boolean isEnabled() {
        requireInitialized();
        return metronome.isEnabled();
    }

    /**
     * Enables or disables the metronome click.
     *
     * @param enabled {@code true} to enable, {@code false} to disable
     * @throws IllegalStateException if the plugin has not been initialized
     */
    public void setEnabled(boolean enabled) {
        requireInitialized();
        metronome.setEnabled(enabled);
    }

    /**
     * Returns the current click sound preset.
     *
     * @return the click sound, never {@code null}
     * @throws IllegalStateException if the plugin has not been initialized
     */
    public ClickSound getClickSound() {
        requireInitialized();
        return metronome.getClickSound();
    }

    /**
     * Sets the click sound preset.
     *
     * @param clickSound the click sound to use
     * @throws NullPointerException  if {@code clickSound} is {@code null}
     * @throws IllegalStateException if the plugin has not been initialized
     */
    public void setClickSound(ClickSound clickSound) {
        requireInitialized();
        metronome.setClickSound(clickSound);
    }

    /**
     * Returns the metronome volume.
     *
     * @return the volume in the range [0.0, 1.0]
     * @throws IllegalStateException if the plugin has not been initialized
     */
    public float getVolume() {
        requireInitialized();
        return metronome.getVolume();
    }

    /**
     * Sets the metronome volume, independent of the master bus volume.
     *
     * @param volume the volume in the range [0.0, 1.0]
     * @throws IllegalArgumentException if the volume is outside [0.0, 1.0]
     * @throws IllegalStateException    if the plugin has not been initialized
     */
    public void setVolume(float volume) {
        requireInitialized();
        metronome.setVolume(volume);
    }

    /**
     * Returns the current count-in mode.
     *
     * @return the count-in mode, never {@code null}
     */
    public CountInMode getCountInMode() {
        return countInMode;
    }

    /**
     * Sets the count-in mode (number of bars before recording starts).
     *
     * @param countInMode the count-in mode
     * @throws NullPointerException if {@code countInMode} is {@code null}
     */
    public void setCountInMode(CountInMode countInMode) {
        this.countInMode = Objects.requireNonNull(countInMode, "countInMode must not be null");
    }

    /**
     * Returns the current subdivision setting.
     *
     * @return the subdivision, never {@code null}
     * @throws IllegalStateException if the plugin has not been initialized
     */
    public Subdivision getSubdivision() {
        requireInitialized();
        return metronome.getSubdivision();
    }

    /**
     * Sets the subdivision level for metronome clicks.
     *
     * @param subdivision the subdivision to use
     * @throws NullPointerException  if {@code subdivision} is {@code null}
     * @throws IllegalStateException if the plugin has not been initialized
     */
    public void setSubdivision(Subdivision subdivision) {
        requireInitialized();
        metronome.setSubdivision(subdivision);
    }

    // ── State Queries ──────────────────────────────────────────────────

    /**
     * Returns whether the plugin is currently active.
     *
     * @return {@code true} if active
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Returns the underlying {@link Metronome} created during
     * {@link #initialize(PluginContext)}, or {@code null} if the plugin
     * has not been initialized or has been disposed.
     *
     * @return the metronome engine, or {@code null}
     */
    public Metronome getMetronome() {
        return metronome;
    }

    // ── Internal ───────────────────────────────────────────────────────

    private void requireInitialized() {
        if (metronome == null) {
            throw new IllegalStateException("Plugin has not been initialized");
        }
    }
}
