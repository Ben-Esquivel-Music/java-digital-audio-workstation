package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.EditTool;
import com.benesquivelmusic.daw.core.track.Track;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;

import java.util.Objects;

/**
 * The observable view-model of the UI <strong>selection</strong> — Stage 4 of
 * the §8 migration path (Control Synchronization Design Book §3.3, §5.5;
 * story 292). {@code SelectionVM} drives the inspector target, the canvas
 * highlight, the mixer focus, and the menu-enablement policy; controls raise
 * {@code SelectionCommand}s that flow through {@code CoreSelectionIntentHandler},
 * which mutates this VM and announces {@code UiEvent.SelectionChanged}.
 *
 * <h2>UI-authoritative — no core mirror, no dispatcher</h2>
 *
 * <p>Unlike {@code ProjectVM}/{@code TrackVM}/{@code TransportVM} (which mirror a
 * {@code daw-core} model through a change signal), the selection is owned by the
 * UI (§3.3): there is no core to observe, so {@code SelectionVM} <em>is</em> the
 * authoritative model. It is mutated only by user gestures on the FX thread (a
 * click, a menu item, a keyboard shortcut — all converging on a
 * {@code SelectionCommand}, §2.8), so its writer methods set the properties
 * synchronously on the calling FX thread and it needs no {@link
 * com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher FxDispatcher}. It remains
 * the <strong>single writer</strong> of its properties (§2.4): the wrappers are
 * private and only the {@code ReadOnly*Property} views are exposed.</p>
 *
 * <h2>Lifetime — restored, not rebuilt</h2>
 *
 * <p>Because the selection is UI state, the full-load cascade (§5.7) does
 * <em>not</em> dispose and rebuild {@code SelectionVM} the way it does the
 * model-derived VMs; instead it <strong>restores and clamps</strong> the
 * selection to the new project's bounds in step 5 ({@link #clampTo(ProjectVM)}).
 * A selected track that no longer exists in the loaded project is cleared so no
 * surface binds a stale target.</p>
 */
public final class SelectionVM {

    private final ReadOnlyObjectWrapper<Track> selectedTrack =
            new ReadOnlyObjectWrapper<>(this, "selectedTrack");
    private final ReadOnlyObjectWrapper<Object> selectedClip =
            new ReadOnlyObjectWrapper<>(this, "selectedClip");
    private final ReadOnlyObjectWrapper<Object> selectedDevice =
            new ReadOnlyObjectWrapper<>(this, "selectedDevice");
    private final ReadOnlyObjectWrapper<EditTool> tool =
            new ReadOnlyObjectWrapper<>(this, "tool", EditTool.POINTER);

    /** Creates an empty selection with the default {@link EditTool#POINTER} tool. */
    public SelectionVM() {
        // No upstream listener — selection is UI-authoritative.
    }

    // ── Writer methods (single-writer command-path entry; FX-thread) ──────────
    //
    // Views never call these — they bind the read-only properties and emit a
    // SelectionCommand. The command handler (CoreSelectionIntentHandler) is the
    // sole caller, after its VALIDATE gate, and it announces SelectionChanged.

    /**
     * Selects {@code track} as the focused track. Single-writer entry on the FX
     * thread; do not call from a view (bind {@link #selectedTrackProperty()} and
     * emit a {@code SelectTrackCommand} instead).
     *
     * @param track the track to select; must not be {@code null} (use {@link #clear()} to deselect)
     * @throws NullPointerException if {@code track} is {@code null}
     */
    public void selectTrack(Track track) {
        selectedTrack.set(Objects.requireNonNull(track, "track must not be null"));
    }

    /**
     * Selects {@code clip} as the focused clip.
     *
     * @param clip the clip to select; must not be {@code null}
     * @throws NullPointerException if {@code clip} is {@code null}
     */
    public void selectClip(Object clip) {
        selectedClip.set(Objects.requireNonNull(clip, "clip must not be null"));
    }

    /**
     * Selects {@code device} (an insert / plugin) as the focused device.
     *
     * @param device the device to select; must not be {@code null}
     * @throws NullPointerException if {@code device} is {@code null}
     */
    public void selectDevice(Object device) {
        selectedDevice.set(Objects.requireNonNull(device, "device must not be null"));
    }

    /**
     * Sets the active edit tool. Unlike the selected entities, the tool is a
     * continuous UI mode the toolbar / cursor / canvas <em>bind</em> directly
     * (§2.7), so a tool change is not announced as a discrete
     * {@code SelectionChanged} bus event (a distinct {@code ToolChanged} event is
     * deferred — §5.5; this story scopes {@code UiEvent} to {@code SelectionChanged}
     * and {@code UndoStateChanged}).
     *
     * @param tool the new edit tool; must not be {@code null}
     * @throws NullPointerException if {@code tool} is {@code null}
     */
    public void setTool(EditTool tool) {
        this.tool.set(Objects.requireNonNull(tool, "tool must not be null"));
    }

    /** Clears the selected track, clip, and device (the edit tool is unchanged). */
    public void clear() {
        selectedTrack.set(null);
        selectedClip.set(null);
        selectedDevice.set(null);
    }

    /**
     * Restores-and-clamps the selection to {@code project}'s bounds — step 5 of
     * the full-load cascade (§5.7). A selected track that is not part of the
     * loaded project is cleared, so no surface binds a target that no longer
     * exists.
     *
     * <p>Only the track selection is clamped here: the clip / device selections
     * are intentionally typed {@code Object} (the clip layer — {@code AudioClip} /
     * {@code MidiClip} — has no shared supertype, and a "device" is an insert /
     * plugin), and clamping them needs the clip-editor / plugin surfaces that
     * migrate in story 294, so it is deferred with them.</p>
     *
     * @param project the newly-loaded project whose tracks bound the selection;
     *                must not be {@code null}
     * @throws NullPointerException if {@code project} is {@code null}
     */
    public void clampTo(ProjectVM project) {
        Objects.requireNonNull(project, "project must not be null");
        Track current = selectedTrack.get();
        if (current != null && !project.getTracks().contains(current)) {
            selectedTrack.set(null);
        }
    }

    // ── Read-only property views (the VM is the sole writer) ──────────────────

    /** The focused track, or {@code null}. Read-only; bound by the inspector, canvas highlight, mixer focus. */
    public ReadOnlyObjectProperty<Track> selectedTrackProperty() {
        return selectedTrack.getReadOnlyProperty();
    }

    /** Returns the focused track, or {@code null}. */
    public Track getSelectedTrack() {
        return selectedTrack.get();
    }

    /** The focused clip, or {@code null}. Read-only; bound by the inspector and clip editor. */
    public ReadOnlyObjectProperty<Object> selectedClipProperty() {
        return selectedClip.getReadOnlyProperty();
    }

    /** Returns the focused clip, or {@code null}. */
    public Object getSelectedClip() {
        return selectedClip.get();
    }

    /** The focused device (insert / plugin), or {@code null}. Read-only; bound by the inspector and plugin chain. */
    public ReadOnlyObjectProperty<Object> selectedDeviceProperty() {
        return selectedDevice.getReadOnlyProperty();
    }

    /** Returns the focused device, or {@code null}. */
    public Object getSelectedDevice() {
        return selectedDevice.get();
    }

    /** The active edit tool (never {@code null}). Read-only; bound by the toolbar, cursor, canvas mode. */
    public ReadOnlyObjectProperty<EditTool> toolProperty() {
        return tool.getReadOnlyProperty();
    }

    /** Returns the active edit tool. */
    public EditTool getTool() {
        return tool.get();
    }

    /**
     * No-op disposer kept for lifecycle symmetry with the model-derived VMs that
     * the cascade/host disposes uniformly: {@code SelectionVM} registers no
     * upstream listener (it is UI-authoritative, §3.3), so there is nothing to
     * release.
     */
    public void dispose() {
        // Nothing to release — see Javadoc.
    }
}
