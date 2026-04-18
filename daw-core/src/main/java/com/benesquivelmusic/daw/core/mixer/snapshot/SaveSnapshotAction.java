package com.benesquivelmusic.daw.core.mixer.snapshot;

import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that captures the current mixer state as a named
 * {@link MixerSnapshot} and stores it in a {@link MixerSnapshotManager}.
 *
 * <p>Executing this action captures a fresh snapshot from the mixer and
 * appends it to the manager. Undoing removes the appended snapshot. Redo
 * re-captures the mixer state at redo time so snapshots always reflect the
 * mixer state at the moment the user saved them.</p>
 */
public final class SaveSnapshotAction implements UndoableAction {

    private final MixerSnapshotManager manager;
    private final Mixer mixer;
    private final String snapshotName;
    private MixerSnapshot saved;

    /**
     * Creates a new save-snapshot action.
     *
     * @param manager      the manager to append the new snapshot to
     * @param mixer        the mixer to capture
     * @param snapshotName the user-provided snapshot name
     */
    public SaveSnapshotAction(MixerSnapshotManager manager, Mixer mixer, String snapshotName) {
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
        this.mixer = Objects.requireNonNull(mixer, "mixer must not be null");
        this.snapshotName = Objects.requireNonNull(snapshotName, "snapshotName must not be null");
    }

    @Override
    public String description() {
        return "Save Mixer Snapshot";
    }

    @Override
    public void execute() {
        saved = MixerSnapshot.capture(mixer, snapshotName);
        manager.addSnapshot(saved);
    }

    @Override
    public void undo() {
        if (saved != null) {
            manager.removeSnapshot(saved);
        }
    }

    /** Returns the snapshot that was appended by {@link #execute()}, or {@code null} before execution. */
    public MixerSnapshot getSavedSnapshot() {
        return saved;
    }
}
