package com.benesquivelmusic.daw.core.midi;

import com.benesquivelmusic.daw.core.clip.Clip;

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
public final class MidiClip implements Clip {

    private final List<MidiNoteData> notes = new ArrayList<>();
    private final List<MidiCcLane> ccLanes = new ArrayList<>();
    private boolean locked;

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

    /**
     * Returns {@code true} when this clip is time-locked and refuses
     * position-changing operations (slip, ripple, etc.).
     *
     * <p>Locked MIDI clips still allow note edits (add / remove / pitch /
     * velocity changes) — lock is strictly about the timeline position
     * of the clip's notes, not their pitches or values.</p>
     */
    @Override
    public boolean isLocked() {
        return locked;
    }

    /**
     * Sets the time-lock flag directly. Prefer
     * {@code SetClipLockedAction} for user-driven changes so the toggle
     * lands on the undo stack.
     */
    @Override
    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    @Override
    public String getDisplayName() {
        return "MIDI Clip";
    }

    // ── CC editing lanes ───────────────────────────────────────────────────
    //
    // Lanes are the per-clip configuration that drives the piano-roll
    // editor's bottom pane: which CCs are visible, their stacking order,
    // and the breakpoints inside each lane. Persisted by ProjectSerializer.

    /**
     * Adds a CC editing lane to this clip.
     *
     * @param lane the lane to add
     * @throws NullPointerException if {@code lane} is {@code null}
     */
    public void addCcLane(MidiCcLane lane) {
        Objects.requireNonNull(lane, "lane must not be null");
        ccLanes.add(lane);
    }

    /**
     * Removes a CC editing lane from this clip.
     *
     * @param lane the lane to remove
     * @return {@code true} if the lane was found and removed
     */
    public boolean removeCcLane(MidiCcLane lane) {
        return ccLanes.remove(lane);
    }

    /**
     * Returns an unmodifiable view of the CC editing lanes shown
     * underneath the piano roll for this clip.
     *
     * @return the lane list (never {@code null})
     */
    public List<MidiCcLane> getCcLanes() {
        return Collections.unmodifiableList(ccLanes);
    }

    /** Removes all CC lanes. */
    public void clearCcLanes() {
        ccLanes.clear();
    }
}
