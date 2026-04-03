package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.track.Track;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure clip-editing operations used by the track context menu.
 *
 * <p>Each operation mutates a {@link Track}'s clip list and returns a
 * {@link Result} that captures the removed and added clips so the caller
 * can build an {@link com.benesquivelmusic.daw.core.undo.UndoableAction}.</p>
 *
 * <p>This class is intentionally free of JavaFX dependencies so that the
 * editing logic can be unit-tested without the toolkit.</p>
 */
final class ClipEditOperations {

    private ClipEditOperations() { /* utility class */ }

    /**
     * The result of a clip-editing operation, capturing the clips that were
     * removed from and added to the track so the operation can be undone.
     */
    record Result(List<AudioClip> removedClips, List<AudioClip> addedClips) {
        Result {
            removedClips = List.copyOf(removedClips);
            addedClips = List.copyOf(addedClips);
        }

        /** Reverses the operation: removes added clips and restores removed clips. */
        void undo(Track track) {
            for (AudioClip clip : addedClips) track.removeClip(clip);
            for (AudioClip clip : removedClips) track.addClip(clip);
        }
    }

    /**
     * The result of a trim operation, capturing the original clip state so
     * the operation can be undone by restoring each clip's properties.
     */
    record TrimResult(List<AudioClip> trimmedClips, List<double[]> savedState) {
        TrimResult {
            trimmedClips = List.copyOf(trimmedClips);
            savedState = List.copyOf(savedState);
        }

        /** Reverses the trim by restoring saved start, duration, and sourceOffset. */
        void undo() {
            for (int i = 0; i < trimmedClips.size(); i++) {
                AudioClip clip = trimmedClips.get(i);
                double[] saved = savedState.get(i);
                clip.setStartBeat(saved[0]);
                clip.setDurationBeats(saved[1]);
                clip.setSourceOffsetBeats(saved[2]);
            }
        }
    }

    /**
     * Pastes the given clips at the specified playhead position on the target
     * track, splitting and removing any existing clips that overlap the pasted
     * region.
     *
     * <p>The overlap removal is computed once against a snapshot of the track's
     * pre-existing clips, so multiple clipboard entries do not interfere with
     * each other.</p>
     *
     * @param track     the target track
     * @param clipsToPaste the clipboard clips to paste (originals — they will be duplicated)
     * @param playhead  the beat position at which to paste
     * @return a {@link Result} for undo support
     */
    static Result pasteOver(Track track, List<AudioClip> clipsToPaste, double playhead) {
        Objects.requireNonNull(track, "track must not be null");
        Objects.requireNonNull(clipsToPaste, "clipsToPaste must not be null");

        List<AudioClip> removedClips = new ArrayList<>();
        List<AudioClip> addedClips = new ArrayList<>();

        // Compute the combined paste span across all entries
        double pasteEnd = playhead;
        List<AudioClip> pastedDuplicates = new ArrayList<>();
        for (AudioClip original : clipsToPaste) {
            AudioClip pasted = original.duplicate();
            pasted.setStartBeat(playhead);
            pasteEnd = Math.max(pasteEnd, playhead + pasted.getDurationBeats());
            pastedDuplicates.add(pasted);
        }

        // Snapshot existing clips and split/remove overlaps once
        for (AudioClip clip : new ArrayList<>(track.getClips())) {
            double clipStart = clip.getStartBeat();
            double clipEnd = clip.getEndBeat();
            if (clipStart < pasteEnd && clipEnd > playhead) {
                track.removeClip(clip);
                removedClips.add(clip);
                if (clipStart < playhead) {
                    AudioClip before = clip.duplicate();
                    before.setStartBeat(clipStart);
                    before.setSourceOffsetBeats(clip.getSourceOffsetBeats());
                    before.setDurationBeats(playhead - clipStart);
                    track.addClip(before);
                    addedClips.add(before);
                }
                if (clipEnd > pasteEnd) {
                    AudioClip after = clip.duplicate();
                    after.setSourceOffsetBeats(clip.getSourceOffsetBeats() + (pasteEnd - clipStart));
                    after.setStartBeat(pasteEnd);
                    after.setDurationBeats(clipEnd - pasteEnd);
                    track.addClip(after);
                    addedClips.add(after);
                }
            }
        }

        // Add all pasted clips
        for (AudioClip pasted : pastedDuplicates) {
            track.addClip(pasted);
            addedClips.add(pasted);
        }

        return new Result(removedClips, addedClips);
    }

    /**
     * Trims all clips on the track that overlap the selection range so that
     * they fit within the selection boundaries.
     *
     * <p>Clips fully inside the selection are unchanged. Clips that extend
     * beyond the selection have their start, duration, and source offset
     * adjusted in place.</p>
     *
     * @param track    the target track
     * @param selStart the selection start beat
     * @param selEnd   the selection end beat
     * @return a {@link TrimResult} for undo support, or {@code null} if no
     *         clips overlapped the selection
     */
    static TrimResult trimToSelection(Track track, double selStart, double selEnd) {
        Objects.requireNonNull(track, "track must not be null");

        List<AudioClip> clipsToTrim = new ArrayList<>();
        for (AudioClip clip : track.getClips()) {
            if (clip.getStartBeat() < selEnd && clip.getEndBeat() > selStart) {
                clipsToTrim.add(clip);
            }
        }
        if (clipsToTrim.isEmpty()) {
            return null;
        }

        List<double[]> savedState = new ArrayList<>();
        for (AudioClip clip : clipsToTrim) {
            savedState.add(new double[]{
                    clip.getStartBeat(), clip.getDurationBeats(), clip.getSourceOffsetBeats()});
        }

        for (AudioClip clip : clipsToTrim) {
            double newStart = Math.max(clip.getStartBeat(), selStart);
            double newEnd = Math.min(clip.getEndBeat(), selEnd);
            if (newEnd > newStart) {
                double offsetDelta = newStart - clip.getStartBeat();
                clip.setSourceOffsetBeats(clip.getSourceOffsetBeats() + offsetDelta);
                clip.setStartBeat(newStart);
                clip.setDurationBeats(newEnd - newStart);
            }
        }

        return new TrimResult(clipsToTrim, savedState);
    }

    /**
     * Crops the track to the selection range, removing clips entirely outside
     * the selection and trimming clips that partially overlap.
     *
     * <p>Clips fully inside the selection are left untouched. Clips that
     * extend beyond the selection are replaced with trimmed duplicates.
     * Clips entirely outside are removed.</p>
     *
     * @param track    the target track
     * @param selStart the selection start beat
     * @param selEnd   the selection end beat
     * @return a {@link Result} for undo support
     */
    static Result crop(Track track, double selStart, double selEnd) {
        Objects.requireNonNull(track, "track must not be null");

        List<AudioClip> removedClips = new ArrayList<>();
        List<AudioClip> addedClips = new ArrayList<>();

        for (AudioClip clip : new ArrayList<>(track.getClips())) {
            double clipStart = clip.getStartBeat();
            double clipEnd = clip.getEndBeat();
            if (clipEnd <= selStart || clipStart >= selEnd) {
                // Completely outside — remove
                track.removeClip(clip);
                removedClips.add(clip);
            } else if (clipStart < selStart || clipEnd > selEnd) {
                // Partially inside — replace with trimmed copy
                double newStart = Math.max(clipStart, selStart);
                double newEnd = Math.min(clipEnd, selEnd);
                AudioClip trimmed = clip.duplicate();
                double offsetDelta = newStart - clipStart;
                trimmed.setSourceOffsetBeats(clip.getSourceOffsetBeats() + offsetDelta);
                trimmed.setStartBeat(newStart);
                trimmed.setDurationBeats(newEnd - newStart);
                track.removeClip(clip);
                removedClips.add(clip);
                track.addClip(trimmed);
                addedClips.add(trimmed);
            }
            // Fully inside — keep as-is
        }

        return new Result(removedClips, addedClips);
    }
}
