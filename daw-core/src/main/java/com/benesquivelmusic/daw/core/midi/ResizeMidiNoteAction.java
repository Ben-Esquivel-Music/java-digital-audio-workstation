package com.benesquivelmusic.daw.core.midi;

import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.event.ClipEvent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An undoable action that changes the duration of a MIDI note within a
 * {@link MidiClip}.
 */
public final class ResizeMidiNoteAction implements UndoableAction {

    private final MidiClip clip;
    private final MidiNoteData originalNote;
    private final MidiNoteData resizedNote;

    /**
     * Creates a new resize-note action.
     *
     * @param clip         the clip containing the note
     * @param originalNote the note before the resize
     * @param newDuration  the new duration in grid columns (≥ 1)
     */
    public ResizeMidiNoteAction(MidiClip clip, MidiNoteData originalNote,
                                int newDuration) {
        this.clip = Objects.requireNonNull(clip, "clip must not be null");
        this.originalNote = Objects.requireNonNull(originalNote,
                "originalNote must not be null");
        this.resizedNote = originalNote.withDurationColumns(newDuration);
    }

    @Override
    public String description() {
        return "Resize MIDI Note";
    }

    @Override
    public void execute() {
        int index = clip.indexOf(originalNote);
        if (index >= 0) {
            clip.replaceNote(index, resizedNote);
            publishTrimmed();
        }
    }

    @Override
    public void undo() {
        int index = clip.indexOf(resizedNote);
        if (index >= 0) {
            clip.replaceNote(index, originalNote);
            publishTrimmed();
        }
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
