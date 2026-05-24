package com.benesquivelmusic.daw.app.ui.views;

import com.benesquivelmusic.daw.app.ui.ClipEditorFactory;
import com.benesquivelmusic.daw.app.ui.inspector.InspectorSelection;
import com.benesquivelmusic.daw.app.ui.inspector.InspectorSelectionModel;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.clip.Clip;
import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.project.ProjectLookups;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.event.ClipEvent;
import com.benesquivelmusic.daw.sdk.event.DispatchMode;
import com.benesquivelmusic.daw.sdk.event.EventBus;
import com.benesquivelmusic.daw.sdk.event.MixerEvent;
import com.benesquivelmusic.daw.sdk.event.PluginEvent;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;

import javafx.beans.value.ChangeListener;
import javafx.scene.Node;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Glue controller that pushes inspector selections into a
 * {@link WorkshopView}'s right-pane plugin focus and clip-detail slots
 * (story 281 Task 2 — selection-driven plugin GUI / clip-detail wiring).
 *
 * <h2>What it wires</h2>
 *
 * <p>Listens on a shared {@link InspectorSelectionModel} and responds to:</p>
 *
 * <ul>
 *   <li>{@link InspectorSelection.InsertSelection} → resolves the
 *       {@link InsertSlot} via {@link ProjectLookups#findInsertSlot},
 *       builds (or looks up cached) the
 *       {@code PluginParameterEditorPanel} for that slot's plugin, and
 *       calls {@link WorkshopView#setFocusedPlugin(int, String, Node)}.</li>
 *   <li>{@link InspectorSelection.ClipSelection} → resolves the
 *       {@link AudioClip} via {@link ProjectLookups#findAudioClip}, builds
 *       (or looks up cached) the editor Node via
 *       {@link ClipEditorFactory#buildEditor}, and calls
 *       {@link WorkshopView#setClipDetailContent(Node)}.</li>
 *   <li>{@link InspectorSelection.TrackSelection} → restores the
 *       last-focused insert for that track (if any) by delegating to the
 *       {@code InsertSelection} path above; clears the focused-plugin pane
 *       only when no insert has ever been focused for the selected track.
 *       Clip-detail is always cleared on track selection.</li>
 *   <li>Any other selection (send, bus, empty) → clears the
 *       focused-plugin pane back to its empty placeholder. Clip selection
 *       transitions to non-clip also clear the clip-detail slot.</li>
 * </ul>
 *
 * <h2>Stable identity</h2>
 *
 * <p>Each built plugin-parameter panel is cached by
 * {@code (trackId, insertIndex)}; selecting the same insert twice returns
 * the same Node instance. Each built clip editor is cached by clip-id
 * String (audio) or by clip identity (MIDI) — selecting the same clip
 * twice returns the same Node. This is the contract pinned by
 * {@code WorkshopPluginSwitchTest} (the right-pane container's identity)
 * extended one level deeper to the focused-plugin Node itself, so the
 * caller can rely on {@code isSameAs} across selection toggles.</p>
 *
 * <h2>View-activation gating</h2>
 *
 * <p>Plugin / clip-editor Nodes are only built when Workshop is the
 * active view; otherwise the selection is recorded as
 * <em>pending</em> and applied the next time Workshop becomes active
 * (via {@link #applyPendingOnWorkshopActivation()}). This avoids
 * pre-building plugin GUIs for users who never enter Workshop —
 * essential when those GUIs become real native windows in story 282.</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>The controller captures the {@link InspectorSelectionModel} once at
 * construction and adds a strong-reference {@link ChangeListener} that
 * lives exactly as long as this controller. {@link #dispose()} removes
 * the listener. Per memory "Capture swappable singleton reference once",
 * the project is read via a {@link Supplier} on every event (the project
 * is itself swappable in {@code MainController}) — never cached.</p>
 */
public final class WorkshopSelectionHostController {

    private static final Logger LOG = Logger.getLogger(
            WorkshopSelectionHostController.class.getName());

    private final WorkshopView workshopView;
    private final InspectorSelectionModel selectionModel;
    private final Supplier<DawProject> projectSupplier;
    private final BooleanSupplier workshopActiveSupplier;
    private final java.util.ResourceBundle messages;
    /**
     * Captured EventBus reference (story 283 S3). Per the "Capture
     * swappable singleton reference once" memory, the bus is read once
     * at construction time and stored in a {@code final} field; the
     * three Subscription handles below are registered against this
     * specific instance, never re-read via {@code EventBusPublisher.getDefault()}.
     * May be {@code null} in tests that don't wire a bus — subscription
     * registration is skipped in that case so the controller stays
     * usable.
     */
    private final EventBus eventBus;

    /**
     * Strong-reference listener field — lives exactly as long as this
     * controller so the selection model never silently orphans the wiring.
     */
    private final ChangeListener<InspectorSelection> selectionListener;

    /**
     * Subscription handle for {@link PluginEvent.Unloaded}: removes any
     * cached plugin panel whose slot's pluginInstanceId equals the
     * event's. {@code null} when {@link #eventBus} is null. Closed in
     * {@link #dispose()}.
     */
    private EventBus.Subscription pluginUnloadedSubscription;

    /**
     * Subscription handle for {@link MixerEvent.ChannelRemoved}: removes
     * every cached plugin panel whose key's {@code trackId} equals the
     * event's {@code channelId()} (per the channelId==trackId invariant
     * from {@code DawProject.addTrack}). {@code null} when
     * {@link #eventBus} is null.
     */
    private EventBus.Subscription channelRemovedSubscription;

    /**
     * Subscription handle for {@link ClipEvent.Removed}: evicts the
     * corresponding entry from the clip-editor cache and disposes the
     * editor's resources. {@code null} when {@link #eventBus} is null.
     */
    private EventBus.Subscription clipRemovedSubscription;

    /**
     * Cache of built plugin-parameter panels keyed by
     * {@code (trackId, insertIndex)}. Selecting the same insert twice
     * returns the same Node. Each entry stores the resolved
     * {@link InsertSlot} identity alongside the panel; on cache hit the
     * identity is compared to the currently-resolved slot — if it differs
     * (insert chain was edited), the stale entry is evicted and the panel
     * is rebuilt. This guarantees the cached panel always corresponds to
     * the live slot at that index.
     *
     * <p>Story 283 — event-driven invalidation lives on
     * {@link #pluginUnloadedSubscription} and
     * {@link #channelRemovedSubscription}.</p>
     */
    private final Map<InsertCacheKey, CachedPanel> pluginPanelCache = new HashMap<>();

    /**
     * Cache of built clip-editor Nodes keyed by clip identity. Audio clip
     * ids are stable UUID strings ({@link AudioClip#getId()}); MIDI clips
     * are cached in a separate {@link IdentityHashMap} keyed by object
     * identity to avoid collisions from non-unique identityHashCode values.
     *
     * <p>Story 283 — event-driven invalidation lives on
     * {@link #clipRemovedSubscription}.</p>
     */
    private final Map<String, Node> clipEditorCache = new HashMap<>();

    /**
     * Identity-based cache for MIDI clip editors. Uses object identity
     * (reference equality) as key to avoid collisions that would occur
     * with {@link System#identityHashCode(Object)}.
     */
    private final IdentityHashMap<Clip, Node> midiClipEditorCache = new IdentityHashMap<>();

    /**
     * Per-track last-focused insert index. Updated whenever an
     * {@link InspectorSelection.InsertSelection} is applied so that
     * switching back to a {@link InspectorSelection.TrackSelection}
     * can restore the most-recently-opened plugin for that track.
     */
    private final Map<UUID, Integer> lastFocusedInsertByTrack = new HashMap<>();

    /**
     * The most-recent selection that arrived while Workshop was inactive,
     * applied on the next activation. {@code null} when there is no
     * pending work.
     */
    private InspectorSelection pendingSelection;

    /**
     * The selection most recently pushed into the Workshop view by
     * {@link #applyPendingOnWorkshopActivation()}. Skips re-applying the
     * same selection on every enter/exit Workshop cycle when nothing
     * changed (review S2) — rebuilding panels (and the more expensive
     * clip editors) on a no-op transition is wasteful and can introduce
     * subtle re-bind flicker. Cleared in {@link #dispose()} so a fresh
     * controller starts with no memo.
     */
    private InspectorSelection lastApplied;

    /**
     * Creates the wiring controller. The selection-model listener is
     * installed immediately; the current selection is applied if Workshop
     * is already active.
     *
     * @param workshopView           the Workshop view whose right pane is
     *                               the push target (must not be {@code null})
     * @param selectionModel         the shared selection model — typically
     *                               the one held by {@code InspectorDrawer}
     *                               (must not be {@code null})
     * @param projectSupplier        read-once-per-event supplier of the
     *                               current {@link DawProject}; {@code null}
     *                               return is tolerated (no resolution
     *                               attempt is made)
     * @param workshopActiveSupplier returns {@code true} when Workshop is
     *                               the active view in the host's
     *                               navigation; supplier is re-read on
     *                               every event so it tracks live state
     * @param messages               the {@code Messages} resource bundle
     *                               for breadcrumb format strings
     *                               (Skill §14); must not be {@code null}
     * @param eventBus               story 283 — the bus this controller
     *                               subscribes to for cache-invalidation
     *                               events ({@code PluginEvent.Unloaded},
     *                               {@code MixerEvent.ChannelRemoved},
     *                               {@code ClipEvent.Removed}); may be
     *                               {@code null} for tests that don't
     *                               care, in which case subscription
     *                               registration is skipped
     */
    public WorkshopSelectionHostController(WorkshopView workshopView,
                                            InspectorSelectionModel selectionModel,
                                            Supplier<DawProject> projectSupplier,
                                            BooleanSupplier workshopActiveSupplier,
                                            java.util.ResourceBundle messages,
                                            EventBus eventBus) {
        this.workshopView = Objects.requireNonNull(workshopView,
                "workshopView must not be null");
        this.selectionModel = Objects.requireNonNull(selectionModel,
                "selectionModel must not be null");
        this.projectSupplier = Objects.requireNonNull(projectSupplier,
                "projectSupplier must not be null");
        this.workshopActiveSupplier = Objects.requireNonNull(workshopActiveSupplier,
                "workshopActiveSupplier must not be null");
        this.messages = Objects.requireNonNull(messages,
                "messages must not be null");
        // eventBus may be null — tests that don't wire it skip
        // subscriptions cleanly. Capture once in a final field per the
        // "Capture swappable singleton reference once" memory.
        this.eventBus = eventBus;

        this.selectionListener = (obs, oldSel, newSel) -> onSelectionChanged(newSel);
        this.selectionModel.selectionProperty().addListener(selectionListener);

        if (this.eventBus != null) {
            this.pluginUnloadedSubscription = this.eventBus.on(
                    PluginEvent.Unloaded.class,
                    DispatchMode.ON_UI_THREAD,
                    this::onPluginUnloaded);
            this.channelRemovedSubscription = this.eventBus.on(
                    MixerEvent.ChannelRemoved.class,
                    DispatchMode.ON_UI_THREAD,
                    this::onChannelRemoved);
            this.clipRemovedSubscription = this.eventBus.on(
                    ClipEvent.Removed.class,
                    DispatchMode.ON_UI_THREAD,
                    this::onClipRemoved);
        }

        // Apply the initial selection — covers the case where the
        // controller is built after a selection already exists.
        onSelectionChanged(this.selectionModel.getSelection());
    }

    private void onPluginUnloaded(PluginEvent.Unloaded ev) {
        UUID instanceId = ev.pluginInstanceId();
        UUID affectedTrackId = null;
        for (var entry : pluginPanelCache.entrySet()) {
            if (instanceId.equals(entry.getValue().slot.getPluginInstanceId())) {
                affectedTrackId = entry.getKey().trackId();
                break;
            }
        }
        if (affectedTrackId != null) {
            UUID trackId = affectedTrackId;
            pluginPanelCache.entrySet().removeIf(
                    e -> trackId.equals(e.getKey().trackId()));
        } else {
            // Cannot derive the affected track — conservatively evict all
            // plugin-panel cache entries so shifted insertIndex keys don't
            // leave stale panels.
            pluginPanelCache.clear();
        }
    }

    private void onChannelRemoved(MixerEvent.ChannelRemoved ev) {
        UUID trackId = ev.channelId();
        pluginPanelCache.entrySet().removeIf(
                e -> trackId.equals(e.getKey().trackId()));
    }

    private void onClipRemoved(ClipEvent.Removed ev) {
        Node removed = clipEditorCache.remove(ev.clipId().toString());
        if (removed != null) {
            ClipEditorFactory.disposeEditor(removed);
        }
    }

    /**
     * Removes the selection listener and closes all event-bus
     * subscriptions — call when the controller's host is torn down
     * (e.g. on project close) to avoid pinning the selection model
     * or leaking subscriptions across re-opens of the view.
     */
    public void dispose() {
        selectionModel.selectionProperty().removeListener(selectionListener);
        if (pluginUnloadedSubscription != null) {
            pluginUnloadedSubscription.close();
            pluginUnloadedSubscription = null;
        }
        if (channelRemovedSubscription != null) {
            channelRemovedSubscription.close();
            channelRemovedSubscription = null;
        }
        if (clipRemovedSubscription != null) {
            clipRemovedSubscription.close();
            clipRemovedSubscription = null;
        }
        resetForNewProject();
    }

    /**
     * Disposes cached clip editors (releasing off-heap GPU/waveform
     * resources) and clears all project-scoped caches so that stale UI
     * nodes from a prior {@link DawProject} are not retained across a
     * project swap. The selection listener remains installed — only the
     * cached state is reset.
     *
     * <p>Called from {@link
     * com.benesquivelmusic.daw.app.ui.ViewNavigationController#onProjectChanged()}
     * whenever {@code MainController} loads/creates a new project.</p>
     */
    public void resetForNewProject() {
        clipEditorCache.values().forEach(ClipEditorFactory::disposeEditor);
        midiClipEditorCache.values().forEach(ClipEditorFactory::disposeEditor);
        pluginPanelCache.clear();
        clipEditorCache.clear();
        midiClipEditorCache.clear();
        lastFocusedInsertByTrack.clear();
        pendingSelection = null;
        lastApplied = null;
    }

    /**
     * Re-applies the last selection that arrived while Workshop was
     * inactive. The navigation controller calls this after switching to
     * Workshop so any selection the user made elsewhere (Arrangement,
     * Mixer, etc.) takes effect on entry.
     */
    public void applyPendingOnWorkshopActivation() {
        InspectorSelection target;
        if (pendingSelection != null) {
            target = pendingSelection;
            pendingSelection = null;
        } else {
            // No pending — but the current selection might have been
            // applied while Workshop was inactive and skipped. Re-apply
            // the current value so the right pane stays in sync.
            target = selectionModel.getSelection();
        }
        // Skip re-applying an unchanged selection on every enter/exit
        // cycle (review S2). Equality is by record value so e.g. two
        // InsertSelection(trackId, idx) with the same fields hit the
        // memo and avoid a full panel rebuild.
        if (Objects.equals(target, lastApplied)) {
            return;
        }
        lastApplied = target;
        applySelection(target);
    }

    /**
     * Resets the {@code lastApplied} memo so the next call to
     * {@link #applyPendingOnWorkshopActivation()} unconditionally
     * re-applies the current selection. Called by the navigation
     * controller when leaving Workshop so that re-entering Workshop
     * restores the clip-detail and plugin slots even when the underlying
     * selection has not changed.
     */
    public void resetLastApplied() {
        lastApplied = null;
    }

    // ── Visible for tests ─────────────────────────────────────────────────

    /**
     * @return the cache size — test seam to verify identity-caching
     *         contract (a second selection of the same insert must not
     *         grow the cache)
     */
    int pluginPanelCacheSize() {
        return pluginPanelCache.size();
    }

    /**
     * @return the clip-editor cache size — test seam
     */
    int clipEditorCacheSize() {
        return clipEditorCache.size() + midiClipEditorCache.size();
    }

    /**
     * Story 283 test seam — seeds the plugin-panel cache without
     * driving the JavaFX-bound {@code applyInsertSelection} path, so
     * the S3 cache-invalidation subscriber can be verified in a
     * headless test.
     */
    void seedPluginPanelCacheForTest(UUID trackId, int insertIndex,
                                      InsertSlot slot, Node panel) {
        pluginPanelCache.put(new InsertCacheKey(trackId, insertIndex),
                new CachedPanel(slot, panel));
    }

    /**
     * Story 283 test seam — seeds the clip-editor cache without
     * driving the JavaFX-bound {@code applyClipSelection} path.
     */
    void seedClipEditorCacheForTest(String clipId, Node editor) {
        clipEditorCache.put(clipId, editor);
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private void onSelectionChanged(InspectorSelection newSel) {
        if (newSel == null) {
            return;
        }
        if (!workshopActiveSupplier.getAsBoolean()) {
            // Workshop is not active — defer until activation. We only
            // remember the LATEST selection; if the user clicks through
            // five inserts before opening Workshop, only the fifth needs
            // to materialise.
            pendingSelection = newSel;
            return;
        }
        applySelection(newSel);
    }

    private void applySelection(InspectorSelection s) {
        if (s == null) {
            clearFocusedPlugin();
            workshopView.setClipDetailContent(null);
            return;
        }
        switch (s) {
            case InspectorSelection.InsertSelection ins -> {
                applyInsertSelection(ins);
                // InsertSelection is a non-clip selection — clear any stale
                // clip-detail content (the class Javadoc states non-clip
                // selections clear the clip-detail slot).
                workshopView.setClipDetailContent(null);
            }
            case InspectorSelection.ClipSelection clip -> {
                // Clip selection updates the clip-detail slot only;
                // the focused-plugin pane retains the last focused
                // plugin so it remains visible side-by-side with the
                // clip detail below.
                applyClipSelection(clip);
            }
            case InspectorSelection.TrackSelection ts -> {
                // Per PR description: "When the active selection is a
                // track or insert, the Workshop right pane shows that
                // track's currently-active plugin (the one most-recently
                // opened)." Resolve the last-focused insert for this
                // track and show its plugin; clear if none exists.
                UUID trackId = ts.trackId();
                Integer lastInsert = lastFocusedInsertByTrack.get(trackId);
                if (lastInsert != null) {
                    applyInsertSelection(
                            new InspectorSelection.InsertSelection(trackId, lastInsert));
                } else {
                    clearFocusedPlugin();
                }
                // Clear only the clip-detail slot — only ClipSelection
                // populates it.
                workshopView.setClipDetailContent(null);
            }
            case InspectorSelection.SendSelection _ -> {
                clearFocusedPlugin();
                workshopView.setClipDetailContent(null);
            }
            case InspectorSelection.BusSelection _ -> {
                clearFocusedPlugin();
                workshopView.setClipDetailContent(null);
            }
            case InspectorSelection.Empty _ -> {
                clearFocusedPlugin();
                workshopView.setClipDetailContent(null);
            }
        }
    }

    private void applyInsertSelection(InspectorSelection.InsertSelection ins) {
        DawProject project = projectSupplier.get();
        if (project == null) {
            clearFocusedPlugin();
            return;
        }
        UUID trackId = ins.trackId();
        int insertIndex = ins.insertIndex();
        Optional<InsertSlot> slotOpt = ProjectLookups.findInsertSlot(project, trackId, insertIndex);
        if (slotOpt.isEmpty()) {
            clearFocusedPlugin();
            return;
        }
        InsertSlot slot = slotOpt.get();
        InsertCacheKey key = new InsertCacheKey(trackId, insertIndex);
        CachedPanel cached = pluginPanelCache.get(key);
        Node panel;
        if (cached != null && cached.slot == slot) {
            // Cache hit — same slot instance, reuse existing panel.
            panel = cached.node;
        } else {
            // Cache miss or stale (insert chain edited) — rebuild.
            panel = buildPluginPanel(slot);
            if (panel == null) {
                // No parameters → no panel; show placeholder.
                pluginPanelCache.remove(key);
                clearFocusedPlugin();
                return;
            }
            pluginPanelCache.put(key, new CachedPanel(slot, panel));
        }
        // Record the last-focused insert for this track so TrackSelection
        // can restore it.
        lastFocusedInsertByTrack.put(trackId, insertIndex);
        int trackIdx = ProjectLookups.findTrackIndex(project, trackId);
        int displayIndex = trackIdx < 0 ? 1 : (trackIdx + 1);
        // §4 Concept F breadcrumb — "Track 03 ▸ Insert 2 ▸ Reverb". Push
        // the fully-composed segments so the real insert number (not the
        // view's default "Insert 1" placeholder) appears.
        List<String> segments = WorkshopView.buildSegments(
                messages, displayIndex, insertIndex + 1, slot.getName());
        workshopView.setFocusedPlugin(segments, panel);
    }

    private void applyClipSelection(InspectorSelection.ClipSelection clipSel) {
        DawProject project = projectSupplier.get();
        if (project == null) {
            workshopView.setClipDetailContent(null);
            return;
        }
        UUID clipId = clipSel.clipId();
        Optional<AudioClip> clipOpt = ProjectLookups.findAudioClip(project, clipId);
        if (clipOpt.isEmpty()) {
            workshopView.setClipDetailContent(null);
            return;
        }
        AudioClip clip = clipOpt.get();
        Node editor = clipEditorCache.get(clip.getId());
        if (editor == null) {
            Track owningTrack = ProjectLookups.findOwningTrack(project, clip).orElse(null);
            editor = ClipEditorFactory.buildEditor(clip, owningTrack);
            clipEditorCache.put(clip.getId(), editor);
        }
        workshopView.setClipDetailContent(editor);
    }

    /**
     * Sets the workshop's clip detail content directly from a
     * {@link Clip} — public seam for tests that pass MIDI clips (which
     * lack a UUID and so can't be addressed via the standard
     * {@link InspectorSelection.ClipSelection} path).
     *
     * @param clip        the clip to render, or {@code null} to clear
     * @param owningTrack the owning track (optional)
     */
    public void setClipDetailFromClip(Clip clip, Track owningTrack) {
        if (clip == null) {
            workshopView.setClipDetailContent(null);
            return;
        }
        Node editor;
        if (clip instanceof AudioClip ac) {
            // AudioClip has a stable UUID id for caching.
            editor = clipEditorCache.get(ac.getId());
            if (editor == null) {
                editor = ClipEditorFactory.buildEditor(clip, owningTrack);
                clipEditorCache.put(ac.getId(), editor);
            }
        } else {
            // MIDI clips use object identity to avoid identityHashCode
            // collisions between distinct clip instances.
            editor = midiClipEditorCache.get(clip);
            if (editor == null) {
                editor = ClipEditorFactory.buildEditor(clip, owningTrack);
                midiClipEditorCache.put(clip, editor);
            }
        }
        workshopView.setClipDetailContent(editor);
    }

    /**
     * Builds a plugin-parameter panel for the given insert slot, or
     * {@code null} when the slot's plugin has no editable parameters
     * (the editor would be empty — show the placeholder instead).
     *
     * <p>Mirrors {@code InsertEffectRack.openParameterEditor} so the
     * panel is wired the same way the floating-Stage path wires it.</p>
     */
    private Node buildPluginPanel(InsertSlot slot) {
        InsertEffectType type = slot.getEffectType();
        if (type == null) {
            return null;
        }
        List<PluginParameter> params = InsertEffectFactory.getParameterDescriptors(type);
        if (params.isEmpty()) {
            return null;
        }
        try {
            com.benesquivelmusic.daw.app.ui.PluginParameterEditorPanel panel =
                    new com.benesquivelmusic.daw.app.ui.PluginParameterEditorPanel(params);
            panel.setOnParameterChanged(
                    InsertEffectFactory.createPublishingParameterHandler(slot, type));
            Map<Integer, Double> currentValues =
                    InsertEffectFactory.getParameterValues(type, slot.getProcessor());
            if (!currentValues.isEmpty()) {
                panel.getState().loadValues(currentValues);
                panel.refreshControls();
            }
            return panel;
        } catch (RuntimeException e) {
            // Defensive — never let a misbehaving plugin descriptor crash
            // the Workshop wiring. Log and show the placeholder.
            LOG.log(Level.WARNING, "Failed to build plugin panel for insert '"
                    + slot.getName() + "'", e);
            return null;
        }
    }

    private void clearFocusedPlugin() {
        // Preserves the PluginViewContainer's identity (the
        // WorkshopPluginSwitchTest contract) — only swaps its inner content
        // back to the empty placeholder and clears the breadcrumb.
        workshopView.clearFocusedPlugin();
    }

    /**
     * Cache key for the per-insert plugin-panel cache. Equality is by
     * value — selecting the same {@code (trackId, insertIndex)} twice
     * hits the same cache entry.
     */
    private record InsertCacheKey(UUID trackId, int insertIndex) {}

    /**
     * A cached plugin panel paired with the {@link InsertSlot} it was
     * built from. On cache hit the stored slot identity is compared to
     * the currently-resolved slot — if they differ (insert chain was
     * reordered / edited), the entry is considered stale and rebuilt.
     */
    private record CachedPanel(InsertSlot slot, Node node) {}
}
