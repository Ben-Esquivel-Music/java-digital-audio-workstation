package com.benesquivelmusic.daw.core.mixer.spatial;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Undoable action that sets (or removes) a {@link BedChannelRouting}
 * for a single track on a {@link BedBusManager}.
 *
 * <p>Passing a {@code null} {@code newRouting} removes the routing for
 * the track, mirroring the "no bed routing" state. Either way,
 * {@link #undo()} restores the previous routing exactly.</p>
 */
public final class SetBedRoutingAction implements UndoableAction {

    private final BedBusManager manager;
    private final UUID trackId;
    private final BedChannelRouting newRouting;
    private BedChannelRouting previousRouting;
    private boolean hadPreviousRouting;

    /**
     * Creates a new action.
     *
     * @param manager    the bed bus manager
     * @param trackId    the track whose routing should change
     * @param newRouting the desired routing, or {@code null} to remove
     */
    public SetBedRoutingAction(BedBusManager manager, UUID trackId, BedChannelRouting newRouting) {
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
        this.trackId = Objects.requireNonNull(trackId, "trackId must not be null");
        if (newRouting != null && !newRouting.trackId().equals(trackId)) {
            throw new IllegalArgumentException(
                    "newRouting trackId " + newRouting.trackId()
                            + " does not match action trackId " + trackId);
        }
        this.newRouting = newRouting;
    }

    @Override
    public String description() {
        return "Set Bed Routing";
    }

    @Override
    public void execute() {
        Optional<BedChannelRouting> existing = manager.getRouting(trackId);
        hadPreviousRouting = existing.isPresent();
        previousRouting = existing.orElse(null);
        if (newRouting == null) {
            manager.removeRouting(trackId);
        } else {
            manager.setRouting(newRouting);
        }
    }

    @Override
    public void undo() {
        if (hadPreviousRouting && previousRouting != null) {
            manager.setRouting(previousRouting);
        } else {
            manager.removeRouting(trackId);
        }
    }
}
