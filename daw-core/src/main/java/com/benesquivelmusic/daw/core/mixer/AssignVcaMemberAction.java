package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;
import java.util.UUID;

/**
 * An undoable action that adds a member channel to a {@link VcaGroup} or
 * removes one from it. Mirrors the drag-onto-strip / unassign UX described
 * in the issue.
 *
 * <p>The previous membership state is captured at {@link #execute()} time
 * so that {@link #undo()} restores it exactly, regardless of how the
 * channel got into / out of the group between execute and undo.</p>
 */
public final class AssignVcaMemberAction implements UndoableAction {

    private final VcaGroupManager manager;
    private final UUID groupId;
    private final UUID channelId;
    private final boolean assign;
    private boolean previouslyMember;
    private boolean executed;

    /**
     * @param manager   the manager owning the group
     * @param groupId   the target VCA group's id
     * @param channelId the channel to assign or unassign
     * @param assign    {@code true} to add the channel as a member,
     *                  {@code false} to remove it
     */
    public AssignVcaMemberAction(VcaGroupManager manager,
                                 UUID groupId,
                                 UUID channelId,
                                 boolean assign) {
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
        this.groupId = Objects.requireNonNull(groupId, "groupId must not be null");
        this.channelId = Objects.requireNonNull(channelId, "channelId must not be null");
        this.assign = assign;
    }

    @Override
    public String description() {
        return assign ? "Assign Channel to VCA" : "Remove Channel from VCA";
    }

    @Override
    public void execute() {
        VcaGroup group = manager.getById(groupId);
        if (group == null) {
            throw new IllegalStateException("vca group not registered: " + groupId);
        }
        // Capture the immediately-prior membership on every execute() so undo
        // restores the state that existed right before this redo, even if
        // other code paths toggled membership while the action was undone.
        previouslyMember = group.hasMember(channelId);
        executed = true;
        if (assign) {
            manager.addMember(groupId, channelId);
        } else {
            manager.removeMember(groupId, channelId);
        }
    }

    @Override
    public void undo() {
        if (!executed) {
            return;
        }
        if (manager.getById(groupId) == null) {
            return;
        }
        if (previouslyMember) {
            manager.addMember(groupId, channelId);
        } else {
            manager.removeMember(groupId, channelId);
        }
    }
}
