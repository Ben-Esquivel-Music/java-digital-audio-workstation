package com.benesquivelmusic.daw.sdk.plugin;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.editor.EditorHints;
import com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory;

import java.util.List;
import java.util.Optional;

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
 *
 * <h2>Audio Processing</h2>
 * <p>Plugins that process audio (effects, instruments) override
 * {@link #asAudioProcessor()} to return their {@link AudioProcessor}.
 * The host uses this method to wire the plugin into the mixer's insert
 * effect chain. Non-processing plugins (analyzers, utilities) inherit the
 * default implementation which returns {@link Optional#empty()}.</p>
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

    /**
     * Returns this plugin's audio processor, if it processes audio.
     *
     * <p>Effect and instrument plugins override this method to return their
     * {@link AudioProcessor}. The host calls this after {@link #initialize(PluginContext)}
     * to wire the plugin into the mixer's insert effect chain.</p>
     *
     * <p>Analysis-only plugins and utilities that do not process audio should
     * keep the default implementation, which returns {@link Optional#empty()}.</p>
     *
     * @return an {@link Optional} containing the audio processor, or empty
     *         if this plugin does not process audio
     */
    default Optional<AudioProcessor> asAudioProcessor() {
        return Optional.empty();
    }

    /**
     * Returns the parameter descriptors exposed by this plugin.
     *
     * <p>Effect plugins override this method to return descriptors for all
     * controllable parameters (e.g., threshold, ratio, attack for a compressor).
     * The host passes this list to a {@code PluginParameterEditorPanel} to
     * generate a generic parameter editor UI.</p>
     *
     * <p>Plugins that have no automatable parameters (analyzers, utilities,
     * or plugins with custom UIs) keep the default implementation, which
     * returns an empty list.</p>
     *
     * @return an unmodifiable list of parameter descriptors, never {@code null}
     */
    default List<PluginParameter> getParameters() {
        return List.of();
    }

    /**
     * Returns the factory that produces this plugin's editor surface (Plugin
     * View Design Book §2.1, §4.2). The host calls this on the JavaFX
     * Application Thread when the user focuses the plugin, and frames the result
     * in its own chrome (breadcrumb, A/B, preset bar, meters, fault banner) — the
     * plugin owns only the surface between them (§2.2).
     *
     * <p>The default is <em>backwards-compatible</em>: a plugin that overrides
     * only {@link #getParameters()} gets a host-generated parameter-grid editor
     * ({@link PluginEditorFactory.Declarative}) for free; a plugin that overrides
     * nothing gets an empty declarative editor (still better than the silence of
     * the pre-{@code editorFactory} SDK). A plugin that wants a custom GUI
     * overrides this method to return a {@link PluginEditorFactory.Panel} (ship a
     * {@link javafx.scene.layout.Region}) or a {@link PluginEditorFactory.Canvas}
     * (draw into a host-owned surface).</p>
     *
     * <p><strong>Threading (§4.7):</strong> this method and the panel/canvas
     * callbacks it leads to run on the FX thread, each inside the host's fault
     * harness (§2.7) — a factory that throws degrades to a recoverable
     * {@link PluginEditorFactory.Faulted} editor rather than crashing the host.
     * The editor never touches the {@code AudioProcessor} directly; it reads and
     * writes parameters through the host's real-time-safe
     * {@link com.benesquivelmusic.daw.sdk.editor.PluginParameterStore} (§2.6).</p>
     *
     * @return this plugin's editor factory, never {@code null}
     */
    default PluginEditorFactory editorFactory() {
        return new PluginEditorFactory.Declarative(getParameters(), EditorHints.compact());
    }

    /**
     * Returns the parameters of this plugin that can be driven by an
     * automation lane.
     *
     * <p>The DAW host queries this list to populate the automation-lane
     * parameter selector when a plugin is inserted on a track. Each returned
     * descriptor includes the parameter id, display name, value range and an
     * optional unit suffix.</p>
     *
     * <p>The default implementation derives the list from {@link #getParameters()}:
     * every generic {@link PluginParameter} is automatically exposed as an
     * {@link AutomatableParameter} (with an empty unit string). Plugins that
     * want richer metadata (unit strings, a subset of parameters, or additional
     * parameters not exposed in the generic editor) should override this
     * method.</p>
     *
     * @return an unmodifiable list of automatable parameter descriptors,
     *         never {@code null}
     */
    default List<AutomatableParameter> getAutomatableParameters() {
        List<PluginParameter> parameters = getParameters();
        if (parameters.isEmpty()) {
            return List.of();
        }
        return parameters.stream()
                .map(AutomatableParameter::from)
                .toList();
    }

    /**
     * Applies an automation value to the parameter with the given id.
     *
     * <p>Called by the host on the audio thread for every automation lane
     * bound to this plugin during playback (when the track's automation mode
     * allows reading). Implementations must be real-time safe: they should
     * simply clamp the incoming value and update a numeric control — they
     * must not allocate, lock, or perform blocking I/O.</p>
     *
     * <p>The default implementation is a no-op. Effect plugins that expose
     * automatable parameters via {@link #getAutomatableParameters()} must
     * override this method to route incoming values to their DSP state.</p>
     *
     * @param parameterId the parameter id (matches {@link AutomatableParameter#id()})
     * @param value       the new parameter value (already inside the declared range)
     */
    default void setAutomatableParameter(int parameterId, double value) {
        // default: plugin does not expose automatable parameters
    }
}
