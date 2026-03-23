package com.benesquivelmusic.daw.app.ui;

/**
 * Identifies the edit tools available for interacting with clips and events
 * in the arrangement and editor views.
 *
 * <p>Exactly one tool is active at a time. The active tool determines mouse
 * behavior when the user clicks or drags in the arrangement or editor.</p>
 */
public enum EditTool {

    /** Select and move clips/notes. */
    POINTER,

    /** Draw new clips/notes. */
    PENCIL,

    /** Delete clips/notes on click. */
    ERASER,

    /** Split clips at click position. */
    SCISSORS,

    /** Join adjacent clips. */
    GLUE
}
