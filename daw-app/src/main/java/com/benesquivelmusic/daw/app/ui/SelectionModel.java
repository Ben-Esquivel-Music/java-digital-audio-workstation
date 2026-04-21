package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.midi.MidiClip;
import com.benesquivelmusic.daw.core.midi.MidiNoteData;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;

import java.util.*;

/**
 * Tracks the current time selection range and clip selection in the arrangement view.
 *
 * <p>A time selection is defined by a start beat and an end beat. When no
 * time selection is active, {@link #hasSelection()} returns {@code false}.</p>
 *
 * <p>A clip selection is an ordered set of (track, clip) pairs chosen by the
 * user via single-click, Ctrl+click (toggle), or rubber-band drag. When no
 * clips are selected, {@link #hasClipSelection()} returns {@code false}.</p>
 */
public final class SelectionModel {

    private boolean active;
    private double startBeat;
    private double endBeat;

    private final Map<AudioClip, Track> selectedClips = new LinkedHashMap<>();
    private final Map<MidiClip, Track> selectedMidiClips = new LinkedHashMap<>();

    /** Optional callback invoked whenever the clip selection changes. */
    private Runnable selectionChangeListener;

    /**
     * Beats per grid column — used to convert MIDI note column positions to
     * beat positions for region overlap tests. Derived from
     * {@link EditorView#BEATS_PER_COLUMN} to keep a single source of truth.
     */
    static final double BEATS_PER_COLUMN = EditorView.BEATS_PER_COLUMN;

    /**
     * Creates a selection model with no active selection.
     */
    public SelectionModel() {
        this.active = false;
        this.startBeat = 0.0;
        this.endBeat = 0.0;
    }

    /**
     * Sets a listener that is invoked whenever the clip selection changes
     * (both audio clip and MIDI clip selections).
     * Pass {@code null} to remove the listener.
     *
     * @param listener the selection change listener, or {@code null}
     */
    public void setSelectionChangeListener(Runnable listener) {
        this.selectionChangeListener = listener;
    }

    private void fireSelectionChanged() {
        if (selectionChangeListener != null) {
            selectionChangeListener.run();
        }
    }

    // ── Time selection ──────────────────────────────────────────────────────

    /**
     * Returns {@code true} if a time selection is currently active.
     *
     * @return whether a selection exists
     */
    public boolean hasSelection() {
        return active;
    }

    /**
     * Returns the start beat of the current selection.
     *
     * @return the selection start beat
     */
    public double getStartBeat() {
        return startBeat;
    }

    /**
     * Returns the end beat of the current selection.
     *
     * @return the selection end beat
     */
    public double getEndBeat() {
        return endBeat;
    }

    /**
     * Sets the selection range. The start must be less than the end.
     *
     * @param startBeat the start of the selection in beats
     * @param endBeat   the end of the selection in beats
     * @throws IllegalArgumentException if startBeat &ge; endBeat
     */
    public void setSelection(double startBeat, double endBeat) {
        if (startBeat >= endBeat) {
            throw new IllegalArgumentException(
                    "startBeat must be less than endBeat: " + startBeat + " >= " + endBeat);
        }
        this.startBeat = startBeat;
        this.endBeat = endBeat;
        this.active = true;
    }

    /**
     * Clears the current selection.
     */
    public void clearSelection() {
        this.active = false;
        this.startBeat = 0.0;
        this.endBeat = 0.0;
    }

    // ── Clip selection ──────────────────────────────────────────────────────

    /**
     * Returns {@code true} if at least one clip is selected.
     *
     * @return whether any clips are selected
     */
    public boolean hasClipSelection() {
        return !selectedClips.isEmpty() || !selectedMidiClips.isEmpty();
    }

    /**
     * Selects a single clip, clearing any previous clip selection.
     *
     * @param track the track that contains the clip
     * @param clip  the clip to select
     * @throws NullPointerException if either argument is {@code null}
     */
    public void selectClip(Track track, AudioClip clip) {
        Objects.requireNonNull(track, "track must not be null");
        Objects.requireNonNull(clip, "clip must not be null");
        selectedClips.clear();
        selectedMidiClips.clear();
        selectedClips.put(clip, track);
        fireSelectionChanged();
    }

    /**
     * Toggles the selection state of a clip (Ctrl+click behaviour).
     *
     * <p>If the clip is already selected it is deselected; otherwise it is
     * added to the current selection.</p>
     *
     * @param track the track that contains the clip
     * @param clip  the clip to toggle
     * @throws NullPointerException if either argument is {@code null}
     */
    public void toggleClipSelection(Track track, AudioClip clip) {
        Objects.requireNonNull(track, "track must not be null");
        Objects.requireNonNull(clip, "clip must not be null");
        if (selectedClips.containsKey(clip)) {
            selectedClips.remove(clip);
        } else {
            selectedClips.put(clip, track);
        }
        fireSelectionChanged();
    }

    /**
     * Selects all clips whose time range overlaps the given beat region on
     * the specified tracks (rubber-band selection).
     *
     * <p>Existing clip selection is cleared first.</p>
     *
     * @param tracks        the tracks to search
     * @param regionStart   the start beat of the selection region
     * @param regionEnd     the end beat of the selection region
     * @throws NullPointerException     if {@code tracks} is {@code null}
     * @throws IllegalArgumentException if {@code regionStart} &ge; {@code regionEnd}
     */
    public void selectClipsInRegion(List<Track> tracks, double regionStart, double regionEnd) {
        Objects.requireNonNull(tracks, "tracks must not be null");
        if (regionStart >= regionEnd) {
            throw new IllegalArgumentException(
                    "regionStart must be less than regionEnd: " + regionStart + " >= " + regionEnd);
        }
        selectedClips.clear();
        selectedMidiClips.clear();
        for (Track track : tracks) {
            for (AudioClip clip : track.getClips()) {
                if (clip.getStartBeat() < regionEnd && clip.getEndBeat() > regionStart) {
                    selectedClips.put(clip, track);
                }
            }
            if (track.getType() == TrackType.MIDI) {
                MidiClip midiClip = track.getMidiClip();
                if (!midiClip.isEmpty()) {
                    double midiStart = midiClipStartBeat(midiClip);
                    double midiEnd = midiClipEndBeat(midiClip);
                    if (midiStart < regionEnd && midiEnd > regionStart) {
                        selectedMidiClips.put(midiClip, track);
                    }
                }
            }
        }
        fireSelectionChanged();
    }

    /**
     * Adds all clips whose time range overlaps the given beat region on
     * the specified tracks to the current clip selection (additive
     * rubber-band selection).
     *
     * <p>Unlike {@link #selectClipsInRegion}, the existing clip selection
     * is <em>not</em> cleared — new matches are merged into whatever is
     * already selected. This supports Shift+rubber-band workflows.</p>
     *
     * @param tracks        the tracks to search
     * @param regionStart   the start beat of the selection region
     * @param regionEnd     the end beat of the selection region
     * @throws NullPointerException     if {@code tracks} is {@code null}
     * @throws IllegalArgumentException if {@code regionStart} &ge; {@code regionEnd}
     */
    public void addClipsInRegion(List<Track> tracks, double regionStart, double regionEnd) {
        Objects.requireNonNull(tracks, "tracks must not be null");
        if (regionStart >= regionEnd) {
            throw new IllegalArgumentException(
                    "regionStart must be less than regionEnd: " + regionStart + " >= " + regionEnd);
        }
        for (Track track : tracks) {
            for (AudioClip clip : track.getClips()) {
                if (clip.getStartBeat() < regionEnd && clip.getEndBeat() > regionStart) {
                    selectedClips.put(clip, track);
                }
            }
            if (track.getType() == TrackType.MIDI) {
                MidiClip midiClip = track.getMidiClip();
                if (!midiClip.isEmpty()) {
                    double midiStart = midiClipStartBeat(midiClip);
                    double midiEnd = midiClipEndBeat(midiClip);
                    if (midiStart < regionEnd && midiEnd > regionStart) {
                        selectedMidiClips.put(midiClip, track);
                    }
                }
            }
        }
        fireSelectionChanged();
    }

    /**
     * Returns an unmodifiable list of the currently selected clips as
     * {@link ClipboardEntry} instances (each carrying its source track).
     *
     * @return the selected clips (empty if nothing is selected)
     */
    public List<ClipboardEntry> getSelectedClips() {
        List<ClipboardEntry> result = new ArrayList<>(selectedClips.size());
        for (Map.Entry<AudioClip, Track> entry : selectedClips.entrySet()) {
            result.add(new ClipboardEntry(entry.getValue(), entry.getKey()));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Returns {@code true} if the given clip is currently selected.
     *
     * @param clip the clip to check
     * @return whether the clip is selected
     */
    public boolean isClipSelected(AudioClip clip) {
        return selectedClips.containsKey(clip);
    }

    /**
     * Clears the clip selection.
     */
    public void clearClipSelection() {
        selectedClips.clear();
        selectedMidiClips.clear();
        fireSelectionChanged();
    }

    // ── MIDI clip selection ─────────────────────────────────────────────────

    /**
     * Selects a single MIDI clip, clearing any previous clip selection
     * (both audio and MIDI).
     *
     * @param track   the track that contains the MIDI clip
     * @param midiClip the MIDI clip to select
     * @throws NullPointerException if either argument is {@code null}
     */
    public void selectMidiClip(Track track, MidiClip midiClip) {
        Objects.requireNonNull(track, "track must not be null");
        Objects.requireNonNull(midiClip, "midiClip must not be null");
        selectedClips.clear();
        selectedMidiClips.clear();
        selectedMidiClips.put(midiClip, track);
        fireSelectionChanged();
    }

    /**
     * Toggles the selection state of a MIDI clip (Shift-click behaviour).
     *
     * <p>If the MIDI clip is already selected it is deselected; otherwise it
     * is added to the current selection.</p>
     *
     * @param track   the track that contains the MIDI clip
     * @param midiClip the MIDI clip to toggle
     * @throws NullPointerException if either argument is {@code null}
     */
    public void toggleMidiClipSelection(Track track, MidiClip midiClip) {
        Objects.requireNonNull(track, "track must not be null");
        Objects.requireNonNull(midiClip, "midiClip must not be null");
        if (selectedMidiClips.containsKey(midiClip)) {
            selectedMidiClips.remove(midiClip);
        } else {
            selectedMidiClips.put(midiClip, track);
        }
        fireSelectionChanged();
    }

    /**
     * Returns {@code true} if the given MIDI clip is currently selected.
     *
     * @param midiClip the MIDI clip to check
     * @return whether the MIDI clip is selected
     */
    public boolean isMidiClipSelected(MidiClip midiClip) {
        return selectedMidiClips.containsKey(midiClip);
    }

    /**
     * Returns an unmodifiable snapshot of the currently selected MIDI clips
     * mapped to their host tracks. Used by slip-edit and other clip-level
     * operations that need to operate on the full MIDI clip selection.
     *
     * @return a map from MIDI clip to its host track (empty if nothing is selected)
     */
    public Map<MidiClip, Track> getSelectedMidiClips() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(selectedMidiClips));
    }

    // ── MIDI clip beat bounds helpers ────────────────────────────────────────

    /**
     * Computes the start beat of a non-empty MIDI clip from the earliest
     * note's start column.
     *
     * @param midiClip the MIDI clip (must not be empty)
     * @return the start beat
     * @throws IllegalArgumentException if the clip is empty
     */
    static double midiClipStartBeat(MidiClip midiClip) {
        if (midiClip.isEmpty()) {
            throw new IllegalArgumentException("MIDI clip must not be empty");
        }
        int minColumn = Integer.MAX_VALUE;
        for (MidiNoteData note : midiClip.getNotes()) {
            if (note.startColumn() < minColumn) {
                minColumn = note.startColumn();
            }
        }
        return minColumn * BEATS_PER_COLUMN;
    }

    /**
     * Computes the end beat of a non-empty MIDI clip from the latest
     * note's end column.
     *
     * @param midiClip the MIDI clip (must not be empty)
     * @return the end beat
     * @throws IllegalArgumentException if the clip is empty
     */
    static double midiClipEndBeat(MidiClip midiClip) {
        if (midiClip.isEmpty()) {
            throw new IllegalArgumentException("MIDI clip must not be empty");
        }
        int maxEndColumn = 0;
        for (MidiNoteData note : midiClip.getNotes()) {
            if (note.endColumn() > maxEndColumn) {
                maxEndColumn = note.endColumn();
            }
        }
        return maxEndColumn * BEATS_PER_COLUMN;
    }
}
