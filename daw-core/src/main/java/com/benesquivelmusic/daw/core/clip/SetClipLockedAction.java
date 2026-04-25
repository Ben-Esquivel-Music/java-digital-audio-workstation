package com.benesquivelmusic.daw.core.clip;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that toggles or sets the {@code locked} flag of a
 * {@link Clip}.
 *
 * <p>Intended to back the "Lock selected" / "Unlock selected"
 * context-menu items and the {@code Ctrl+L} / {@code Ctrl+Shift+L}
 * shortcuts in the UI layer (the actual menu/shortcut wiring lives in
 * {@code daw-app} and is not part of this core PR). Setting the flag
 * through this action — rather than calling
 * {@link Clip#setLocked(boolean)} directly — places the toggle on the
 * undo stack so users can {@code Ctrl+Z} an accidental lock change.</p>
 */
public final class SetClipLockedAction implements UndoableAction {

    private final Clip clip;
    private final boolean newLocked;
    private boolean previousLocked;
    private boolean executed;

    /**
     * Creates a new set-clip-locked action.
     *
     * @param clip      the clip whose lock flag is being toggled
     * @param newLocked the desired new lock state
     * @throws NullPointerException if {@code clip} is {@code null}
     */
    public SetClipLockedAction(Clip clip, boolean newLocked) {
        this.clip = Objects.requireNonNull(clip, "clip must not be null");
        this.newLocked = newLocked;
    }

    @Override
    public String description() {
        return newLocked ? "Lock Clip" : "Unlock Clip";
    }

    @Override
    public void execute() {
        previousLocked = clip.isLocked();
        clip.setLocked(newLocked);
        executed = true;
    }

    @Override
    public void undo() {
        if (!executed) {
            throw new IllegalStateException("undo() called before execute()");
        }
        clip.setLocked(previousLocked);
    }
}
