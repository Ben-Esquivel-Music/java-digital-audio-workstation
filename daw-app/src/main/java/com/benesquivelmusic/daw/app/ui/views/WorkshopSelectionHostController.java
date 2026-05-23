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
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;

import javafx.beans.value.ChangeListener;
import javafx.scene.Node;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
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
 *   <li>Any other selection (track, send, bus, empty) → clears the
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

    /**
     * Strong-reference listener field — lives exactly as long as this
     * controller so the selection model never silently orphans the wiring.
     */
    private final ChangeListener<InspectorSelection> selectionListener;

    /**
     * Cache of built plugin-parameter panels keyed by
     * {@code (trackId, insertIndex)}. Selecting the same insert twice
     * returns the same Node. Insert mutations (add/remove/move on the
     * channel) invalidate entries lazily — a stale entry simply produces
     * an old panel; the resolver would have returned {@link Optional#empty()}
     * for an out-of-range index anyway and skipped the cache lookup.
     */
    private final Map<InsertCacheKey, Node> pluginPanelCache = new HashMap<>();

    /**
     * Cache of built clip-editor Nodes keyed by clip identity. Clip ids
     * are stable UUID strings ({@link AudioClip#getId()}); MIDI clips
     * lack an id and would key by identity in a future selection path.
     */
    private final Map<String, Node> clipEditorCache = new HashMap<>();

    /**
     * The most-recent selection that arrived while Workshop was inactive,
     * applied on the next activation. {@code null} when there is no
     * pending work.
     */
    private InspectorSelection pendingSelection;

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
     */
    public WorkshopSelectionHostController(WorkshopView workshopView,
                                            InspectorSelectionModel selectionModel,
                                            Supplier<DawProject> projectSupplier,
                                            BooleanSupplier workshopActiveSupplier) {
        this.workshopView = Objects.requireNonNull(workshopView,
                "workshopView must not be null");
        this.selectionModel = Objects.requireNonNull(selectionModel,
                "selectionModel must not be null");
        this.projectSupplier = Objects.requireNonNull(projectSupplier,
                "projectSupplier must not be null");
        this.workshopActiveSupplier = Objects.requireNonNull(workshopActiveSupplier,
                "workshopActiveSupplier must not be null");

        this.selectionListener = (obs, oldSel, newSel) -> onSelectionChanged(newSel);
        this.selectionModel.selectionProperty().addListener(selectionListener);

        // Apply the initial selection — covers the case where the
        // controller is built after a selection already exists.
        onSelectionChanged(this.selectionModel.getSelection());
    }

    /**
     * Removes the selection listener — call when the controller's host
     * is torn down (e.g. on project close) to avoid pinning the
     * selection model.
     */
    public void dispose() {
        selectionModel.selectionProperty().removeListener(selectionListener);
        pluginPanelCache.clear();
        clipEditorCache.clear();
        pendingSelection = null;
    }

    /**
     * Re-applies the last selection that arrived while Workshop was
     * inactive. The navigation controller calls this after switching to
     * Workshop so any selection the user made elsewhere (Arrangement,
     * Mixer, etc.) takes effect on entry.
     */
    public void applyPendingOnWorkshopActivation() {
        if (pendingSelection != null) {
            InspectorSelection s = pendingSelection;
            pendingSelection = null;
            applySelection(s);
        } else {
            // No pending — but the current selection might have been
            // applied while Workshop was inactive and skipped. Re-apply
            // the current value so the right pane stays in sync.
            applySelection(selectionModel.getSelection());
        }
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
        return clipEditorCache.size();
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
            case InspectorSelection.InsertSelection ins -> applyInsertSelection(ins);
            case InspectorSelection.ClipSelection clip -> {
                // Clip selection is purely a clip-detail concern — but
                // per the host-controller brief, ANY non-InsertSelection
                // also clears the focused-plugin slot.
                clearFocusedPlugin();
                applyClipSelection(clip);
            }
            case InspectorSelection.TrackSelection ignored -> {
                clearFocusedPlugin();
                // Track / send / bus selections clear the clip-detail
                // slot too — only ClipSelection populates it.
                workshopView.setClipDetailContent(null);
            }
            case InspectorSelection.SendSelection ignored -> {
                clearFocusedPlugin();
                workshopView.setClipDetailContent(null);
            }
            case InspectorSelection.BusSelection ignored -> {
                clearFocusedPlugin();
                workshopView.setClipDetailContent(null);
            }
            case InspectorSelection.Empty ignored -> {
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
        Node panel = pluginPanelCache.get(key);
        if (panel == null) {
            panel = buildPluginPanel(slot);
            if (panel == null) {
                // No parameters → no panel; show placeholder.
                clearFocusedPlugin();
                return;
            }
            pluginPanelCache.put(key, panel);
        }
        int trackIdx = ProjectLookups.findTrackIndex(project, trackId);
        int displayIndex = trackIdx < 0 ? 1 : (trackIdx + 1);
        // §4 Concept F breadcrumb — "Track 03 ▸ Insert 2 ▸ Reverb". Push
        // the fully-composed segments so the real insert number (not the
        // view's default "Insert 1" placeholder) appears.
        List<String> segments = WorkshopView.buildSegments(
                displayIndex, insertIndex + 1, slot.getName());
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
        // Cache key — AudioClip has a stable id; MIDI clip is keyed by
        // identity-hash (single-instance per track).
        String cacheKey = clip instanceof AudioClip ac
                ? ac.getId()
                : "midi:" + System.identityHashCode(clip);
        Node editor = clipEditorCache.get(cacheKey);
        if (editor == null) {
            editor = ClipEditorFactory.buildEditor(clip, owningTrack);
            clipEditorCache.put(cacheKey, editor);
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
                    InsertEffectFactory.createParameterHandler(type, slot.getProcessor()));
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
            LOG.warning(() -> "Failed to build plugin panel for insert '"
                    + slot.getName() + "': " + e);
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
}
