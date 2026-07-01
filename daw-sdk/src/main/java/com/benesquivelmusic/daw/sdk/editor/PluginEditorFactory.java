package com.benesquivelmusic.daw.sdk.editor;

import java.util.List;
import java.util.Objects;

import javafx.scene.layout.Region;

import com.benesquivelmusic.daw.sdk.annotation.Internal;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;

/**
 * The single, typed entry point a plugin returns from
 * {@link com.benesquivelmusic.daw.sdk.plugin.DawPlugin#editorFactory()} to
 * describe <em>how</em> its editor is produced (Plugin View Design Book §2.1,
 * §4.1). A {@code PluginEditorFactory} is one of exactly four permitted shapes:
 *
 * <ul>
 *   <li>{@link Declarative} — "no GUI, just my parameter list"; the host renders
 *       a parameter grid from the carried parameters laid out per {@link #hints()}
 *       (§6.2).</li>
 *   <li>{@link Panel} — "I will hand you a {@link Region}"; the host embeds it in
 *       its standard frame (§6.3).</li>
 *   <li>{@link Canvas} — "I will draw into your surface"; the host owns the
 *       canvas and drives the render loop (§5.D, §6.3).</li>
 *   <li>{@link Faulted} — an {@linkplain Internal internal} variant the host
 *       swaps in when a factory throws, so the user still sees an editor with a
 *       fault banner and a Reload affordance, never a void (§2.7, §4.8, §6.6).</li>
 * </ul>
 *
 * <p>The type is {@code sealed}: the four permitted subtypes are closed, so the
 * host can pattern-match exhaustively over the modes without a {@code default}.
 * Plugin authors implement {@link Panel} or {@link Canvas} (both
 * {@code non-sealed}), or return a {@link Declarative}; they never implement
 * {@code PluginEditorFactory} directly.</p>
 *
 * <h2>Threading (§4.7)</h2>
 * <p>{@link Panel#createPanel(EditorContext)}, {@link Canvas#attach(CanvasSurface)},
 * {@link Canvas#render(RenderTick)} and {@link Canvas#detach()} are all called on
 * the JavaFX Application Thread, each inside the host's fault harness (§2.7). None
 * may block; {@code render} should not allocate. The audio side
 * ({@code AudioProcessor#process(...)}) runs on the real-time audio thread and
 * must be real-time-safe; the editor never touches the processor directly — both
 * sides talk to the {@link PluginParameterStore} (§2.6).</p>
 *
 * @see com.benesquivelmusic.daw.sdk.plugin.DawPlugin#editorFactory()
 * @see EditorContext
 * @see EditorHints
 */
public sealed interface PluginEditorFactory
        permits PluginEditorFactory.Declarative,
                PluginEditorFactory.Panel,
                PluginEditorFactory.Canvas,
                PluginEditorFactory.Faulted {

    /**
     * The size, resize and chrome hints the host reads to frame this editor
     * (§4.1). Every mode reports hints: {@link Declarative} carries them as a
     * component; {@link Panel} and {@link Canvas} provide sensible defaults a
     * plugin may override.
     *
     * @return the editor hints, never {@code null}
     */
    EditorHints hints();

    /**
     * "No GUI — just my parameter list." The host generates a parameter grid
     * (§6.2) from {@link #parameters()} laid out per {@link #hints()}. This is
     * the default an ordinary plugin gets for free from
     * {@link com.benesquivelmusic.daw.sdk.plugin.DawPlugin#editorFactory()} when
     * it overrides only {@code getParameters()} (§4.2).
     *
     * @param parameters the parameters to render (copied defensively; never
     *                   {@code null})
     * @param hints      the layout hints (never {@code null})
     */
    record Declarative(List<PluginParameter> parameters, EditorHints hints)
            implements PluginEditorFactory {
        public Declarative {
            Objects.requireNonNull(parameters, "parameters must not be null");
            Objects.requireNonNull(hints, "hints must not be null");
            parameters = List.copyOf(parameters);
        }
    }

    /**
     * "I will hand you a {@link Region} when you ask." The host calls
     * {@link #createPanel(EditorContext)} once on the FX thread and embeds the
     * returned region inside its standard frame (§6.3). The plugin owns only the
     * region's contents; the host owns the chrome, sizing and clipping (§2.2).
     *
     * <p><strong>Threading (§4.7):</strong> {@code createPanel} runs on the
     * JavaFX Application Thread inside the host's fault harness; it may allocate
     * but must not block.</p>
     */
    non-sealed interface Panel extends PluginEditorFactory {

        /**
         * Builds this plugin's editor region. Called once, on the FX thread.
         *
         * @param context the narrow host services the editor may use (never
         *               {@code null})
         * @return the editor's root region; must not be {@code null}
         */
        Region createPanel(EditorContext context);

        /**
         * {@inheritDoc}
         *
         * <p>Defaults to {@link EditorHints#standard()}; override to request a
         * different preferred size, resize policy, or chrome.</p>
         */
        @Override
        default EditorHints hints() {
            return EditorHints.standard();
        }
    }

    /**
     * "I will draw inside your canvas." The host provides and owns a
     * {@link CanvasSurface}; the plugin draws into it and never constructs, sizes
     * or reparents it (§2.2, §6.3). Suits spectrum analyzers, oscilloscopes and
     * other full-surface tools (§5.D).
     *
     * <p><strong>Threading (§4.7):</strong> {@link #attach(CanvasSurface)},
     * {@link #render(RenderTick)} and {@link #detach()} all run on the JavaFX
     * Application Thread inside the host's fault harness. {@code render} should
     * not allocate and must not block.</p>
     */
    non-sealed interface Canvas extends PluginEditorFactory {

        /**
         * Called once, on the FX thread, when the host has created the surface
         * and is about to start the render loop. Cache the surface reference for
         * use in {@link #render(RenderTick)}.
         *
         * @param surface the host-owned drawing surface (never {@code null})
         */
        void attach(CanvasSurface surface);

        /**
         * Draws one frame. Called on the FX thread for every frame the host needs
         * to repaint (parameter change, resize, theme change, or an animation
         * tick the plugin opted into). Should not allocate; must not block.
         *
         * @param tick the per-frame payload — elapsed time, frame index and the
         *            resolved {@link Theme} tokens (never {@code null})
         */
        void render(RenderTick tick);

        /**
         * Called once, on the FX thread, when the editor is being torn down.
         * Release any resources acquired in {@link #attach(CanvasSurface)}.
         */
        void detach();

        /**
         * {@inheritDoc}
         *
         * <p>Defaults to {@link EditorHints#immersive()} — a full surface whose
         * chrome retracts on inactivity; override to request different hints.</p>
         */
        @Override
        default EditorHints hints() {
            return EditorHints.immersive();
        }
    }

    /**
     * An {@linkplain Internal internal} editor the host substitutes when a
     * plugin's {@code editorFactory()} — or one of its FX-thread callbacks —
     * throws (Plugin View Design Book §2.7, §4.8, §6.6). The user still sees an
     * editor: a fault banner describing {@link #cause()} / {@link #hint()} plus a
     * placeholder body and a Reload affordance, never a blank void. Plugin
     * authors never construct this; it exists so a thrown factory degrades to a
     * recoverable surface instead of a missing one.
     *
     * @param cause the throwable the plugin raised (never {@code null})
     * @param hint  a short, user-facing description of what failed (never
     *             {@code null})
     */
    @Internal
    record Faulted(Throwable cause, String hint) implements PluginEditorFactory {
        public Faulted {
            Objects.requireNonNull(cause, "cause must not be null");
            Objects.requireNonNull(hint, "hint must not be null");
        }

        /**
         * {@inheritDoc}
         *
         * <p>A faulted editor is framed compactly with standard chrome so the
         * banner and Reload affordance are visible.</p>
         */
        @Override
        public EditorHints hints() {
            return EditorHints.compact();
        }
    }
}
