package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;
import java.util.UUID;

/**
 * An undoable action that inserts or replaces a {@link CueSend} on a
 * {@link CueBus}.
 *
 * <p>If a send already exists on the bus for {@link CueSend#trackId()}, it is
 * replaced and the previous send is captured for undo. If no send existed,
 * undo removes the newly added send.</p>
 */
public final class SetCueSendAction implements UndoableAction {

    private final CueBusManager manager;
    private final UUID cueBusId;
    private final CueSend newSend;
    private CueSend previousSend;
    private boolean existedBefore;

    public SetCueSendAction(CueBusManager manager, UUID cueBusId, CueSend newSend) {
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
        this.cueBusId = Objects.requireNonNull(cueBusId, "cueBusId must not be null");
        this.newSend = Objects.requireNonNull(newSend, "newSend must not be null");
    }

    @Override
    public String description() {
        return "Set Cue Send";
    }

    @Override
    public void execute() {
        CueBus bus = manager.getById(cueBusId);
        if (bus == null) {
            throw new IllegalStateException("cue bus not registered: " + cueBusId);
        }
        CueSend existing = bus.findSend(newSend.trackId());
        if (existing != null) {
            previousSend = existing;
            existedBefore = true;
        } else {
            previousSend = null;
            existedBefore = false;
        }
        manager.replace(bus.withSend(newSend));
    }

    @Override
    public void undo() {
        CueBus bus = manager.getById(cueBusId);
        if (bus == null) {
            return;
        }
        CueBus restored = existedBefore
                ? bus.withSend(previousSend)
                : bus.withoutSend(newSend.trackId());
        manager.replace(restored);
    }
}
