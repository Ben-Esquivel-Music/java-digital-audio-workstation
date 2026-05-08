package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.track.TrackColor;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An undoable action that creates a new {@link VcaGroup} and registers it
 * with the given {@link VcaGroupManager}.
 *
 * <p>Executing the action creates the VCA group on first run and re-adds
 * the same {@code VcaGroup} instance on redo so that the group's id remains
 * stable across undo/redo cycles. Undoing removes the group from the
 * manager.</p>
 */
public final class CreateVcaGroupAction implements UndoableAction {

    private final VcaGroupManager manager;
    private final String label;
    private final TrackColor color;
    private final List<UUID> initialMembers;
    private VcaGroup createdGroup;

    /**
     * Creates the action for an empty (no-members) VCA group at unity gain.
     */
    public CreateVcaGroupAction(VcaGroupManager manager, String label) {
        this(manager, label, null, List.of());
    }

    /**
     * Creates the action for a VCA group seeded with the given members at
     * unity gain. Mirrors the "select several channels → right-click →
     * Create VCA" UX described in the issue.
     */
    public CreateVcaGroupAction(VcaGroupManager manager, String label, List<UUID> initialMembers) {
        this(manager, label, null, initialMembers);
    }

    /**
     * Creates the action for a VCA group with a label, color, and seed
     * members. Matches the {@code new CreateVcaGroupAction(manager, name,
     * color, selectedChannelIds)} call shape described in the issue's UI
     * spec — the right-click "Create VCA from selection" flow constructs
     * the action with a user-picked color.
     */
    public CreateVcaGroupAction(VcaGroupManager manager,
                                String label,
                                TrackColor color,
                                List<UUID> initialMembers) {
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
        this.label = Objects.requireNonNull(label, "label must not be null");
        this.color = color; // may be null = no color assigned
        this.initialMembers = List.copyOf(
                Objects.requireNonNull(initialMembers, "initialMembers must not be null"));
    }

    @Override
    public String description() {
        return "Create VCA Group";
    }

    @Override
    public void execute() {
        if (createdGroup == null) {
            // First execute: create-and-register so the manager mints the id.
            // We then immediately replace the group with one that carries the
            // user-picked color (the manager's createVcaGroup does not take a
            // color parameter), preserving id/members across save/load and
            // undo/redo cycles.
            VcaGroup seeded = manager.createVcaGroup(label, initialMembers);
            if (color != null) {
                VcaGroup colored = seeded.withColor(color);
                manager.replace(colored);
                createdGroup = colored;
            } else {
                createdGroup = seeded;
            }
        } else {
            manager.addVcaGroup(createdGroup);
        }
    }

    @Override
    public void undo() {
        if (createdGroup != null) {
            manager.removeVcaGroup(createdGroup.id());
        }
    }

    /** Returns the created VCA group, or {@code null} if not yet executed. */
    public VcaGroup getGroup() {
        return createdGroup;
    }
}
