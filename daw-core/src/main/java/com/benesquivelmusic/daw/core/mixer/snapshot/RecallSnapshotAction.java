package com.benesquivelmusic.daw.core.mixer.snapshot;

import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that recalls a previously captured {@link MixerSnapshot}
 * onto a {@link Mixer}, restoring every channel's volume, pan, mute, solo,
 * phase, output routing, insert bypass/parameter state, and send settings
 * as a <strong>single compound step</strong>.
 *
 * <p>Execution captures the current mixer state (for undo) then applies the
 * target snapshot. Undo re-applies the captured pre-state, reversing every
 * channel, insert, and send change in a single atomic step.</p>
 */
public final class RecallSnapshotAction implements UndoableAction {

    private final Mixer mixer;
    private final MixerSnapshot target;
    private MixerSnapshot previousState;

    /**
     * Creates a new recall-snapshot action.
     *
     * @param mixer  the mixer to apply the snapshot to
     * @param target the snapshot to recall
     */
    public RecallSnapshotAction(Mixer mixer, MixerSnapshot target) {
        this.mixer = Objects.requireNonNull(mixer, "mixer must not be null");
        this.target = Objects.requireNonNull(target, "target must not be null");
    }

    @Override
    public String description() {
        return "Recall Mixer Snapshot";
    }

    @Override
    public void execute() {
        // Capture current state for undo and apply the target.
        previousState = MixerSnapshot.capture(mixer, "__before_recall__");
        target.applyTo(mixer);
    }

    @Override
    public void undo() {
        if (previousState != null) {
            previousState.applyTo(mixer);
        }
    }
}
