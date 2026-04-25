package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;
import java.util.UUID;

/**
 * An undoable action that changes the {@link VcaGroup#masterGainDb() master
 * gain} of a registered {@link VcaGroup}.
 *
 * <p>The previous gain is captured at {@link #execute()} time so that
 * {@link #undo()} restores the exact value the user moved the fader away
 * from — even if the gain has been changed by other code paths between
 * execute and undo.</p>
 */
public final class SetVcaGainAction implements UndoableAction {

    private final VcaGroupManager manager;
    private final UUID groupId;
    private final double newGainDb;
    private double previousGainDb;
    private boolean executed;

    public SetVcaGainAction(VcaGroupManager manager, UUID groupId, double newGainDb) {
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
        this.groupId = Objects.requireNonNull(groupId, "groupId must not be null");
        this.newGainDb = newGainDb;
    }

    @Override
    public String description() {
        return "Set VCA Gain";
    }

    @Override
    public void execute() {
        VcaGroup group = manager.getById(groupId);
        if (group == null) {
            throw new IllegalStateException("vca group not registered: " + groupId);
        }
        if (!executed) {
            previousGainDb = group.masterGainDb();
            executed = true;
        }
        manager.setMasterGainDb(groupId, newGainDb);
    }

    @Override
    public void undo() {
        if (!executed) {
            return;
        }
        if (manager.getById(groupId) != null) {
            manager.setMasterGainDb(groupId, previousGainDb);
        }
    }
}
