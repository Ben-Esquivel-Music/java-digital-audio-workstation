package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.undo.UndoHistoryChange;
import com.benesquivelmusic.daw.core.undo.UndoHistoryListener;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import java.util.Objects;

/**
 * The one general dirty path through the undo history (PR #984 review):
 * every change that applies or reverses an action on the project — an
 * execute, undo, redo or history jump ({@link UndoHistoryChange#mutatesProject()})
 * — marks the project dirty, whichever surface ran it: the arrangement
 * canvas, the mixer, the insert rack, the history panel, the menu. MIDI
 * editor edits are not yet undo-tracked, dirty-marking or persisted (stories
 * 330 and 355). A clear of either stack does not: it discards history without
 * touching the project (today only {@code TrackFreezeController} clears the
 * redo stack, after rolling a cancelled freeze back; nothing calls
 * {@link UndoManager#clear()} in production). Open, New, import, recovery
 * and snapshot restore leave the old history alone and install a fresh,
 * empty manager instead, and Save does not touch the history at all.
 *
 * <p>This is phase 3 of the universal cascade (Control Synchronization Design
 * Book §5.1, "MUTATE [+UndoManager] → PROJECT dirty"), done once for every
 * undoable intent instead of by each caller, and undo replays it like any
 * other cascade (§5.6). {@link DawProject#markDirty()} is the one dirty bit
 * (§7); it is idempotent and thread-safe, so a history change off the FX
 * thread needs no marshalling here — {@code ProjectVM} carries the flip to
 * the FX thread.</p>
 *
 * <h2>Per-generation lifetime</h2>
 *
 * <p>Every generation — startup, New, Open, import, recovery, archive open,
 * snapshot restore — installs a fresh {@link UndoManager} and its
 * {@link DawProject} together. A binder is bound to the one manager and
 * the one project of a generation, both captured at construction, and is
 * disposed and rebuilt with the view-model layer
 * ({@code MainController.rebuildViewModels()}), so exactly one is attached
 * per manager and an abandoned manager never marks the new project
 * (javafx-application-design §15: every registration has its removal).</p>
 */
public final class UndoHistoryDirtyBinder {

    private final UndoManager undoManager;
    private final UndoHistoryListener listener;

    /**
     * Attaches to {@code undoManager}, marking {@code project} dirty on every
     * history change that mutates it.
     *
     * @param undoManager the generation's undo manager
     * @param project     the generation's project — the one the manager's actions edit
     */
    public UndoHistoryDirtyBinder(UndoManager undoManager, DawProject project) {
        this.undoManager = Objects.requireNonNull(undoManager, "undoManager must not be null");
        Objects.requireNonNull(project, "project must not be null");
        this.listener = (_, change) -> {
            if (change.mutatesProject()) {
                project.markDirty();
            }
        };
        undoManager.addHistoryListener(listener);
    }

    /** Detaches from the undo manager; idempotent. */
    public void dispose() {
        undoManager.removeHistoryListener(listener);
    }
}
