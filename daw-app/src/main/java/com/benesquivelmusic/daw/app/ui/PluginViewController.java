package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.plugin.PluginEditorSession;
import com.benesquivelmusic.daw.core.plugin.*;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.spatial.binaural.HrtfProfileLibrary;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import javafx.scene.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages plugin activation, the contract-driven editor session, and plugin
 * lifecycle (dispose on shutdown).
 *
 * <p>Extracted from {@code MainController} to separate plugin view management
 * from the main coordinator.</p>
 *
 * <p>Story 302 (Plugin View Design Book §8.3 Phase 3) removed the legacy
 * built-in view {@code switch} and its fourteen {@code open…Window(...)}
 * helpers: every plugin — built-in or third-party — now opens through the
 * §8.2.1 {@code editorFactory()} contract path, and no plugin id drives
 * behaviour anywhere in this class (§9 rejection #5). The one non-editor
 * route left is declared by the plugin itself:
 * {@link BuiltInDawPlugin#routesToMasteringView()}.</p>
 */
final class PluginViewController {

    private static final Logger LOG = Logger.getLogger(PluginViewController.class.getName());

    /**
     * Story 294 — direct functional deps replace the callback-up {@code Host}
     * (Control Synchronization Design Book §4.2/§9 "use publish/subscribe, not a
     * callback-up {@code Host} for cross-surface updates"). {@code sampleRate}/
     * {@code bufferSize} are primitive {@link DoubleSupplier}/{@link IntSupplier}
     * reads; the swappable project is a live {@link Supplier} (read live, so a
     * project load is reflected without rebuilding this controller); mastering
     * navigation and dirty are {@link Runnable}s ({@code markProjectDirty}
     * routes through {@code DawProject.markDirty()} — the §1.2 "one dirty
     * bit"); status and notification are {@link BiConsumer} sinks.
     *
     * <p>Story 301 (Plugin View Design Book §8.2) appended the contract-editor
     * services: {@code showEditorInWorkshopPane} hosts an {@code EditorFrame}
     * in the Workshop right pane's stable-identity container (§8.2.2 —
     * breadcrumb segments plus the editor node; a {@code null} node clears the
     * pane); {@code faultSupervisor} is a live {@link Supplier} (the
     * {@link PluginInvocationSupervisor} is constructed after this controller
     * at startup) feeding the §6.6 editor fault harness; and
     * {@code openPluginFaultLog} backs the fault banner's {@code [ⓘ]}
     * action.</p>
     *
     * <p>Story 302 removed the {@code showTelemetryPanel} member: its only
     * consumer was the deleted Sound Wave Telemetry {@code switch} arm. The
     * docked telemetry panel remains reachable through the dock manifest UI;
     * the plugin's editor now comes from its own {@code editorFactory()}.</p>
     */
    record Deps(
            DoubleSupplier sampleRate,
            IntSupplier bufferSize,
            Supplier<DawProject> project,
            Runnable markProjectDirty,
            Runnable switchToMasteringView,
            BiConsumer<String, DawIcon> updateStatusBar,
            BiConsumer<NotificationLevel, String> showNotification,
            BiConsumer<List<String>, Node> showEditorInWorkshopPane,
            Supplier<PluginInvocationSupervisor> faultSupervisor,
            Runnable openPluginFaultLog) {
    }

    private final Deps deps;
    private final Map<Class<? extends BuiltInDawPlugin>, BuiltInDawPlugin> builtInPluginCache = new HashMap<>();
    private final HrtfProfileLibrary hrtfProfileLibrary = new HrtfProfileLibrary();

    /**
     * The single live contract-editor session (story 301 §8.2). Opening
     * another plugin's editor disposes the previous session first — the
     * Workshop right pane hosts one focused editor at a time (§5.A).
     */
    private PluginEditorSession activeEditorSession;

    /**
     * External plugins this controller has already {@code initialize(...)}d
     * (story 301 — initialise-once, identity-keyed: two registry entries that
     * load the same instance must not double-initialise it). Activation and
     * editor opening repeat per request; disposal stays with the
     * {@link PluginRegistry}, which owns the external-plugin lifecycle.
     */
    private final Set<DawPlugin> initializedExternalPlugins =
            Collections.newSetFromMap(new IdentityHashMap<>());

    PluginViewController(Deps deps) {
        this.deps = deps;
    }

    void onManagePlugins(PluginRegistry pluginRegistry) {
        deps.updateStatusBar().accept("Opening plugin manager...", com.benesquivelmusic.daw.app.ui.icons.DawIcon.MENU);
        PluginManagerDialog dialog = new PluginManagerDialog(pluginRegistry);
        dialog.showAndWait();
        deps.updateStatusBar().accept("Plugin manager closed", com.benesquivelmusic.daw.app.ui.icons.DawIcon.SETTINGS);
    }

    void onActivateBuiltInPlugin(Class<? extends BuiltInDawPlugin> pluginClass) {
        try {
            BuiltInDawPlugin plugin = builtInPluginCache.computeIfAbsent(pluginClass, cls -> {
                try {
                    BuiltInDawPlugin instance = cls.getConstructor().newInstance();
                    PluginContext pluginContext = new PluginContext() {
                        @Override public double getSampleRate() { return deps.sampleRate().getAsDouble(); }
                        @Override public int getBufferSize() { return deps.bufferSize().getAsInt(); }
                        @Override public void log(String message) { LOG.info(message); }
                    };
                    instance.initialize(pluginContext);
                    return instance;
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException("Failed to instantiate built-in plugin: " + cls.getName(), e);
                }
            });
            deps.updateStatusBar().accept("Activating " + plugin.getMenuLabel() + "...", null);
            plugin.activate();
            openEditor(plugin);
            deps.updateStatusBar().accept(plugin.getMenuLabel() + " activated", null);
            LOG.fine("Activated built-in plugin: " + plugin.getMenuLabel());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to activate built-in plugin: " + pluginClass.getName(), e);
            deps.updateStatusBar().accept("Failed to activate " + pluginClass.getSimpleName(), null);
            deps.showNotification().accept(NotificationLevel.ERROR,
                    "Failed to activate " + pluginClass.getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Activates an external (third-party) plugin from the registry and opens
     * its contract-driven editor (story 301 §8.2.1 — "third-party plugins now
     * have editors"). Mirrors {@link #onActivateBuiltInPlugin}: first
     * activation initialises the plugin with the same anonymous
     * {@code PluginContext} shape (initialise-once, identity-keyed), every
     * activation calls {@code activate()} then routes through
     * {@link #openEditor(DawPlugin)}. The registry keeps ownership of the
     * plugin's lifecycle — this controller never disposes it.
     *
     * @param plugin the loaded external plugin to activate; must not be
     *               {@code null}
     */
    void onActivateExternalPlugin(DawPlugin plugin) {
        try {
            if (initializedExternalPlugins.add(plugin)) {
                PluginContext pluginContext = new PluginContext() {
                    @Override public double getSampleRate() { return deps.sampleRate().getAsDouble(); }
                    @Override public int getBufferSize() { return deps.bufferSize().getAsInt(); }
                    @Override public void log(String message) { LOG.info(message); }
                };
                plugin.initialize(pluginContext);
            }
            String label = plugin.getDescriptor().name();
            deps.updateStatusBar().accept("Activating " + label + "...", null);
            plugin.activate();
            openEditor(plugin);
            deps.updateStatusBar().accept(label + " activated", null);
            LOG.fine("Activated external plugin: " + label);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to activate external plugin: " + plugin.getClass().getName(), e);
            deps.updateStatusBar().accept("Failed to activate " + plugin.getClass().getSimpleName(), null);
            deps.showNotification().accept(NotificationLevel.ERROR,
                    "Failed to activate " + plugin.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    void dispose() {
        // Story 301 — tear down the live contract-editor session. External
        // plugins themselves are NOT disposed here: the PluginRegistry owns
        // their lifecycle.
        if (activeEditorSession != null) {
            activeEditorSession.dispose();
            activeEditorSession = null;
        }
        try {
            for (BuiltInDawPlugin plugin : builtInPluginCache.values()) {
                try {
                    plugin.dispose();
                } catch (Exception ex) {
                    LOG.log(Level.WARNING,
                            "Failed to dispose built-in plugin: " + plugin.getClass().getName(), ex);
                }
            }
        } finally {
            builtInPluginCache.clear();
        }
    }

    /**
     * Opens a plugin's editor (story 302, Plugin View Design Book §8.3 Phase 3
     * complete): every plugin — built-in or third-party — routes through its
     * own {@code editorFactory()} and is rendered in the standard
     * {@link com.benesquivelmusic.daw.app.ui.plugin.EditorFrame} chrome
     * ({@link #openContractEditor(DawPlugin)}). The only non-editor route is
     * declared by the plugin itself: a built-in whose
     * {@link BuiltInDawPlugin#routesToMasteringView()} is {@code true} (the
     * mastering-chain stages) surfaces in the mastering view instead. No
     * plugin id drives behaviour here — the legacy id {@code switch} was
     * deleted with its bespoke views (§9 rejection #5).
     *
     * @param plugin the activated plugin to open an editor for
     */
    void openEditor(DawPlugin plugin) {
        if (plugin instanceof BuiltInDawPlugin builtIn && builtIn.routesToMasteringView()) {
            deps.switchToMasteringView().run();
            return;
        }
        openContractEditor(plugin);
    }

    /**
     * Story 301 §8.2 — opens the contract-driven editor: disposes any
     * previous session, runs the plugin's {@code editorFactory()} pipeline
     * through {@link PluginEditorSession#open} (never throws for
     * plugin-caused failures — faults degrade to the §6.6 banner), wires the
     * controller-owned close intent (the one frame callback the session
     * deliberately leaves to its host), and places the framed editor in the
     * Workshop right pane's stable-identity container (§8.2.2).
     */
    private void openContractEditor(DawPlugin plugin) {
        if (activeEditorSession != null) {
            activeEditorSession.dispose();
            activeEditorSession = null;
        }
        PluginEditorSession session = PluginEditorSession.open(plugin, null,
                new PluginEditorSession.Deps(
                        deps.sampleRate(),
                        deps.faultSupervisor(),
                        deps.openPluginFaultLog(),
                        deps.showNotification()));
        activeEditorSession = session;
        // §6.1 [×] close — focus-only: the session is torn down and the pane
        // cleared, but the plugin stays registered and activated.
        session.frame().setOnCloseRequested(() -> {
            session.dispose();
            if (activeEditorSession == session) {
                activeEditorSession = null;
            }
            deps.showEditorInWorkshopPane().accept(List.of(), null);
        });
        List<String> segments = new ArrayList<>(2);
        addSegmentIfPresent(segments, session.frame().getVendor());
        addSegmentIfPresent(segments, session.frame().getPluginName());
        deps.showEditorInWorkshopPane().accept(segments, session.frame());
        deps.updateStatusBar().accept(
                "Opened editor for " + session.frame().getPluginName(), null);
    }

    private static void addSegmentIfPresent(List<String> segments, String value) {
        if (value != null && !value.isBlank()) {
            segments.add(value);
        }
    }

    /**
     * @return the live contract-editor session, or {@code null} — test seam
     *         (story 301)
     */
    PluginEditorSession activeEditorSessionForTest() {
        return activeEditorSession;
    }

    /**
     * Opens the standalone "Manage HRTF Profiles…" dialog (Settings entry-point
     * for story 174). Surfaced as a {@code public} method so the menu bar can
     * route a "Settings → HRTF Profiles…" action through the plugin controller.
     *
     * <p>Story 302 moved the story-174 per-project active-profile persistence
     * here from the retired {@code BinauralMonitorPluginView}: the dialog is
     * now the selection surface, and the profile chosen at close is persisted
     * on the active project (marking it dirty on a real change). The
     * load-time resolve-with-fallback-warning path retired with the view.</p>
     */
    public void onManageHrtfProfiles() {
        HrtfProfileBrowserDialog dialog = new HrtfProfileBrowserDialog(
                hrtfProfileLibrary, deps.sampleRate().getAsDouble());
        dialog.showAndWait().ifPresent(selected -> {
            String current = deps.project().get().getActiveHrtfProfileName();
            if (!selected.equals(current)) {
                deps.project().get().setActiveHrtfProfileName(selected);
                deps.markProjectDirty().run();
            }
        });
    }
}
