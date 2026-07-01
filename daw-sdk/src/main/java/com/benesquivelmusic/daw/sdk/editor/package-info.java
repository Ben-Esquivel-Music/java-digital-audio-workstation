/**
 * The plugin <strong>editor</strong> contract (Plugin View Design Book §4) — the
 * typed seam a third-party plugin uses to ship a GUI without ever touching
 * {@code daw-app}. Introduced by Phase 1 of the §8 migration path (SDK additions,
 * no removals); the host begins honouring it in story 301.
 *
 * <h2>The one entry point</h2>
 * <p>A plugin returns a
 * {@link com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory} from
 * {@link com.benesquivelmusic.daw.sdk.plugin.DawPlugin#editorFactory()}, choosing
 * exactly one of three modes (§2.1): a
 * {@link com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory.Declarative}
 * parameter list, a {@link com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory.Panel}
 * that ships a {@link javafx.scene.layout.Region}, or a
 * {@link com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory.Canvas} that draws
 * into a host-owned {@link com.benesquivelmusic.daw.sdk.editor.CanvasSurface}.</p>
 *
 * <p>The host owns all chrome (breadcrumb, A/B, preset bar, meters, fault banner,
 * resize grip, detach/close) in every mode; the plugin owns only what is between
 * them (§2.1, §2.2). A plugin never overrides host tokens — a
 * {@link com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory.Canvas} plugin is
 * handed the resolved {@link com.benesquivelmusic.daw.sdk.editor.Theme} so its
 * drawing tracks the active palette (§2.5, §9 rejection #6).</p>
 *
 * <h2>Real-time safety (§2.6)</h2>
 * <p>An editor cannot reach its {@code AudioProcessor} directly. Both the FX side
 * (a knob turn) and the audio side (an internal follower) talk only to the
 * host-owned {@link com.benesquivelmusic.daw.sdk.editor.PluginParameterStore},
 * which bridges the two threads over lock-free rings.</p>
 *
 * <h2>Threading (§4.7)</h2>
 * <table class="striped">
 *   <caption>Which thread each callback runs on</caption>
 *   <thead>
 *     <tr><th>Callback</th><th>Thread</th><th>May allocate?</th><th>May block?</th></tr>
 *   </thead>
 *   <tbody>
 *     <tr><td>{@code AudioProcessor#process(...)}</td><td>Audio</td><td>no</td><td>no</td></tr>
 *     <tr><td>{@link com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory.Panel#createPanel(com.benesquivelmusic.daw.sdk.editor.EditorContext)}</td><td>FX</td><td>yes</td><td>no</td></tr>
 *     <tr><td>{@link com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory.Canvas#attach(com.benesquivelmusic.daw.sdk.editor.CanvasSurface)} / detach</td><td>FX</td><td>yes</td><td>no</td></tr>
 *     <tr><td>{@link com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory.Canvas#render(com.benesquivelmusic.daw.sdk.editor.RenderTick)}</td><td>FX</td><td>preferably no</td><td>no</td></tr>
 *   </tbody>
 * </table>
 * <p>The host wraps every plugin-facing callback in a fault harness that catches
 * {@link java.lang.Throwable} and routes it to the in-surface fault banner (§2.7,
 * §6.6); a plugin may also self-report via
 * {@link com.benesquivelmusic.daw.sdk.editor.EditorContext#postFault(java.lang.Throwable, java.lang.String)}.</p>
 *
 * @see com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory
 * @see com.benesquivelmusic.daw.sdk.plugin.DawPlugin
 */
package com.benesquivelmusic.daw.sdk.editor;
