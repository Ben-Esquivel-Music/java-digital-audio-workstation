package com.benesquivelmusic.daw.core.project.edit;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.CutClipsAction;
import com.benesquivelmusic.daw.core.audio.MoveClipAction;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.CompoundUndoableAction;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.edit.RippleMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Computes the atomic set of clip-position deltas for a ripple edit and
 * wraps them in a {@link CompoundUndoableAction}.
 *
 * <p>Ripple editing closes the gap left by a delete or follows a move by
 * shifting later clips on the affected tracks. The scope is governed by the
 * {@link RippleMode}:</p>
 * <ul>
 *   <li>{@link RippleMode#OFF} — no follow-on shift; only the primary edit
 *       is executed.</li>
 *   <li>{@link RippleMode#PER_TRACK} — clips later than the edit on the
 *       same track as each removed/moved clip shift by the edit delta.</li>
 *   <li>{@link RippleMode#ALL_TRACKS} — every clip whose start is at or
 *       after the edit cutoff, on every track in scope, shifts by the edit
 *       delta.</li>
 * </ul>
 *
 * <p>The service validates up-front that shifting does not cause
 * overlaps on destination tracks — if an overlap is detected, a
 * {@link RippleValidationException} is thrown so the caller can surface
 * an error instead of silently losing data.</p>
 *
 * <p>This class is stateless; all methods are static utilities.</p>
 */
public final class RippleEditService {

    /** Minimum meaningful beat-difference — smaller deltas are treated as zero. */
    private static final double EPSILON = 1e-6;

    private RippleEditService() { /* utility class */ }

    /**
     * Builds a ripple-delete compound action for the given clips.
     *
     * <p>For each track that contributes one or more deleted clips, the
     * method computes the earliest deleted start on that track and the
     * total "closed" duration — i.e. the sum of the durations of every
     * deleted contiguous region.
     *
     * <p>In {@link RippleMode#PER_TRACK}, later clips on the same track
     * shift left by that track's closed duration.
     * In {@link RippleMode#ALL_TRACKS}, later clips on <em>every</em> track
     * in {@code allTracks} shift left by the maximum closed duration across
     * all contributing tracks, using the overall earliest deleted start as
     * the cutoff. When a time selection is present it bounds the cutoff so
     * that only clips whose start falls at or after the selection are
     * treated as ripple candidates on non-contributing tracks.</p>
     *
     * @param entries         the (track, clip) pairs being deleted; must not be empty
     * @param mode            the ripple scope
     * @param allTracks       the tracks in scope for {@code ALL_TRACKS} ripple; may
     *                        be the full project track list
     * @param selectionStart  the time-selection start, or empty when no selection
     * @param selectionEnd    the time-selection end, or empty when no selection
     * @return a {@link CompoundUndoableAction} that deletes the clips and
     *         performs the shift
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if {@code entries} is empty
     * @throws RippleValidationException if applying the shift would cause
     *                                   overlaps on destination tracks
     */
    public static CompoundUndoableAction buildRippleDelete(
            List<Map.Entry<Track, AudioClip>> entries,
            RippleMode mode,
            List<Track> allTracks,
            OptionalDouble selectionStart,
            OptionalDouble selectionEnd) {
        Objects.requireNonNull(entries, "entries must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(allTracks, "allTracks must not be null");
        Objects.requireNonNull(selectionStart, "selectionStart must not be null");
        Objects.requireNonNull(selectionEnd, "selectionEnd must not be null");
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("entries must not be empty");
        }

        List<UndoableAction> actions = new ArrayList<>();
        // Primary action: the cut itself.
        actions.add(new CutClipsAction(entries));

        if (mode == RippleMode.OFF) {
            return new CompoundUndoableAction("Delete Clips", actions);
        }

        // Per-track, compute the cutoff (earliest deleted start) and the
        // total closed duration (sum of deleted contiguous-region durations).
        // The "closed" duration is simply the sum of the durations — that
        // matches the strict-gap-closing semantics (story non-goal: gap
        // preservation). Identity-hashed so independent Track instances in
        // tests are distinguished.
        Map<Track, List<AudioClip>> deletedByTrack = new IdentityHashMap<>();
        for (Map.Entry<Track, AudioClip> entry : entries) {
            deletedByTrack.computeIfAbsent(entry.getKey(), _ -> new ArrayList<>()).add(entry.getValue());
        }

        Map<Track, Double> cutoffByTrack = new IdentityHashMap<>();
        Map<Track, Double> closedByTrack = new IdentityHashMap<>();
        double overallCutoff = Double.POSITIVE_INFINITY;
        double maxClosed = 0.0;
        for (Map.Entry<Track, List<AudioClip>> e : deletedByTrack.entrySet()) {
            double earliest = Double.POSITIVE_INFINITY;
            double totalDuration = 0.0;
            for (AudioClip c : e.getValue()) {
                if (c.getStartBeat() < earliest) {
                    earliest = c.getStartBeat();
                }
                totalDuration += c.getDurationBeats();
            }
            cutoffByTrack.put(e.getKey(), earliest);
            closedByTrack.put(e.getKey(), totalDuration);
            if (earliest < overallCutoff) {
                overallCutoff = earliest;
            }
            if (totalDuration > maxClosed) {
                maxClosed = totalDuration;
            }
        }

        // Time-selection override: when present, the "closed" duration is the
        // selection length and the cutoff is the selection end — every clip
        // that starts strictly after the selection ripples left by the full
        // selection length (matches Reaper's ripple-delete-with-selection
        // behaviour and allows the user to clear 8 bars of mix with one edit).
        boolean selectionActive = selectionStart.isPresent() && selectionEnd.isPresent()
                && selectionEnd.getAsDouble() > selectionStart.getAsDouble() + EPSILON;
        double selectionLength = selectionActive
                ? selectionEnd.getAsDouble() - selectionStart.getAsDouble()
                : 0.0;

        // Deleted-clip identities for quick "skip" test when scanning tracks
        // for ripple candidates.
        Set<AudioClip> deletedClips = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Map.Entry<Track, AudioClip> entry : entries) {
            deletedClips.add(entry.getValue());
        }

        // Deletes always shift later clips LEFT to close the gap, so the
        // applied shift is negative — the closed duration is how far each
        // affected clip moves toward the deletion cutoff.
        if (mode == RippleMode.PER_TRACK) {
            for (Map.Entry<Track, List<AudioClip>> e : deletedByTrack.entrySet()) {
                Track track = e.getKey();
                double cutoff = selectionActive ? selectionEnd.getAsDouble() : cutoffByTrack.get(track);
                double shiftMagnitude = selectionActive ? selectionLength : closedByTrack.get(track);
                if (shiftMagnitude <= EPSILON) continue;
                addShiftActionsForTrack(actions, track, cutoff, -shiftMagnitude, deletedClips);
            }
        } else { // ALL_TRACKS
            double cutoff = selectionActive ? selectionEnd.getAsDouble() : overallCutoff;
            double shiftMagnitude = selectionActive ? selectionLength : maxClosed;
            if (shiftMagnitude > EPSILON) {
                for (Track track : allTracks) {
                    addShiftActionsForTrack(actions, track, cutoff, -shiftMagnitude, deletedClips);
                }
            }
        }

        return new CompoundUndoableAction("Ripple Delete", actions);
    }

    /**
     * Builds a ripple-move compound action for a single moved clip.
     *
     * <p>Later clips on the ripple-scope tracks shift by the same delta as
     * the moved clip, so the clip "carries" the arrangement after it. The
     * cutoff is the moved clip's original start beat — any clip starting
     * at or after that cutoff (other than the moved clip itself) is shifted.</p>
     *
     * <p>If a time-selection is present, moves whose original position is
     * <em>outside</em> the selection do not ripple (story requirement: "moves
     * outside do not"). Moves whose start is inside the selection ripple
     * normally.</p>
     *
     * @param clip            the clip being moved
     * @param clipTrack       the track the clip lives on
     * @param newStartBeat    the clip's new start position
     * @param mode            the ripple scope
     * @param allTracks       the tracks in scope for {@code ALL_TRACKS} ripple
     * @param selectionStart  the time-selection start, or empty when no selection
     * @param selectionEnd    the time-selection end, or empty when no selection
     * @return a {@link CompoundUndoableAction} that moves the clip and
     *         performs the shift
     * @throws NullPointerException     if any argument is {@code null}
     * @throws RippleValidationException if applying the shift would cause
     *                                   overlaps on destination tracks
     */
    public static CompoundUndoableAction buildRippleMove(
            AudioClip clip,
            Track clipTrack,
            double newStartBeat,
            RippleMode mode,
            List<Track> allTracks,
            OptionalDouble selectionStart,
            OptionalDouble selectionEnd) {
        Objects.requireNonNull(clip, "clip must not be null");
        Objects.requireNonNull(clipTrack, "clipTrack must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(allTracks, "allTracks must not be null");
        Objects.requireNonNull(selectionStart, "selectionStart must not be null");
        Objects.requireNonNull(selectionEnd, "selectionEnd must not be null");

        double originalStart = clip.getStartBeat();
        double delta = newStartBeat - originalStart;

        List<UndoableAction> actions = new ArrayList<>();
        actions.add(new MoveClipAction(clip, newStartBeat));

        if (mode == RippleMode.OFF || Math.abs(delta) <= EPSILON) {
            return new CompoundUndoableAction("Move Clip", actions);
        }

        // Selection gate: when a selection is active, only ripple moves whose
        // original start falls inside the selection. Moves outside the
        // selection leave other clips untouched.
        if (selectionStart.isPresent() && selectionEnd.isPresent()) {
            double selS = selectionStart.getAsDouble();
            double selE = selectionEnd.getAsDouble();
            if (selE > selS + EPSILON) {
                if (originalStart < selS - EPSILON || originalStart > selE + EPSILON) {
                    return new CompoundUndoableAction("Move Clip", actions);
                }
            }
        }

        // Clips strictly after the moved clip's original start — exclude the
        // moved clip itself — shift by delta. Negative deltas (moving the
        // clip left) shift later clips left; positive deltas shift them right.
        Set<AudioClip> exclude = Collections.newSetFromMap(new IdentityHashMap<>());
        exclude.add(clip);

        if (mode == RippleMode.PER_TRACK) {
            addShiftActionsForTrack(actions, clipTrack, originalStart + EPSILON, delta, exclude);
        } else { // ALL_TRACKS
            for (Track track : allTracks) {
                addShiftActionsForTrack(actions, track, originalStart + EPSILON, delta, exclude);
            }
        }

        return new CompoundUndoableAction("Ripple Move", actions);
    }

    /**
     * Adds shift actions for all clips on {@code track} whose start is at or
     * after {@code cutoff}, skipping clips in {@code exclude}. The shift amount
     * can be positive (right-shift) or negative (left-shift). Validates that
     * the post-shift clip does not overlap any non-shifted clip on the same
     * track — and throws {@link RippleValidationException} on conflict.
     */
    private static void addShiftActionsForTrack(
            List<UndoableAction> actions,
            Track track,
            double cutoff,
            double shift,
            Set<AudioClip> exclude) {
        // Partition the track's clips into "stationary" (before cutoff or
        // excluded) and "to-shift" (at/after cutoff and not excluded).
        List<AudioClip> stationary = new ArrayList<>();
        List<AudioClip> toShift = new ArrayList<>();
        for (AudioClip c : track.getClips()) {
            if (exclude.contains(c)) {
                continue;
            }
            if (c.getStartBeat() >= cutoff - EPSILON) {
                toShift.add(c);
            } else {
                stationary.add(c);
            }
        }

        // Refuse to shift any clip past the zero boundary — clips cannot have
        // negative start beats.
        for (AudioClip c : toShift) {
            double newStart = c.getStartBeat() + shift;
            if (newStart < -EPSILON) {
                throw new RippleValidationException(
                        "Ripple would push clip '" + c.getName()
                                + "' on track '" + track.getName()
                                + "' to a negative start beat (" + newStart + ")");
            }
        }

        // Validate that shifted clips do not overlap stationary clips.
        for (AudioClip shifted : toShift) {
            double newStart = shifted.getStartBeat() + shift;
            double newEnd = newStart + shifted.getDurationBeats();
            for (AudioClip still : stationary) {
                double stillStart = still.getStartBeat();
                double stillEnd = still.getEndBeat();
                if (newStart < stillEnd - EPSILON && newEnd > stillStart + EPSILON) {
                    throw new RippleValidationException(
                            "Ripple would overlap clip '" + shifted.getName()
                                    + "' with clip '" + still.getName()
                                    + "' on track '" + track.getName() + "'");
                }
            }
        }

        // Order the shift operations to avoid transient overlaps during
        // execute(): when shifting right, process right-to-left so each clip
        // moves into empty space; when shifting left, process left-to-right
        // for the same reason. Undo runs in reverse insertion order, which
        // yields the correct restoration ordering automatically.
        List<AudioClip> ordered = new ArrayList<>(toShift);
        if (shift > 0) {
            ordered.sort((a, b) -> Double.compare(b.getStartBeat(), a.getStartBeat()));
        } else {
            ordered.sort((a, b) -> Double.compare(a.getStartBeat(), b.getStartBeat()));
        }

        // Capture start beats at build time so that the shift delta is a
        // fixed quantity (not re-evaluated at execute() time after the primary
        // action has already mutated the track). The MoveClipAction snapshots
        // the "previous" start beat when execute() runs, so undo correctly
        // reverses the stored new position.
        Map<AudioClip, Double> originalStarts = new HashMap<>();
        for (AudioClip c : ordered) {
            originalStarts.put(c, c.getStartBeat());
        }
        for (AudioClip c : ordered) {
            actions.add(new MoveClipAction(c, originalStarts.get(c) + shift));
        }
    }
}
