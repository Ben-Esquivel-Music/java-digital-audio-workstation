/**
 * Host-side plugin-editor surface — the standard chrome and mode renderers
 * that wrap any {@link com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory}
 * (story 301, Plugin View Design Book §5.A/§5.D and §6).
 *
 * <p>Story 300 (Phase 1 of the §8 migration path) defined the SDK-side
 * editor contract; this package is Phase 2 — <em>the host honours it</em>
 * (§8.2). The centrepiece is
 * {@link com.benesquivelmusic.daw.app.ui.plugin.EditorFrame}, a
 * {@code Control} that renders the host-owned chrome around every editor
 * mode:</p>
 *
 * <ul>
 *   <li>the <strong>breadcrumb header</strong> (§6.1) — vendor ▸ plugin ▸
 *       insert location plus the four host actions
 *       {@code [Bypass] [A|B] [↗ detach] [× close]};</li>
 *   <li>the <strong>A/B compare bar</strong> (§6.5), backed by
 *       {@link com.benesquivelmusic.daw.core.plugin.parameter.ABComparison};</li>
 *   <li>the <strong>fault banner</strong> (§6.6) — inline above the body,
 *       never modal;</li>
 *   <li>the clipped <strong>body host</strong> (§6.3) — the mode-specific
 *       editor node, size-policed by the host
 *       ({@link com.benesquivelmusic.daw.sdk.editor.ResizePolicy}); and</li>
 *   <li>the <strong>footer</strong> (§6.4) — preset bar + always-present
 *       IN/OUT meters fed from
 *       {@link com.benesquivelmusic.daw.sdk.plugin.PluginMeterSnapshot}.</li>
 * </ul>
 *
 * <p>Design principle §2.2 — "the plugin is a guest, not a tenant" — is
 * structural here: the chrome is host-rendered in every mode, the plugin
 * cannot add or remove header actions, and the body region is clipped so a
 * misbehaving plugin cannot paint over the breadcrumb or footer. Chrome
 * quantity is selected by
 * {@link com.benesquivelmusic.daw.sdk.editor.ChromePolicy}
 * ({@code STANDARD} / {@code MINIMAL} / {@code IMMERSIVE}) but always
 * host-owned.</p>
 */
package com.benesquivelmusic.daw.app.ui.plugin;
