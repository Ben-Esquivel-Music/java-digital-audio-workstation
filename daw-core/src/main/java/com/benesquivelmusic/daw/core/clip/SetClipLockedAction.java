package com.benesquivelmusic.daw.core.clip;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.midi.MidiClip;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.event.ClipEvent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

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
 *
 * <p>Story 283 — the owning {@link Track} is now a constructor
 * parameter so {@code execute()}/{@code undo()} can publish
 * {@code ClipEvent.Renamed(trackId, clipId, …)} (the closest
 * metadata-change variant in the sealed hierarchy). The previous
 * 2-arg constructor was removed.</p>
 */
public final class SetClipLockedAction implements UndoableAction {

    private final Track track;
    private final Clip clip;
    private final boolean newLocked;
    private boolean previousLocked;
    private boolean executed;

    /**
     * Creates a new set-clip-locked action.
     *
     * @param track     the track that owns the clip (required for
     *                  event publication)
     * @param clip      the clip whose lock flag is being toggled
     * @param newLocked the desired new lock state
     * @throws NullPointerException if {@code track} or {@code clip} is {@code null}
     */
    public SetClipLockedAction(Track track, Clip clip, boolean newLocked) {
        this.track = Objects.requireNonNull(track, "track must not be null");
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
        publishRenamed();
    }

    @Override
    public void undo() {
        if (!executed) {
            throw new IllegalStateException("undo() called before execute()");
        }
        clip.setLocked(previousLocked);
        publishRenamed();
    }

    private void publishRenamed() {
        UUID clipId = clipIdOf(clip);
        if (clipId == null) {
            return;
        }
        EventBusPublisher.publish(new ClipEvent.Renamed(
                UUID.fromString(track.getId()),
                clipId,
                Instant.now()));
    }

    private static UUID clipIdOf(Clip clip) {
        if (clip instanceof AudioClip ac) {
            return UUID.fromString(ac.getId());
        }
        if (clip instanceof MidiClip mc) {
            return mc.getId();
        }
        return null;
    }
}
