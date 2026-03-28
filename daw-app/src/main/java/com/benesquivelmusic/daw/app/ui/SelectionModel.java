package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.track.Track;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    /**
     * Creates a selection model with no active selection.
     */
    public SelectionModel() {
        this.active = false;
        this.startBeat = 0.0;
        this.endBeat = 0.0;
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
        return !selectedClips.isEmpty();
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
        selectedClips.put(clip, track);
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
        for (Track track : tracks) {
            for (AudioClip clip : track.getClips()) {
                if (clip.getStartBeat() < regionEnd && clip.getEndBeat() > regionStart) {
                    selectedClips.put(clip, track);
                }
            }
        }
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
    }
}
