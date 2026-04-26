package com.benesquivelmusic.daw.core.snapshot;

/**
 * The trigger that caused a project snapshot to be created.
 *
 * <p>Used by the snapshot history browser (see story <em>Snapshot History
 * Browser with Visual Diff Preview</em>) to label each row in the timeline
 * and to apply per-source retention policies:</p>
 *
 * <ul>
 *   <li>{@link #AUTOSAVE} — written periodically by the auto-save
 *       scheduler; retained for 7 days rolling.</li>
 *   <li>{@link #USER_CHECKPOINT} — explicitly created by the user via the
 *       <em>Create Checkpoint</em> action (Ctrl+Alt+S); retained
 *       indefinitely.</li>
 *   <li>{@link #UNDO_POINT} — generated implicitly from an entry on the
 *       {@code UndoManager}'s history; retained for the current session
 *       only.</li>
 * </ul>
 */
public enum SnapshotKind {
    /** A snapshot produced automatically by the auto-save / checkpoint scheduler. */
    AUTOSAVE,
    /** A snapshot the user requested explicitly. */
    USER_CHECKPOINT,
    /** A snapshot tied to an entry in the undo history. */
    UNDO_POINT
}
