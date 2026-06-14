package com.benesquivelmusic.daw.app.ui.vm.command;

import com.benesquivelmusic.daw.app.ui.EditTool;
import com.benesquivelmusic.daw.app.ui.vm.SelectionVM;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.event.UiEvent;

import java.time.Instant;
import java.util.Objects;

/**
 * The production {@link SelectionIntentHandler}: it owns the {@link SelectionVM}
 * write and the {@code SelectionChanged} announce, running the universal cascade
 * for each selection intent (Control Synchronization Design Book §5.1, §5.5;
 * story 292).
 *
 * <p>For every intent the handler runs, in order:</p>
 * <ol>
 *   <li><strong>VALIDATE</strong> — an idempotent gate: if the selection already
 *       matches the request, neither mutate nor announce.</li>
 *   <li><strong>MUTATE</strong> — write the {@link SelectionVM} (the
 *       UI-authoritative selection model — there is no {@code daw-core} model for
 *       selection, §3.3).</li>
 *   <li><strong>ANNOUNCE</strong> — publish {@link UiEvent.SelectionChanged} on
 *       the bus via {@link EventBusPublisher} so unrelated surfaces (inspector,
 *       menu-enablement policy, canvas highlight, mixer focus) re-read the VM.</li>
 * </ol>
 *
 * <p>{@link #setTool(EditTool)} is the exception: the edit tool is a continuous UI
 * mode bound directly by the toolbar / cursor / canvas (§2.7), so it mutates the
 * VM but announces no discrete event (a distinct {@code ToolChanged} event is
 * deferred — §5.5; this story scopes {@code UiEvent} to {@code SelectionChanged}
 * and {@code UndoStateChanged}).</p>
 *
 * <p>Mirrors stories 290/291: the VM is a pure projection of UI state and this
 * handler is the single writer + announcer the commands target. All methods run
 * on the FX thread (selection gestures originate there).</p>
 */
public final class CoreSelectionIntentHandler implements SelectionIntentHandler {

    private final SelectionVM selectionVM;

    /**
     * Creates a handler bound to {@code selectionVM}.
     *
     * @param selectionVM the UI-authoritative selection model to write; must not be {@code null}
     * @throws NullPointerException if {@code selectionVM} is {@code null}
     */
    public CoreSelectionIntentHandler(SelectionVM selectionVM) {
        this.selectionVM = Objects.requireNonNull(selectionVM, "selectionVM must not be null");
    }

    @Override
    public void selectTrack(Track track) {
        Objects.requireNonNull(track, "track must not be null");
        if (Objects.equals(selectionVM.getSelectedTrack(), track)) {
            return; // VALIDATE: already selected — no-op
        }
        selectionVM.selectTrack(track);
        announceSelectionChanged();
    }

    @Override
    public void selectClip(Object clip) {
        Objects.requireNonNull(clip, "clip must not be null");
        if (Objects.equals(selectionVM.getSelectedClip(), clip)) {
            return;
        }
        selectionVM.selectClip(clip);
        announceSelectionChanged();
    }

    @Override
    public void selectDevice(Object device) {
        Objects.requireNonNull(device, "device must not be null");
        if (Objects.equals(selectionVM.getSelectedDevice(), device)) {
            return;
        }
        selectionVM.selectDevice(device);
        announceSelectionChanged();
    }

    @Override
    public void setTool(EditTool tool) {
        Objects.requireNonNull(tool, "tool must not be null");
        if (selectionVM.getTool() == tool) {
            return; // VALIDATE: already active — no-op
        }
        // MUTATE only — the tool is bound directly (§2.7), so no SelectionChanged.
        selectionVM.setTool(tool);
    }

    @Override
    public void clear() {
        if (selectionVM.getSelectedTrack() == null
                && selectionVM.getSelectedClip() == null
                && selectionVM.getSelectedDevice() == null) {
            return; // VALIDATE: already empty — no-op
        }
        selectionVM.clear();
        announceSelectionChanged();
    }

    private void announceSelectionChanged() {
        EventBusPublisher.publish(new UiEvent.SelectionChanged(Instant.now()));
    }
}
