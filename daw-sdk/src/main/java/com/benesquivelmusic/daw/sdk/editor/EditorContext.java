package com.benesquivelmusic.daw.sdk.editor;

import javafx.beans.value.ObservableValue;

/**
 * The narrow set of host services a plugin editor may call (Plugin View Design
 * Book §4.1). Handed to {@link PluginEditorFactory.Panel#createPanel(EditorContext)}
 * and available to a {@link PluginEditorFactory.Canvas} through the surface the
 * host attaches.
 *
 * <p>This interface is deliberately kept small (§9 rejection #2 — no god-object
 * {@code EditorContext}): it exposes only theme, the parameter store, the
 * sample rate, resize / redraw requests, and fault reporting. Anything richer
 * (for example observing an automation lane's state) is intended to arrive
 * later as a dedicated sub-interface reached through this one, not by growing
 * this type.</p>
 *
 * <p><strong>Threading (§4.7):</strong> every method here is called on the
 * JavaFX Application Thread. The store returned by {@link #parameterStore()} is
 * the one exception — it is the thread-safe bridge to the audio thread (see
 * {@link PluginParameterStore}).</p>
 */
public interface EditorContext {

    /**
     * Returns the current audio sample rate in Hz.
     *
     * @return the sample rate in Hz
     */
    double sampleRate();

    /**
     * Returns the resolved theme colours the editor should draw with right now.
     * Prefer {@link #themeProperty()} to react to live theme changes.
     *
     * @return the current resolved theme, never {@code null}
     */
    Theme theme();

    /**
     * Returns an observable of the resolved theme so a panel can re-style, and
     * a canvas can re-tint, when the user switches the DAW theme. Read-only —
     * a plugin observes the palette, it cannot set it (§9 rejection #6).
     *
     * @return an observable theme value, never {@code null}
     */
    ObservableValue<Theme> themeProperty();

    /**
     * Returns the host-owned, real-time-safe parameter store. This is the only
     * channel between the editor (FX thread) and the audio processor (audio
     * thread); the editor cannot reach the {@code AudioProcessor} directly
     * (§2.6).
     *
     * @return the parameter store, never {@code null}
     */
    PluginParameterStore parameterStore();

    /**
     * Asks the host to resize the editor to the given content size, subject to
     * the editor's {@link EditorHints#resize()} policy and the host layout. The
     * host enforces the constraints and may coerce the request; the plugin
     * cannot resize itself (§6.3).
     *
     * @param width  the requested content width in pixels
     * @param height the requested content height in pixels
     */
    void requestResize(int width, int height);

    /**
     * Asks the host to schedule a repaint of the editor surface on the next
     * pulse. Repeated requests within a frame are coalesced.
     */
    void requestRender();

    /**
     * Asks the host to drive a continuous animation loop for this editor at
     * (approximately) the given frame rate. Only meaningful for a
     * {@link PluginEditorFactory.Canvas} editor: the host then calls
     * {@link PluginEditorFactory.Canvas#render(RenderTick) render(RenderTick)}
     * on an animation tick in addition to the event-driven repaints (parameter
     * change, resize, theme change) it already performs (§6.3). The host clamps
     * the requested rate to a sensible range; a value {@code <= 0} cancels a
     * previously requested timer, returning the editor to purely event-driven
     * rendering.
     *
     * @param framesPerSecond the requested animation rate in frames per
     *                        second; {@code <= 0} cancels the animation timer
     */
    void requestAnimationTimer(double framesPerSecond);

    /**
     * Reports a recoverable fault from the plugin. The host routes it to the
     * in-surface fault banner (§6.6) and the plugin fault log, without tearing
     * the editor down. Use this for problems the plugin catches itself; faults
     * thrown out of a host-invoked callback are captured by the host's fault
     * harness automatically (§2.7).
     *
     * @param error the throwable that occurred; must not be {@code null}
     * @param hint  a short, user-facing description of what failed (for example
     *              {@code "failed to load impulse response"}); must not be
     *              {@code null}
     */
    void postFault(Throwable error, String hint);
}
