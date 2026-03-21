package com.benesquivelmusic.daw.sdk.event;

/**
 * Listener for auto-save and checkpoint events.
 *
 * <p>Plugin developers can implement this interface to be notified when
 * the DAW performs automatic checkpoints during long-running recording
 * sessions. This allows plugins to persist their own state alongside
 * the project data.</p>
 */
public interface AutoSaveListener {

    /**
     * Called just before the DAW performs an auto-save checkpoint.
     *
     * @param checkpointId a unique identifier for this checkpoint
     */
    void onBeforeCheckpoint(String checkpointId);

    /**
     * Called after an auto-save checkpoint completes successfully.
     *
     * @param checkpointId the identifier of the completed checkpoint
     */
    void onAfterCheckpoint(String checkpointId);

    /**
     * Called if an auto-save checkpoint fails.
     *
     * @param checkpointId the identifier of the failed checkpoint
     * @param cause        the cause of the failure
     */
    void onCheckpointFailed(String checkpointId, Throwable cause);
}
