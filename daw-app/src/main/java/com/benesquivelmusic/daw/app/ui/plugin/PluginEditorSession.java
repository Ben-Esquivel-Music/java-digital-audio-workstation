package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.NotificationLevel;
import com.benesquivelmusic.daw.app.ui.marshal.FxAnimationTimerAllowed;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.app.ui.views.DetachPluginRequestedEvent;
import com.benesquivelmusic.daw.core.plugin.PluginInvocationSupervisor;
import com.benesquivelmusic.daw.core.plugin.parameter.ABComparison;
import com.benesquivelmusic.daw.core.plugin.parameter.ParameterPreset;
import com.benesquivelmusic.daw.core.plugin.parameter.ParameterPresetManager;
import com.benesquivelmusic.daw.core.plugin.parameter.PluginParameterState;
import com.benesquivelmusic.daw.sdk.editor.EditorHints;
import com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory;
import com.benesquivelmusic.daw.sdk.editor.PluginParameterStore;
import com.benesquivelmusic.daw.sdk.editor.RenderTick;
import com.benesquivelmusic.daw.sdk.editor.Theme;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginMeterSnapshot;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;

import javafx.animation.AnimationTimer;
import javafx.beans.InvalidationListener;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.function.BiConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One live host↔plugin editor session (story 301, Plugin View Design Book
 * §8.2) — the host-side machinery that honours the story-300
 * {@link PluginEditorFactory} contract. {@link #open} invokes the plugin's
 * {@code editorFactory()} inside the §2.7 fault harness, frames the resulting
 * editor in the standard {@link EditorFrame} chrome (§6.1–§6.6), and drives
 * everything the contract promises the plugin:
 *
 * <ul>
 *   <li><strong>Declarative</strong> — a host-generated
 *       {@link ParameterGridPanel} (§6.2) wired through the single-writer
 *       {@link ParameterGridPanel.ValueSink} into the session's
 *       {@link PluginParameterState} mirror and the real-time-safe
 *       {@link PluginParameterStore} (§2.6);</li>
 *   <li><strong>Panel</strong> — the plugin's {@link Region} from
 *       {@code createPanel(EditorContext)}, embedded and clipped by the frame
 *       (§6.3);</li>
 *   <li><strong>Canvas</strong> — a host-owned {@link Canvas} inside an
 *       {@code editor-canvas-host} wrapper; the session owns the render loop
 *       and calls {@code render(RenderTick)} on parameter change, resize,
 *       theme change, {@code requestRender()} and an opted-in
 *       {@code requestAnimationTimer(fps)} tick (§5.D, §6.3), all coalesced
 *       per frame through the {@link FxDispatcher} keyed latest-wins seam
 *       (§6.3 / story 289);</li>
 *   <li><strong>Faulted</strong> — the §6.6 placeholder body plus the inline
 *       fault banner, never a void.</li>
 * </ul>
 *
 * <h2>Fault harness (§2.7, §4.7, §6.6)</h2>
 *
 * <p>Every plugin-facing call — {@code editorFactory()}, {@code hints()},
 * {@code getParameters()}, {@code createPanel}, {@code attach},
 * {@code render}, {@code detach} — runs inside a {@link Throwable}-catching
 * harness. On a fault the session routes the trace to
 * {@link PluginInvocationSupervisor#reportUiFault} (editor faults never
 * quarantine the audio processor — §6.6 "only the editor is disabled"),
 * shows the fault banner, and — when the fault killed the body — swaps in the
 * {@code editor-fault-placeholder} so the user always sees an editor. A fault
 * during a canvas {@code render} substitutes the body exactly once (guarded,
 * never per-tick spam), stops the fps loop and best-effort-detaches the
 * canvas. {@code EditorContext#postFault} (§4.8) is the deliberate exception:
 * a plugin-<em>reported</em> recoverable fault shows the banner and logs, but
 * neither substitutes the body nor stops the editor.</p>
 *
 * <h2>Callback ownership</h2>
 *
 * <p>The session wires every {@link EditorFrame} callback <em>except</em>
 * {@link EditorFrame#setOnCloseRequested(Runnable)}: close-intent placement
 * (clearing the Workshop pane, nulling the active-session field) is the
 * hosting {@code PluginViewController}'s concern, so the controller sets that
 * one callback after {@link #open} returns. Reload, fault details, detach
 * (fires the existing typed {@link DetachPluginRequestedEvent} — story 288's
 * panel-dock machinery is explicitly out of scope), A/B (backed by the
 * existing {@link ABComparison}, §6.5), and the §6.4 preset footer (persisted
 * via {@link ParameterPresetManager} under
 * {@code ~/.daw/plugin-presets/<sanitized-plugin-id>}, the
 * {@code BackupRetentionController}/{@code RenderQueue} home-directory
 * convention) are all session-owned.</p>
 *
 * <h2>Bypass (§6.1)</h2>
 *
 * <p>The frame's bypass toggle is recorded in {@link #isBypassed()} only:
 * story 301 opens editors for <em>registered</em> plugins with no live insert
 * slot behind them, so there is no engine hookup for the bypass state yet —
 * wiring it to a live insert chain is the inserts / story-302+ domain.</p>
 *
 * <h2>Threading (§4.7)</h2>
 *
 * <p>{@link #open}, {@link #dispose()} and every frame callback run on the
 * JavaFX Application Thread. The session's single {@link AnimationTimer}
 * (sanctioned below) runs only while the frame is in a {@link Scene}; per
 * tick it drains the store's audio→UI ring into the grid / a canvas repaint,
 * mirrors the latest {@link PluginMeterSnapshot} into the frame's §6.4
 * meters (reference-compared — the meter channel is latest-wins), and paces
 * the opted-in canvas fps. Per-tick work is allocation-light: the drain sink
 * and render runnable are pre-allocated fields.</p>
 *
 * <h2>Theme bridge (§2.5)</h2>
 *
 * <p>The SDK {@link Theme} handed to canvas plugins is built from the frame's
 * six resolved {@code -editor-frame-*} styleable colours (styles.css forwards
 * {@code -surface-1}/{@code -text}/{@code -accent}/{@code -meter-*} into
 * them) — colours derive from resolved CSS, never a mirrored hex literal.
 * One invalidation listener over all six rebuilds the theme and schedules a
 * canvas repaint; entering a scene first forces {@code applyCss()} so the
 * initial read is resolved, not the neutral fallback.</p>
 *
 * @see EditorFrame
 * @see HostEditorContext
 * @see FxCanvasSurface
 */
@FxAnimationTimerAllowed("Per-editor drain/meter/fps loop owned by this session: "
        + "drains the parameter store's audio-to-UI ring into the editor, mirrors "
        + "the meter snapshot and paces an opted-in canvas frame rate, running "
        + "only while the frame is in a scene (javafx-application-design §6 "
        + "control-owns-timer); not a cross-thread seam — story 289 sentinel.")
public final class PluginEditorSession {

    private static final Logger LOG = Logger.getLogger(PluginEditorSession.class.getName());

    /** Chrome strings (Skill §14) — {@code Locale.ROOT}, EditorFrameSkin idiom. */
    private static final ResourceBundle MESSAGES = ResourceBundle.getBundle(
            "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);

    /** Bounds the host clamps an opted-in animation rate into (§6.3). */
    private static final double MIN_FPS = 1.0;
    private static final double MAX_FPS = 60.0;

    /**
     * Host services the session consumes. Supplier results and nullable
     * members are tolerated as {@code null} — a missing service degrades to
     * a sensible fallback (default sample rate, no fault-log routing, no
     * notification) rather than an error, so headless tests can open a
     * session with {@code new Deps(null, null, null, null)}.
     *
     * @param sampleRate       live sample-rate read for
     *                         {@code EditorContext#sampleRate()}; nullable
     * @param faultSupervisor  live supplier of the
     *                         {@link PluginInvocationSupervisor} the §6.6
     *                         fault harness reports into (a supplier because
     *                         the supervisor is constructed after the plugin
     *                         controller at startup); nullable, and a
     *                         {@code null} supplier result is tolerated
     * @param openPluginFaultLog opens the plugin fault log (the banner's
     *                         {@code [ⓘ]} action, §6.6); nullable
     * @param showNotification the toast sink for host-side failures (e.g. a
     *                         preset that cannot be written, §6.4); nullable
     */
    public record Deps(DoubleSupplier sampleRate,
                       Supplier<PluginInvocationSupervisor> faultSupervisor,
                       Runnable openPluginFaultLog,
                       BiConsumer<NotificationLevel, String> showNotification) {
    }

    // ── Immutable session identity / services ────────────────────────────

    private final DawPlugin plugin;
    private final Deps deps;
    private final EditorFrame frame = new EditorFrame();
    private final HostEditorContext context;

    /**
     * The marshalling seam, captured ONCE at open — never re-read, so a
     * {@code FxDispatcher.installDefault} swap mid-life cannot split the
     * session across two dispatchers (the "capture swappable singleton
     * reference once" discipline). May be {@code null} in a pure-unit
     * context; renders then run immediately instead of coalesced.
     */
    private final FxDispatcher dispatcher;

    /** Theme manager captured ONCE at open (same discipline as above). */
    private final ThemeManager themeManager;

    /** Fault-log id: {@code descriptor.id()}, or the class name fallback. */
    private final String faultReportId;
    /** Banner display name: {@code descriptor.name()}, or the class simple name. */
    private final String displayName;

    private final ParameterPresetManager presetManager;
    /** Loaded presets by name — backs the §6.4 footer selector. */
    private final Map<String, ParameterPreset> presetsByName = new LinkedHashMap<>();

    // ── Timer / render plumbing (pre-allocated — per-tick work stays light) ─

    private final SessionTimer timer = new SessionTimer();
    /** Session-unique coalescing key for {@link FxDispatcher#onFx(Object, Runnable)}. */
    private final Object renderKey = new Object();
    private final Runnable renderRunnable = this::renderNow;
    private final PluginParameterStore.IndexConsumer drainSink = this::onStoreIndexDrained;

    // ── Theme bridge (§2.5) ───────────────────────────────────────────────

    private final ReadOnlyObjectWrapper<Theme> theme;
    // this-qualified reads: a lambda in an instance-variable initializer may
    // not forward-reference a later field by simple name (JLS §8.3.3).
    private final InvalidationListener themeTokensListener = obs -> {
        if (!this.disposed) {
            rebuildTheme();
        }
    };
    private final ChangeListener<Scene> sceneListener = this::onSceneChanged;
    private final ChangeListener<Window> windowListener =
            (obs, was, window) -> trackWindow(window);
    /**
     * Restart kick for self-scheduling canvas editors (§5.D): they stop
     * re-requesting frames while their window is hidden (a hidden stage keeps
     * its scene attached — the dock layer hides floating stages rather than
     * closing them), so when the window shows again the host schedules one
     * repaint to resume the loop — the window-side counterpart of the
     * scene-entry repaint in {@link #onSceneChanged}.
     */
    private final ChangeListener<Boolean> windowShowingListener =
            (obs, was, showing) -> {
                if (!this.disposed && showing) {
                    scheduleRender();
                }
            };
    /** The window whose {@code showingProperty} the session tracks. */
    private Window trackedWindow;
    private final ChangeListener<Boolean> bypassListener =
            (obs, was, now) -> this.bypassed = now;

    // ── Per-build editor state (replaced wholesale by a reload) ──────────

    private PluginParameterStore store;
    private PluginParameterState state;
    private ABComparison abComparison;
    /** Non-null only in Declarative mode — the echo target for drains / A/B / presets. */
    private ParameterGridPanel grid;
    /** Non-null only in Canvas mode. */
    private PluginEditorFactory.Canvas canvasFactory;
    private FxCanvasSurface surface;
    private Canvas canvas;
    private boolean canvasAttached;

    /** §6.6 swap-once guard: the placeholder is substituted at most once per build. */
    private boolean bodyFaulted;
    /** Set by the drain sink when a drained change needs a canvas repaint this tick. */
    private boolean drainRenderNeeded;
    /** Opted-in canvas animation rate (§6.3); {@code 0} = event-driven only. */
    private double animationFps;
    /** {@code System.nanoTime()} of the previous render; {@code 0} = none yet. */
    private long lastRenderNanos;
    /** Monotonic {@link RenderTick#frameIndex()} counter for this editor. */
    private long frameIndex;

    private boolean bypassed;
    private boolean disposed;

    private PluginEditorSession(DawPlugin plugin, String insertLocation, Deps deps) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.deps = Objects.requireNonNull(deps, "deps must not be null");
        this.dispatcher = FxDispatcher.getDefault();
        this.themeManager = ThemeManager.getDefault();
        this.context = new HostEditorContext(this, deps.sampleRate());

        // Identity — getDescriptor() is plugin code; a throw degrades to the
        // class name so the session (and its fault reporting) still works.
        String id;
        String name;
        String vendor;
        Throwable descriptorFault = null;
        try {
            PluginDescriptor descriptor = Objects.requireNonNull(
                    plugin.getDescriptor(), "getDescriptor() returned null");
            id = descriptor.id();
            name = descriptor.name();
            vendor = descriptor.vendor();
        } catch (Throwable t) {
            id = plugin.getClass().getName();
            name = plugin.getClass().getSimpleName();
            vendor = "";
            descriptorFault = t;
        }
        this.faultReportId = id;
        this.displayName = name;
        if (descriptorFault != null) {
            reportToSupervisor(descriptorFault);
        }
        frame.setVendor(vendor);
        frame.setPluginName(name);
        frame.setInsertLocation(insertLocation);

        this.presetManager = new ParameterPresetManager(presetRoot(id));
        this.theme = new ReadOnlyObjectWrapper<>(this, "theme", readThemeFromFrame());

        // §2.5 — ONE invalidation listener over all six forwarded role-token
        // colours; a change rebuilds the SDK Theme and repaints the canvas.
        frame.editorBackgroundProperty().addListener(themeTokensListener);
        frame.editorForegroundProperty().addListener(themeTokensListener);
        frame.editorAccentProperty().addListener(themeTokensListener);
        frame.editorMeterSafeProperty().addListener(themeTokensListener);
        frame.editorMeterWarnProperty().addListener(themeTokensListener);
        frame.editorMeterClipProperty().addListener(themeTokensListener);
        frame.sceneProperty().addListener(sceneListener);
        frame.bypassedProperty().addListener(bypassListener);
        bypassed = frame.isBypassed();

        // Frame callbacks — everything EXCEPT onCloseRequested, which the
        // hosting PluginViewController owns (see the class Javadoc).
        frame.setOnReloadRequested(this::reload);
        frame.setOnFaultDetailsRequested(this::openFaultLog);
        frame.setOnDetachRequested(
                () -> frame.fireEvent(new DetachPluginRequestedEvent()));
        frame.setOnAbToggleRequested(this::onAbToggle);
        frame.setOnAbCopyRequested(this::onAbCopy);
        frame.setOnPresetSelected(this::onPresetSelected);
        frame.setOnSavePresetRequested(this::onSavePreset);
        frame.setOnSaveAsPresetRequested(this::onSaveAsPreset);

        buildEditor();
        loadPresetsIntoFrame();

        if (frame.getScene() != null) {
            onSceneChanged(frame.sceneProperty(), null, frame.getScene());
        }
    }

    /**
     * Opens an editor session for the given plugin: runs the §8.2 factory
     * pipeline ({@code editorFactory()} → hints → store → mode body) inside
     * the fault harness and returns the framed result.
     *
     * <p>FX thread only. Never throws for plugin-caused failures — a plugin
     * that throws anywhere in the pipeline degrades to the §6.6 fault banner
     * plus placeholder body, and the returned session is fully functional
     * (Reload re-attempts the pipeline).</p>
     *
     * @param plugin         the plugin whose editor to open; must not be
     *                       {@code null}
     * @param insertLocation the §6.1 insert-location crumb (e.g.
     *                       {@code "Drum Bus / Insert 2"}); {@code null} or
     *                       blank omits the crumb
     * @param deps           host services; must not be {@code null} (its
     *                       members may be)
     * @return the open session, never {@code null}
     */
    public static PluginEditorSession open(DawPlugin plugin, String insertLocation,
            Deps deps) {
        return new PluginEditorSession(plugin, insertLocation, deps);
    }

    /** @return the framed editor — the node the host places in the Workshop pane. */
    public EditorFrame frame() {
        return frame;
    }

    /**
     * @return the session's real-time-safe parameter store — the §2.6 bridge
     *         both the editor and (in later stories) the audio processor talk
     *         to. Exposed as a test seam; a Reload that re-runs the factory
     *         pipeline replaces the instance, so callers should not cache it.
     */
    public PluginParameterStore store() {
        return store;
    }

    /**
     * @return the frame's current bypass state (§6.1). Story 301 records the
     *         toggle only — these editors open for registered plugins with no
     *         live insert slot, so wiring bypass into an engine chain is the
     *         inserts / story-302+ domain.
     */
    public boolean isBypassed() {
        return bypassed;
    }

    /**
     * Tears the session down: stops the timer, best-effort-detaches a canvas
     * editor (harnessed), and removes every listener / callback the session
     * installed on the frame ({@code onCloseRequested} is deliberately left —
     * the hosting controller owns it). Idempotent.
     */
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        timer.stop();
        teardownCanvas();
        grid = null;
        if (frame.getScene() != null) {
            frame.getScene().windowProperty().removeListener(windowListener);
        }
        trackWindow(null);
        frame.sceneProperty().removeListener(sceneListener);
        frame.bypassedProperty().removeListener(bypassListener);
        frame.editorBackgroundProperty().removeListener(themeTokensListener);
        frame.editorForegroundProperty().removeListener(themeTokensListener);
        frame.editorAccentProperty().removeListener(themeTokensListener);
        frame.editorMeterSafeProperty().removeListener(themeTokensListener);
        frame.editorMeterWarnProperty().removeListener(themeTokensListener);
        frame.editorMeterClipProperty().removeListener(themeTokensListener);
        frame.setOnReloadRequested(null);
        frame.setOnFaultDetailsRequested(null);
        frame.setOnDetachRequested(null);
        frame.setOnAbToggleRequested(null);
        frame.setOnAbCopyRequested(null);
        frame.setOnPresetSelected(null);
        frame.setOnSavePresetRequested(null);
        frame.setOnSaveAsPresetRequested(null);
    }

    // ── Factory pipeline (§8.2, §2.7) ─────────────────────────────────────

    /**
     * The whole factory pipeline: invoke {@code editorFactory()} (harnessed;
     * a throw substitutes {@link PluginEditorFactory.Faulted} per §2.7), read
     * {@code hints()} (harnessed; {@link EditorHints#compact()} fallback),
     * build the store / state / A/B from the resolved parameter list, apply
     * the hints to the frame, and build the mode-specific body. Run once at
     * open and again on every Reload.
     */
    private void buildEditor() {
        bodyFaulted = false;

        PluginEditorFactory factory;
        try {
            factory = Objects.requireNonNull(
                    plugin.editorFactory(), "editorFactory() returned null");
        } catch (Throwable t) {
            factory = new PluginEditorFactory.Faulted(t, "editorFactory");
        }

        EditorHints hints;
        try {
            hints = Objects.requireNonNull(factory.hints(), "hints() returned null");
        } catch (Throwable t) {
            faultBanner("hints", t);
            hints = EditorHints.compact();
        }

        // §2.6 — the store is the only bridge to the audio side. For a
        // Declarative editor it must carry the FACTORY's list (the record may
        // differ from getParameters()); an empty factory list falls back to
        // getParameters(). The store constructor rejects duplicate ids —
        // plugin-caused, so that too degrades via the harness.
        List<PluginParameter> parameters = resolveParameters(factory);
        try {
            store = new PluginParameterStore(parameters);
        } catch (Throwable t) {
            faultBanner("getParameters", t);
            parameters = List.of();
            store = new PluginParameterStore(parameters);
        }
        state = new PluginParameterState(parameters);
        abComparison = new ABComparison(state);
        frame.setAbActiveSlot(abComparison.getActiveSlot());

        // §6.3 — the host applies (and enforces) the size / resize / chrome
        // hints; the plugin cannot resize itself.
        frame.setPreferredBodyWidth(hints.preferredWidth());
        frame.setPreferredBodyHeight(hints.preferredHeight());
        frame.setResizePolicy(hints.resize());
        frame.setChromePolicy(hints.chrome());

        switch (factory) {
            case PluginEditorFactory.Declarative _ -> buildDeclarativeBody(parameters);
            case PluginEditorFactory.Panel p -> buildPanelBody(p);
            case PluginEditorFactory.Canvas c -> buildCanvasBody(c);
            case PluginEditorFactory.Faulted f -> buildFaultedBody(f);
        }
    }

    /**
     * The parameter list the store / state / grid are built from: a
     * Declarative factory's own (non-empty) list wins, everything else reads
     * {@code getParameters()} (harnessed — a throw degrades to an empty
     * list, banner shown, editor still built).
     */
    private List<PluginParameter> resolveParameters(PluginEditorFactory factory) {
        if (factory instanceof PluginEditorFactory.Declarative d
                && !d.parameters().isEmpty()) {
            return d.parameters();
        }
        try {
            return Objects.requireNonNull(
                    plugin.getParameters(), "getParameters() returned null");
        } catch (Throwable t) {
            faultBanner("getParameters", t);
            return List.of();
        }
    }

    /**
     * §6.2 — the host-generated grid. The sink is the session's single-writer
     * entry: a user gesture lands in the {@link PluginParameterState} mirror
     * (the A/B / preset snapshot source) and the store's UI→audio ring; echo
     * re-entry is already guarded inside the grid.
     */
    private void buildDeclarativeBody(List<PluginParameter> parameters) {
        grid = new ParameterGridPanel(parameters, this::onGridGesture);
        frame.setBody(grid);
    }

    private void onGridGesture(int parameterId, double newValue) {
        state.setValue(parameterId, newValue);
        store.writeFromUiById(parameterId, newValue);
    }

    /** §6.3 — the plugin's own region, harnessed; a {@code null} return is a fault. */
    private void buildPanelBody(PluginEditorFactory.Panel panel) {
        Region body;
        try {
            body = panel.createPanel(context);
            if (body == null) {
                throw new NullPointerException("createPanel returned null");
            }
        } catch (Throwable t) {
            faultAndSubstitute("createPanel", t);
            return;
        }
        frame.setBody(body);
    }

    /**
     * §5.D / §6.3 — the host-owned canvas. {@link Canvas} is not a
     * {@link Region}, so it cannot be resized by layout: the wrapper hosts it
     * and listeners mirror the wrapper's size onto the canvas (then request a
     * render). Harnessed {@code attach} followed by exactly one synchronous
     * initial render; all further renders are event-driven / fps-paced.
     */
    private void buildCanvasBody(PluginEditorFactory.Canvas factory) {
        canvas = new Canvas();
        Pane host = new Pane(canvas);
        host.getStyleClass().add("editor-canvas-host");
        host.widthProperty().addListener((obs, was, now) -> {
            canvas.setWidth(now.doubleValue());
            scheduleRender();
        });
        host.heightProperty().addListener((obs, was, now) -> {
            canvas.setHeight(now.doubleValue());
            scheduleRender();
        });
        surface = new FxCanvasSurface(canvas, this::scheduleRender);
        canvasFactory = factory;
        frame.setBody(host);
        try {
            factory.attach(surface);
            canvasAttached = true;
        } catch (Throwable t) {
            faultAndSubstitute("attach", t);
            return;
        }
        renderNow();
    }

    /** §6.6 — the host-substituted faulted editor: banner + placeholder, never a void. */
    private void buildFaultedBody(PluginEditorFactory.Faulted faulted) {
        bodyFaulted = true;
        faultBanner(faulted.hint(), faulted.cause());
        frame.setBody(buildFaultPlaceholder());
    }

    private Node buildFaultPlaceholder() {
        Label label = new Label(msg("editor.frame.fault.placeholder"));
        label.setWrapText(true);
        VBox placeholder = new VBox(label);
        placeholder.getStyleClass().add("editor-fault-placeholder");
        placeholder.setAlignment(Pos.CENTER);
        return placeholder;
    }

    // ── Fault harness (§2.7, §4.7, §6.6) ─────────────────────────────────

    /**
     * A fault that killed the body: banner + substitute the placeholder +
     * (canvas-side) stop the fps loop and best-effort detach. Substitutes at
     * most once per build — a render fault inside the timer swaps once,
     * never per-tick spam; repeats are still routed to the fault log.
     */
    private void faultAndSubstitute(String phase, Throwable t) {
        if (bodyFaulted) {
            reportToSupervisor(t);
            return;
        }
        bodyFaulted = true;
        faultBanner(phase, t);
        frame.setBody(buildFaultPlaceholder());
        grid = null;
        teardownCanvas();
    }

    /**
     * Banner + fault-log routing without substituting the body — for faults
     * with a viable fallback (hints, getParameters) and the §4.8 posted-fault
     * channel.
     */
    private void faultBanner(String phase, Throwable t) {
        reportToSupervisor(t);
        frame.setFaultMessage(String.format(Locale.ROOT,
                msg("editor.frame.fault.bannerFormat"),
                displayName, t.getClass().getSimpleName(), phase));
    }

    /**
     * Routes a fault to the {@link PluginInvocationSupervisor} (§6.6 — editor
     * faults are logged and published but never quarantine the audio side).
     * Tolerates a missing supervisor: the deps member and its supplier result
     * may both be {@code null}.
     */
    private void reportToSupervisor(Throwable t) {
        PluginInvocationSupervisor supervisor =
                deps.faultSupervisor() == null ? null : deps.faultSupervisor().get();
        if (supervisor != null) {
            supervisor.reportUiFault(faultReportId, t);
        }
        LOG.log(Level.WARNING, "Plugin editor fault [" + faultReportId + "]", t);
    }

    /**
     * §4.8 — a plugin-reported <em>recoverable</em> fault
     * ({@code EditorContext#postFault}): banner + fault log, but the body is
     * NOT substituted and the editor keeps running.
     */
    void postFault(Throwable error, String hint) {
        Objects.requireNonNull(error, "error must not be null");
        Objects.requireNonNull(hint, "hint must not be null");
        reportToSupervisor(error);
        frame.setFaultMessage(String.format(Locale.ROOT,
                msg("editor.frame.fault.postedFormat"),
                hint, error.getClass().getSimpleName()));
    }

    /** §6.6 {@code [Reload]} — tear the body down and re-run the whole pipeline. */
    private void reload() {
        if (disposed) {
            return;
        }
        teardownCanvas();
        grid = null;
        frame.setFaultMessage("");
        buildEditor();
    }

    private void openFaultLog() {
        if (deps.openPluginFaultLog() != null) {
            deps.openPluginFaultLog().run();
        }
    }

    /**
     * Stops the fps loop and best-effort-detaches an attached canvas factory.
     * The detach is itself harnessed (report-only — no banner, no recursive
     * substitution on failure). A no-op outside Canvas mode.
     */
    private void teardownCanvas() {
        animationFps = 0.0;
        if (canvasFactory != null && canvasAttached) {
            try {
                canvasFactory.detach();
            } catch (Throwable t) {
                reportToSupervisor(t);
            }
        }
        canvasAttached = false;
        canvasFactory = null;
        surface = null;
        canvas = null;
        lastRenderNanos = 0;
    }

    // ── A/B (§6.5) ────────────────────────────────────────────────────────

    /**
     * §6.5 swap. Panel / Canvas editors write to the store directly, so the
     * {@link PluginParameterState} mirror is refreshed from the store at
     * every snapshot point before {@link ABComparison} captures / loads;
     * afterwards the loaded slot is pushed back into the store and echoed
     * into the grid.
     */
    private void onAbToggle() {
        if (disposed) {
            return;
        }
        refreshStateFromStore();
        abComparison.toggle();
        frame.setAbActiveSlot(abComparison.getActiveSlot());
        pushStateToStoreAndGrid();
    }

    /** §6.5 {@code [Copy A▸B]} — snapshot the store, copy active → inactive. */
    private void onAbCopy() {
        if (disposed) {
            return;
        }
        refreshStateFromStore();
        abComparison.copyActiveToInactive();
    }

    /** Snapshot point: mirror every store value into the A/B / preset state. */
    private void refreshStateFromStore() {
        for (int i = 0; i < store.parameterCount(); i++) {
            state.setValue(store.parameterIdAt(i), store.value(i));
        }
    }

    /**
     * Pushes every state value into the store (the audio side sees the swap /
     * preset), echoes the grid in one guarded bulk write, and schedules a
     * canvas repaint (a UI-side value change never travels the audio→UI ring,
     * so the canvas must be repainted explicitly).
     */
    private void pushStateToStoreAndGrid() {
        Map<Integer, Double> values = state.getAllValues();
        for (Map.Entry<Integer, Double> entry : values.entrySet()) {
            store.writeFromUiById(entry.getKey(), entry.getValue());
        }
        if (grid != null) {
            grid.setAllValues(values);
        }
        scheduleRender();
    }

    // ── Presets (§6.4) ────────────────────────────────────────────────────

    private void loadPresetsIntoFrame() {
        presetsByName.clear();
        try {
            for (ParameterPreset preset : presetManager.loadAllPresets()) {
                presetsByName.put(preset.name(), preset);
            }
        } catch (IOException e) {
            LOG.fine(() -> "Could not load presets for " + faultReportId + ": " + e);
        }
        frame.getPresetItems().setAll(presetsByName.keySet());
    }

    private void onPresetSelected(String name) {
        if (disposed || name == null) {
            return;
        }
        ParameterPreset preset = presetsByName.get(name);
        if (preset == null) {
            return;
        }
        state.loadValues(preset.values());
        pushStateToStoreAndGrid();
        frame.setSelectedPreset(name);
    }

    /** §6.4 {@code [Save…]} — overwrite the selected preset, else fall through to Save As. */
    private void onSavePreset() {
        if (disposed) {
            return;
        }
        refreshStateFromStore();
        String selected = frame.getSelectedPreset();
        if (selected == null || selected.isBlank()) {
            promptAndSavePresetAs();
            return;
        }
        savePreset(selected);
    }

    /** §6.4 {@code [Save As…]} — prompt for a name, then persist. */
    private void onSaveAsPreset() {
        if (disposed) {
            return;
        }
        refreshStateFromStore();
        promptAndSavePresetAs();
    }

    private void promptAndSavePresetAs() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle(msg("editor.frame.preset.saveAs.title"));
        dialog.setHeaderText(msg("editor.frame.preset.saveAs.header"));
        dialog.setContentText(msg("editor.frame.preset.saveAs.prompt"));
        themeManager.applyTo(dialog.getDialogPane());
        dialog.showAndWait()
                .map(String::strip)
                .filter(name -> !name.isEmpty())
                .ifPresent(this::savePreset);
    }

    private void savePreset(String name) {
        ParameterPreset preset = ParameterPreset.user(name, state.getAllValues());
        try {
            presetManager.savePreset(preset);
        } catch (IOException e) {
            LOG.log(Level.WARNING,
                    "Failed to save preset '" + name + "' for " + faultReportId, e);
            if (deps.showNotification() != null) {
                deps.showNotification().accept(NotificationLevel.ERROR,
                        String.format(Locale.ROOT,
                                msg("editor.frame.preset.saveError"), e.getMessage()));
            }
            return;
        }
        presetsByName.put(name, preset);
        if (!frame.getPresetItems().contains(name)) {
            frame.getPresetItems().add(name);
        }
        frame.setSelectedPreset(name);
    }

    /**
     * Per-plugin preset home: {@code ~/.daw/plugin-presets/<sanitized-id>}
     * (the {@code ~/.daw} convention shared with autosaves and the render
     * queue). Sanitising replaces any character outside
     * {@code [a-zA-Z0-9._-]} with {@code _} so an arbitrary plugin id is
     * always a valid directory name.
     */
    private static Path presetRoot(String pluginId) {
        return Path.of(System.getProperty("user.home", "."),
                ".daw", "plugin-presets", sanitizeDirectoryName(pluginId));
    }

    private static String sanitizeDirectoryName(String pluginId) {
        return pluginId.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    // ── Host services reached through HostEditorContext ─────────────────

    /** @return the current resolved SDK theme (§2.5). */
    Theme currentTheme() {
        return theme.get();
    }

    /** @return the read-only observable theme handed to plugin editors (§2.5). */
    ObservableValue<Theme> themeProperty() {
        return theme.getReadOnlyProperty();
    }

    /**
     * §6.3 — coerces a plugin resize request per the CURRENT resize policy
     * (the host enforces; the plugin cannot resize itself): {@code FIXED}
     * ignores it, {@code HORIZONTAL}/{@code VERTICAL} honour one axis,
     * {@code BOTH} honours both. Sizes clamp to ≥ 1 px.
     */
    void requestResize(int width, int height) {
        if (disposed) {
            return;
        }
        double w = Math.max(1, width);
        double h = Math.max(1, height);
        switch (frame.getResizePolicy()) {
            case FIXED -> {
                // Fixed on both axes — the request is ignored (§6.3).
            }
            case HORIZONTAL -> frame.setPreferredBodyWidth(w);
            case VERTICAL -> frame.setPreferredBodyHeight(h);
            case BOTH -> {
                frame.setPreferredBodyWidth(w);
                frame.setPreferredBodyHeight(h);
            }
        }
    }

    /**
     * §6.3 — clamps an opted-in animation rate into [{@value MIN_FPS},
     * {@value MAX_FPS}]; a value {@code <= 0} cancels the loop, returning the
     * editor to purely event-driven rendering.
     */
    void setAnimationFps(double framesPerSecond) {
        if (framesPerSecond <= 0.0) {
            animationFps = 0.0;
            return;
        }
        animationFps = Math.clamp(framesPerSecond, MIN_FPS, MAX_FPS);
    }

    /**
     * Schedules one coalesced canvas render on the next dispatcher pulse
     * (§6.3 — N requests within a frame collapse to the latest via the
     * story-289 keyed seam). A no-op outside Canvas mode; renders immediately
     * when no dispatcher is installed (pure-unit context).
     */
    void scheduleRender() {
        if (disposed || canvasFactory == null) {
            return;
        }
        if (dispatcher != null) {
            dispatcher.onFx(renderKey, renderRunnable);
        } else {
            renderNow();
        }
    }

    // ── Render loop (§5.D, §6.3) ──────────────────────────────────────────

    /**
     * Renders one frame: builds the {@link RenderTick} (delta from the
     * previous render, {@code 0.0} first; monotonic frame index; the current
     * resolved theme) and calls the plugin's {@code render} inside the
     * harness. The body host clips the canvas region (§6.3), so nothing
     * extra guards the graphics context here.
     */
    private void renderNow() {
        if (disposed || bodyFaulted || canvasFactory == null || !canvasAttached) {
            return;
        }
        long now = System.nanoTime();
        double delta = lastRenderNanos == 0
                ? 0.0
                : Math.max(0.0, (now - lastRenderNanos) / 1e9);
        lastRenderNanos = now;
        RenderTick tick = new RenderTick(delta, frameIndex++, theme.get());
        try {
            canvasFactory.render(tick);
        } catch (Throwable t) {
            faultAndSubstitute("render", t);
        }
    }

    // ── Theme bridge (§2.5) ───────────────────────────────────────────────

    /**
     * Builds the SDK {@link Theme} from the frame's six RESOLVED styleable
     * colours — the styles.css role-token forwarding seam — never a mirrored
     * hex literal. Null-hardened against a degenerate CSS write with the
     * SDK's own neutral fallback.
     */
    private Theme readThemeFromFrame() {
        Theme neutral = Theme.neutral();
        return new Theme(
                orFallback(frame.getEditorBackground(), neutral.background()),
                orFallback(frame.getEditorForeground(), neutral.foreground()),
                orFallback(frame.getEditorAccent(), neutral.accent()),
                orFallback(frame.getEditorMeterSafe(), neutral.meterSafe()),
                orFallback(frame.getEditorMeterWarn(), neutral.meterWarn()),
                orFallback(frame.getEditorMeterClip(), neutral.meterClip()));
    }

    private static <T> T orFallback(T value, T fallback) {
        return value == null ? fallback : value;
    }

    private void rebuildTheme() {
        theme.set(readThemeFromFrame());
        scheduleRender();
    }

    /**
     * Scene lifecycle: entering a scene forces {@code applyCss()} (paired
     * with the token listeners per the resolved-CSS discipline) so the first
     * theme read is resolved rather than the neutral fallback, then starts
     * the per-editor timer; leaving the scene stops it, so a hidden editor
     * never spins a frame loop. The scene's window is tracked alongside
     * ({@link #trackWindow}) so a canvas editor's self-scheduled loop — which
     * stops itself while the window is hidden — is kicked back to life when
     * the window shows again.
     */
    private void onSceneChanged(ObservableValue<? extends Scene> observable,
            Scene oldScene, Scene newScene) {
        if (disposed) {
            return;
        }
        if (oldScene != null) {
            oldScene.windowProperty().removeListener(windowListener);
        }
        if (newScene != null) {
            newScene.windowProperty().addListener(windowListener);
            trackWindow(newScene.getWindow());
            frame.applyCss();
            rebuildTheme();
            timer.start();
        } else {
            trackWindow(null);
            timer.stop();
        }
    }

    /**
     * Retargets {@link #windowShowingListener} at the scene's current window
     * (detaching it from the previously remembered one — never a re-read of
     * a possibly-changed supplier), and, mirroring the listener itself,
     * schedules a repaint when the new window is already showing so a canvas
     * editor moved into a live window resumes its self-scheduled loop.
     */
    private void trackWindow(Window window) {
        if (trackedWindow == window) {
            return;
        }
        if (trackedWindow != null) {
            trackedWindow.showingProperty().removeListener(windowShowingListener);
        }
        trackedWindow = window;
        if (window != null) {
            window.showingProperty().addListener(windowShowingListener);
            if (window.isShowing()) {
                scheduleRender();
            }
        }
    }

    // ── Session timer ─────────────────────────────────────────────────────

    /**
     * The session's ONE per-frame loop (class-level
     * {@code @FxAnimationTimerAllowed} sentinel): (1) drain the store's
     * audio→UI ring — Declarative echoes into the grid, Canvas marks a
     * repaint; (2) mirror the latest meter snapshot (reference compare —
     * meters are a latest-wins channel, §6.4); (3) pace the opted-in canvas
     * fps. Allocation-light: the sink and runnable are pre-allocated fields.
     */
    private void tick(long nowNanos) {
        if (disposed) {
            return;
        }
        drainRenderNeeded = false;
        store.drainToUi(drainSink);
        if (drainRenderNeeded) {
            renderNow();
        }
        PluginMeterSnapshot meters = store.meters();
        if (meters != frame.getMeters()) {
            frame.setMeters(meters);
        }
        if (canvasFactory != null && animationFps > 0.0
                && (lastRenderNanos == 0
                        || nowNanos - lastRenderNanos >= (long) (1e9 / animationFps))) {
            renderNow();
        }
    }

    /** Pre-allocated drain sink — one audio-side change, by dense index. */
    private void onStoreIndexDrained(int index) {
        if (grid != null) {
            grid.updateValue(store.parameterIdAt(index), store.value(index));
        } else if (canvasFactory != null) {
            drainRenderNeeded = true;
        }
    }

    /** The one timer this session owns; its {@code handle} body is {@link #tick}. */
    private final class SessionTimer extends AnimationTimer {
        @Override
        public void handle(long now) {
            tick(now);
        }
    }

    /**
     * Resolves a chrome string from the shared bundle, falling back to the
     * raw key if absent (mirrors {@code EditorFrameSkin#msg(String)}).
     */
    private static String msg(String key) {
        try {
            return MESSAGES.getString(key);
        } catch (MissingResourceException e) {
            return key;
        }
    }
}
