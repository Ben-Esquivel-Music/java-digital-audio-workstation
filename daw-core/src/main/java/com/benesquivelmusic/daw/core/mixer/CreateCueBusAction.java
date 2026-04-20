package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that creates a new {@link CueBus} and registers it with
 * the given {@link CueBusManager}.
 *
 * <p>Executing the action creates the cue bus on first run and re-adds the
 * same {@code CueBus} instance on redo so the bus id is stable. Undoing
 * removes the bus from the manager.</p>
 */
public final class CreateCueBusAction implements UndoableAction {

    private final CueBusManager manager;
    private final String label;
    private final int hardwareOutputIndex;
    private CueBus createdBus;

    public CreateCueBusAction(CueBusManager manager, String label, int hardwareOutputIndex) {
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
        this.label = Objects.requireNonNull(label, "label must not be null");
        this.hardwareOutputIndex = hardwareOutputIndex;
    }

    @Override
    public String description() {
        return "Create Cue Bus";
    }

    @Override
    public void execute() {
        if (createdBus == null) {
            createdBus = manager.createCueBus(label, hardwareOutputIndex);
        } else {
            manager.addCueBus(createdBus);
        }
    }

    @Override
    public void undo() {
        if (createdBus != null) {
            manager.removeCueBus(createdBus.id());
        }
    }

    /** Returns the created cue bus, or {@code null} if not yet executed. */
    public CueBus getCueBus() {
        return createdBus;
    }
}
