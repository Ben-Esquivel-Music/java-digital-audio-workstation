package com.benesquivelmusic.daw.app.ui.dialogs;

/**
 * The user's decision from the story-298 crash-recovery dialog (Project Manager
 * Design Book §4.6.1). Returned by {@link RecoveryDialog} so the
 * {@code ProjectLifecycleController} can branch on what to do with a project
 * whose prior session did not exit cleanly.
 */
public enum RecoveryChoice {

    /**
     * Recover everything: load the recovery point the dialog names and apply
     * the journaled changes replay can rebuild, or promote a newer interrupted
     * save, backing up {@code project.daw} first when there is one.
     */
    REPLAY_ALL,

    /**
     * Restore checkpoint only: open the last clean save ({@code project.daw})
     * and, when journaled persistence is on, delete the journal; refused, with
     * nothing deleted, when there is no {@code project.daw}.
     */
    CHECKPOINT_ONLY,

    /**
     * Inspect the captured events before deciding (leaves the project
     * unopened).
     */
    INSPECT_EVENTS,

    /** Dismiss without opening anything. */
    CANCEL
}
