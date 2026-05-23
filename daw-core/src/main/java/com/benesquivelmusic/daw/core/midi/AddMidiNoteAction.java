package com.benesquivelmusic.daw.core.midi;

import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.event.ClipEvent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An undoable action that adds a MIDI note to a {@link MidiClip}.
 */
public final class AddMidiNoteAction implements UndoableAction {

    private final MidiClip clip;
    private final MidiNoteData note;

    /**
     * Creates a new add-note action.
     *
     * @param clip the clip to add the note to
     * @param note the note to add
     */
    public AddMidiNoteAction(MidiClip clip, MidiNoteData note) {
        this.clip = Objects.requireNonNull(clip, "clip must not be null");
        this.note = Objects.requireNonNull(note, "note must not be null");
    }

    @Override
    public String description() {
        return "Add MIDI Note";
    }

    @Override
    public void execute() {
        clip.addNote(note);
        publishTrimmed();
    }

    @Override
    public void undo() {
        clip.removeNote(note);
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
