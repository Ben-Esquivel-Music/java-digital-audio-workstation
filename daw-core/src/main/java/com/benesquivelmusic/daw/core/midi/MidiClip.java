package com.benesquivelmusic.daw.core.midi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A mutable container for MIDI notes placed on a track's timeline.
 *
 * <p>Each note is represented as a {@link MidiNoteData} record. Notes can be
 * added, removed, replaced, and queried. This class serves as the model for
 * MIDI note editing in the piano roll and for MIDI recording.</p>
 *
 * <p>All undoable note-editing actions ({@link AddMidiNoteAction},
 * {@link RemoveMidiNoteAction}, etc.) operate on a {@code MidiClip}.</p>
 */
public final class MidiClip {

    private final List<MidiNoteData> notes = new ArrayList<>();

    /**
     * Creates an empty MIDI clip.
     */
    public MidiClip() {
    }

    /**
     * Adds a note to this clip.
     *
     * @param note the note to add
     * @throws NullPointerException if {@code note} is {@code null}
     */
    public void addNote(MidiNoteData note) {
        Objects.requireNonNull(note, "note must not be null");
        notes.add(note);
    }

    /**
     * Removes a note from this clip.
     *
     * @param note the note to remove
     * @return {@code true} if the note was found and removed
     */
    public boolean removeNote(MidiNoteData note) {
        return notes.remove(note);
    }

    /**
     * Replaces the note at the given index with a new note.
     *
     * @param index   the index of the note to replace
     * @param newNote the replacement note
     * @return the previous note at the given index
     * @throws IndexOutOfBoundsException if the index is out of range
     * @throws NullPointerException      if {@code newNote} is {@code null}
     */
    public MidiNoteData replaceNote(int index, MidiNoteData newNote) {
        Objects.requireNonNull(newNote, "newNote must not be null");
        return notes.set(index, newNote);
    }

    /**
     * Returns the note at the given index.
     *
     * @param index the index
     * @return the note at the given index
     * @throws IndexOutOfBoundsException if the index is out of range
     */
    public MidiNoteData getNote(int index) {
        return notes.get(index);
    }

    /**
     * Returns the index of the given note, or {@code -1} if not found.
     *
     * @param note the note to find
     * @return the index, or {@code -1}
     */
    public int indexOf(MidiNoteData note) {
        return notes.indexOf(note);
    }

    /**
     * Returns an unmodifiable view of the notes in this clip.
     *
     * @return the note list
     */
    public List<MidiNoteData> getNotes() {
        return Collections.unmodifiableList(notes);
    }

    /**
     * Returns the number of notes in this clip.
     *
     * @return the note count
     */
    public int size() {
        return notes.size();
    }

    /**
     * Returns {@code true} if this clip contains no notes.
     *
     * @return whether the clip is empty
     */
    public boolean isEmpty() {
        return notes.isEmpty();
    }

    /**
     * Removes all notes from this clip.
     */
    public void clear() {
        notes.clear();
    }
}
