package com.benesquivelmusic.daw.core.comping;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * Undoable action that creates a new {@link TakeLane} on a {@link TakeComping}
 * and seeds it with a single {@link AudioClip}.
 *
 * <p>This is used during recording: each recorded take stacks as a new lane
 * beneath the main track lane, preserving prior takes (the previous take is
 * not overwritten). Undo removes the lane and any comp regions that
 * referenced it.</p>
 */
public final class CreateTakeAction implements UndoableAction {

    private final TakeComping comping;
    private final String takeName;
    private final AudioClip audioClip;

    private TakeLane createdLane;
    private int insertedIndex = -1;

    /**
     * Creates a new create-take action.
     *
     * @param comping   the take comping instance to mutate
     * @param takeName  the display name of the new take (e.g. {@code "Take 3"})
     * @param audioClip the clip captured during this take
     */
    public CreateTakeAction(TakeComping comping, String takeName, AudioClip audioClip) {
        this.comping = Objects.requireNonNull(comping, "comping must not be null");
        this.takeName = Objects.requireNonNull(takeName, "takeName must not be null");
        this.audioClip = Objects.requireNonNull(audioClip, "audioClip must not be null");
    }

    @Override
    public String description() {
        return "Create Take";
    }

    @Override
    public void execute() {
        if (createdLane == null) {
            createdLane = new TakeLane(takeName);
            createdLane.addClip(audioClip);
        }
        insertedIndex = comping.getTakeLaneCount();
        comping.addTakeLane(createdLane);
    }

    @Override
    public void undo() {
        if (createdLane != null) {
            comping.removeTakeLane(createdLane);
            insertedIndex = -1;
        }
    }

    /** Returns the newly created lane, or {@code null} before {@link #execute()}. */
    public TakeLane getCreatedLane() {
        return createdLane;
    }

    /** Returns the index of the newly created lane, or {@code -1} before execute. */
    public int getInsertedIndex() {
        return insertedIndex;
    }
}
