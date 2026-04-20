package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.List;
import java.util.Objects;

/**
 * Destructive, undoable "Flatten takes" maintenance command.
 *
 * <p>Collapses a {@link TakeGroup} on a {@link Track} to its active take:
 * the active take's {@link AudioClip} is kept (and added to the track if not
 * already present); every other take's clip is removed from the track and the
 * take group is detached. Undoing the action restores the original clips,
 * their order, and the take group.</p>
 *
 * <p>The action is <strong>undoable</strong> in the in-memory project graph —
 * audio assets on disk are not deleted by {@link #execute()}, so undo can
 * re-link them. Permanent on-disk cleanup is the responsibility of a separate
 * garbage-collection pass over the project's recording directory.</p>
 */
public final class FlattenTakesAction implements UndoableAction {

    private final Track track;
    private final TakeGroup takeGroup;

    // Captured for undo:
    private List<AudioClip> previousClips;
    private TakeGroup previousTakeGroupInTrack;

    /**
     * Creates a flatten-takes action.
     *
     * @param track     the track that owns the {@code takeGroup}
     * @param takeGroup the take group to flatten (must be non-empty)
     */
    public FlattenTakesAction(Track track, TakeGroup takeGroup) {
        this.track = Objects.requireNonNull(track, "track must not be null");
        this.takeGroup = Objects.requireNonNull(takeGroup, "takeGroup must not be null");
        if (takeGroup.isEmpty()) {
            throw new IllegalArgumentException("takeGroup must not be empty");
        }
    }

    @Override
    public String description() {
        return "Flatten Takes";
    }

    @Override
    public void execute() {
        // Snapshot clips + existing take group for undo.
        previousClips = List.copyOf(track.getClips());
        previousTakeGroupInTrack = track.getTakeGroup(takeGroup.id());

        AudioClip activeClip = takeGroup.activeClip();

        // Remove every take's clip from the track...
        for (Take take : takeGroup.takes()) {
            track.removeClip(take.clip());
        }
        // ...then put the active clip back so the track has exactly the
        // flattened clip in place of the stack.
        track.addClip(activeClip);

        // Detach the take group from the track.
        track.removeTakeGroup(takeGroup.id());
    }

    @Override
    public void undo() {
        if (previousClips == null) {
            return;
        }
        // Remove anything execute() added, then restore the snapshot clip list.
        for (AudioClip clip : List.copyOf(track.getClips())) {
            track.removeClip(clip);
        }
        for (AudioClip clip : previousClips) {
            track.addClip(clip);
        }
        if (previousTakeGroupInTrack != null) {
            track.putTakeGroup(previousTakeGroupInTrack);
        }
    }
}
