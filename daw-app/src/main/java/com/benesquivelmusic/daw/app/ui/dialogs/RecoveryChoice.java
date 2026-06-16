package com.benesquivelmusic.daw.app.ui.dialogs;

/**
 * The user's decision from the story-298 crash-recovery dialog (Project Manager
 * Design Book §4.6.1). Returned by {@link RecoveryDialog} so the
 * {@code ProjectLifecycleController} can branch on what to do with a project
 * whose prior session did not exit cleanly.
 */
public enum RecoveryChoice {

    /**
     * Replay the whole write-ahead journal on top of the newest checkpoint —
     * the primary, "I want my work back" path.
     */
    REPLAY_ALL,

    /**
     * Open only the newest clean checkpoint and discard the journal — the
     * conservative "give me the last known-good save" path.
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
