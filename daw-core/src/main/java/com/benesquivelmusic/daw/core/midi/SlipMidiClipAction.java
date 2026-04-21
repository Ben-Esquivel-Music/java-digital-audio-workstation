package com.benesquivelmusic.daw.core.midi;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An undoable action that slips every note in a {@link MidiClip} by a shared
 * column delta.
 *
 * <p>MIDI slip is the counterpart of audio slip (see
 * {@code com.benesquivelmusic.daw.core.audio.SlipClipAction}): every note's
 * {@code startColumn} shifts by the same amount while the clip's position
 * relative to the arrangement stays fixed (the "clip window" for a MIDI
 * clip is derived from the note positions themselves, so slipping the
 * notes effectively slides the content).</p>
 *
 * <p>The caller is responsible for clamping the column delta so that no
 * note's {@code startColumn} falls below zero — see
 * {@code SlipEditService.buildMidiSlip} in
 * {@code com.benesquivelmusic.daw.core.project.edit}.</p>
 *
 * <p>Story 139 — {@code docs/user-stories/139-slip-edit-within-clip.md}.</p>
 */
public final class SlipMidiClipAction implements UndoableAction {

    private final MidiClip clip;
    private final int columnDelta;

    /**
     * Creates a new MIDI slip action.
     *
     * @param clip        the MIDI clip whose notes are being slipped
     * @param columnDelta the column delta applied to every note's start
     *                    column; may be positive (slide right) or negative
     *                    (slide left). Must have been clamped by the caller
     *                    so no note ends up at a negative start column.
     * @throws NullPointerException if {@code clip} is {@code null}
     */
    public SlipMidiClipAction(MidiClip clip, int columnDelta) {
        this.clip = Objects.requireNonNull(clip, "clip must not be null");
        this.columnDelta = columnDelta;
    }

    @Override
    public String description() {
        return "Slip MIDI Clip";
    }

    @Override
    public void execute() {
        shiftAllNotes(columnDelta);
    }

    @Override
    public void undo() {
        shiftAllNotes(-columnDelta);
    }

    private void shiftAllNotes(int delta) {
        if (delta == 0 || clip.isEmpty()) {
            return;
        }
        // Snapshot the current note list; replaceNote mutates by index.
        List<MidiNoteData> current = new ArrayList<>(clip.getNotes());
        for (int i = 0; i < current.size(); i++) {
            MidiNoteData note = current.get(i);
            int newStart = note.startColumn() + delta;
            if (newStart < 0) {
                // Defensive: the action is only created with a pre-clamped
                // delta, but if misused we refuse to corrupt the model.
                throw new IllegalStateException(
                        "Slip would push note '" + note
                                + "' to negative start column: " + newStart);
            }
            clip.replaceNote(i, note.withStartColumn(newStart));
        }
    }
}
