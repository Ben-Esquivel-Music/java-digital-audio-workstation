package com.benesquivelmusic.daw.core.persistence;

/**
 * Why a checkpoint was written (story 331): every checkpoint outcome a
 * {@link CheckpointManager} reports carries one, so a status surface can tell
 * an autosave from the checkpoint of an explicit gesture.
 */
public enum CheckpointOrigin {

    /** The autosave cadence's scheduled tick. */
    SCHEDULED,

    /** The checkpoint of a manual save, written from the bytes the save wrote to {@code project.daw}. */
    AFTER_SAVE,

    /**
     * The user's File ▸ Create Checkpoint: always written, even when its
     * revision is already checkpointed (ruling G8).
     */
    USER,

    /** The safety copy of a save whose head write failed after its document rendered (ruling G9). */
    SAFETY_COPY,

    /**
     * The exit protocol's closing checkpoint (story 333; Persistence Integrity
     * book §5.4; ruling R3): written once the close decided, by the running
     * generation, whether or not its revision is already checkpointed and
     * whether or not autosave is enabled; no in-memory browser entry and no
     * label; counts toward retention.
     */
    CLOSING
}
