/**
 * Editor surfaces for the built-in plugins (story 302, Plugin View Design
 * Book §8.3 Phase 3) — the {@link com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory
 * PluginEditorFactory} implementations each built-in returns from
 * {@code editorFactory()}.
 *
 * <p>Every class here is a worked example of the SDK editor contract a
 * third-party plugin author can copy (§1.5 reversed): a
 * {@code PluginEditorFactory.Panel} that hands the host a
 * {@link javafx.scene.layout.Region}, or a {@code PluginEditorFactory.Canvas}
 * that draws into the host-owned surface. The package is deliberately
 * <strong>not exported</strong> from {@code daw.core}: the host reaches these
 * editors only through the SDK contract (§2.3 — the SDK is the only seam),
 * never by naming a class in this package.</p>
 *
 * <h2>Package conventions</h2>
 * <ul>
 *   <li><strong>Theming (§2.5):</strong> no hard-coded colours. Panels rely on
 *       the host's application stylesheet for standard controls; custom canvas
 *       drawing (meters, plots) reads the resolved
 *       {@link com.benesquivelmusic.daw.sdk.editor.Theme} from
 *       {@code EditorContext#theme()} / {@code RenderTick#tokens()} and
 *       repaints on {@code EditorContext#themeProperty()} changes.</li>
 *   <li><strong>Parameters (§2.6):</strong> where a
 *       {@link com.benesquivelmusic.daw.sdk.plugin.PluginParameter} exists,
 *       controls read initial values from and write gestures to
 *       {@code EditorContext#parameterStore()}. Plugin state the store cannot
 *       express (enum modes, chain membership, MIDI note events) goes through
 *       the plugin's own accessors — the plugin editing its own state is
 *       within the contract; only the {@code AudioProcessor} is off-limits to
 *       direct writes. Editors never call {@code store.drainToUi(...)} — the
 *       host session owns that single-consumer ring.</li>
 *   <li><strong>Timers:</strong> a panel that polls (meters, step indicators)
 *       owns one {@link javafx.animation.AnimationTimer} gated by
 *       {@code ShowingWindowGate} — running only while the editor sits in a
 *       scene whose window is showing — so a torn-down or hidden editor never
 *       spins a frame loop. Scene presence alone is not enough: a hidden
 *       {@code Stage} keeps its scene attached, so a scene-gated loop would
 *       keep spinning invisibly. Canvas editors that self-schedule via
 *       {@code CanvasSurface#requestRender()} gate the re-request on
 *       {@code ShowingWindowGate#isShowing} for the same reason.</li>
 *   <li><strong>Threading (§4.7):</strong> every contract callback runs on the
 *       JavaFX Application Thread; off-thread completions marshal back with
 *       {@link javafx.application.Platform#runLater(Runnable)}.</li>
 *   <li><strong>Name shadowing (JLS §6.4.1):</strong> implementing
 *       {@code PluginEditorFactory.Panel} or {@code .Canvas} inherits the
 *       sealed interface's nested {@code Canvas} member type, which shadows an
 *       imported {@code javafx.scene.canvas.Canvas} throughout the class —
 *       use the fully-qualified JavaFX name at those use sites.</li>
 * </ul>
 */
package com.benesquivelmusic.daw.core.plugin.editor;
