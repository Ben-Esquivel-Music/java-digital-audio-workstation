package com.benesquivelmusic.daw.core.midi;

import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.event.ClipEvent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An undoable action that removes a MIDI note from a {@link MidiClip}.
 */
public final class RemoveMidiNoteAction implements UndoableAction {

    private final MidiClip clip;
    private final MidiNoteData note;
    private int removedIndex = -1;

    /**
     * Creates a new remove-note action.
     *
     * @param clip the clip to remove the note from
     * @param note the note to remove
     */
    public RemoveMidiNoteAction(MidiClip clip, MidiNoteData note) {
        this.clip = Objects.requireNonNull(clip, "clip must not be null");
        this.note = Objects.requireNonNull(note, "note must not be null");
    }

    @Override
    public String description() {
        return "Delete MIDI Note";
    }

    @Override
    public void execute() {
        removedIndex = clip.indexOf(note);
        if (clip.removeNote(note)) {
            publishTrimmed();
        }
    }

    @Override
    public void undo() {
        clip.addNote(note);
        publishTrimmed();
    }

    private void publishTrimmed() {
        UUID trackId = clip.getOwningTrackId();
        if (trackId == null) {
            return;
        }
        EventBusPublisher.publish(new ClipEvent.Trimmed(
                trackId, clip.getId(), Instant.now()));
    }
}
