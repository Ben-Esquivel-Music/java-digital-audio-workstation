package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;
import java.util.UUID;

/**
 * An undoable action that removes a {@link CueBus} from a {@link CueBusManager}.
 *
 * <p>The full bus (including all cue sends) is captured at execution time so
 * undo restores it identically.</p>
 */
public final class DeleteCueBusAction implements UndoableAction {

    private final CueBusManager manager;
    private final UUID cueBusId;
    private CueBus savedBus;

    public DeleteCueBusAction(CueBusManager manager, UUID cueBusId) {
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
        this.cueBusId = Objects.requireNonNull(cueBusId, "cueBusId must not be null");
    }

    @Override
    public String description() {
        return "Delete Cue Bus";
    }

    @Override
    public void execute() {
        CueBus current = manager.getById(cueBusId);
        if (current == null) {
            throw new IllegalStateException("cue bus not registered: " + cueBusId);
        }
        savedBus = current;
        manager.removeCueBus(cueBusId);
    }

    @Override
    public void undo() {
        if (savedBus != null) {
            manager.addCueBus(savedBus);
        }
    }
}
