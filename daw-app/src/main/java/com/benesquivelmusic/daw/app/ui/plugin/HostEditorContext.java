package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.sdk.editor.EditorContext;
import com.benesquivelmusic.daw.sdk.editor.PluginParameterStore;
import com.benesquivelmusic.daw.sdk.editor.ResizePolicy;
import com.benesquivelmusic.daw.sdk.editor.Theme;

import javafx.beans.value.ObservableValue;

import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * Host-side {@link EditorContext} implementation — the narrow set of host
 * services a contract-driven plugin editor may call (story 301, Plugin View
 * Design Book §4.1). One instance per {@link PluginEditorSession}; every
 * method delegates to the owning session so a reload that rebuilds the
 * store / body keeps this context valid (the plugin holds the context, the
 * session holds the truth).
 *
 * <p>Per §9 rejection #2 this stays deliberately small: theme, the parameter
 * store, the sample rate, resize / redraw / animation requests and fault
 * reporting — nothing more.</p>
 *
 * <ul>
 *   <li>{@link #requestResize(int, int)} is coerced by the session per the
 *       <em>current</em> {@link ResizePolicy} — the host enforces, the plugin
 *       cannot resize itself (§6.3).</li>
 *   <li>{@link #requestRender()} and {@link #requestAnimationTimer(double)}
 *       drive the session's coalesced canvas render / opted-in fps loop
 *       (§6.3); both are no-ops for non-canvas editors.</li>
 *   <li>{@link #postFault(Throwable, String)} is the §4.8 <em>recoverable</em>
 *       fault channel: banner + fault log, the editor keeps running — unlike a
 *       fault thrown out of a host-invoked callback, which the session's
 *       harness substitutes with the §6.6 placeholder.</li>
 * </ul>
 *
 * <p>Threading (§4.7): every method here is called on the JavaFX Application
 * Thread; the store returned by {@link #parameterStore()} is the one
 * thread-safe exception (§2.6).</p>
 *
 * @see PluginEditorSession
 */
final class HostEditorContext implements EditorContext {

    /**
     * Sample-rate fallback when the host supplied no
     * {@link PluginEditorSession.Deps#sampleRate()} (the Deps contract
     * tolerates {@code null} members) — CD-quality, the same default the
     * audio format layer assumes.
     */
    private static final double FALLBACK_SAMPLE_RATE = 44_100.0;

    private final PluginEditorSession session;
    private final DoubleSupplier sampleRate;

    /**
     * @param session    the owning session; must not be {@code null}
     * @param sampleRate the host's live sample-rate read, or {@code null} to
     *                   fall back to {@value #FALLBACK_SAMPLE_RATE}
     */
    HostEditorContext(PluginEditorSession session, DoubleSupplier sampleRate) {
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.sampleRate = sampleRate;
    }

    @Override
    public double sampleRate() {
        return sampleRate == null ? FALLBACK_SAMPLE_RATE : sampleRate.getAsDouble();
    }

    @Override
    public Theme theme() {
        return session.currentTheme();
    }

    @Override
    public ObservableValue<Theme> themeProperty() {
        return session.themeProperty();
    }

    @Override
    public PluginParameterStore parameterStore() {
        return session.store();
    }

    @Override
    public void requestResize(int width, int height) {
        session.requestResize(width, height);
    }

    @Override
    public void requestRender() {
        session.scheduleRender();
    }

    @Override
    public void requestAnimationTimer(double framesPerSecond) {
        session.setAnimationFps(framesPerSecond);
    }

    @Override
    public void postFault(Throwable error, String hint) {
        session.postFault(error, hint);
    }
}
