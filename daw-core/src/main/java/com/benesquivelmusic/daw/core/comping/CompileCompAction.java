package com.benesquivelmusic.daw.core.comping;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.List;
import java.util.Objects;

/**
 * Undoable "Compile comp" command: puts the compiled comp of a {@link Track}'s
 * {@link TakeComping} on the main lane (story 328, book §3.5).
 *
 * <p>{@link #execute()} compiles the current comp selection
 * ({@link TakeComping#compile()} — one clip per selected lane range, or the
 * soloed lane whole, each referencing the take's segment files and tagged
 * as the comping's output), removes from the track every clip that is the
 * comping's output now ({@link TakeComping#compOutputOn(Track)}) and adds
 * the new ones. Occupancy is read from the live track, not remembered: comp
 * output a clip edit kept as comp output — both halves of a Scissors split,
 * the remnants of a paste-over or crop, a glue of two halves — is replaced
 * as a whole, and so is comp output the user trimmed or moved on the main
 * lane (PR #984 review). Clips that are not comp output — an earlier
 * recording, an import, a duplicate or paste of comp output — are never
 * touched. The take stack itself is preserved, so the user can go on
 * comping; every swipe runs one of these ({@link SetCompRegionAction}).</p>
 *
 * <p>{@link #undo()} recompiles the same way from the LIVE comping state —
 * it does not put back a snapshot of the clips the previous compile had.
 * The main lane is thereby always exactly what the comping state says it
 * is: an audition ({@code CompToolHandler.altClickLane}, a solo that is not
 * an edit and so not in the undo history) recompiles between a swipe and
 * its undo without leaving its clips orphaned or doubled, because the undo
 * removes whatever the comp occupies now and compiles afresh from the
 * selection the composing action restored (story 328 review). The action
 * itself changes no selection, so its own undo is a recompile of the same
 * state; it is meaningful as the second half of {@link SetCompRegionAction}
 * and of the recording coordinator's "Record Audio" entry, which restore
 * the selection — and end any audition ({@link TakeComping#endAuditions()})
 * — before calling it. Called on its own, as the audition gesture does, it
 * compiles whatever the comping state is, a soloed lane included.</p>
 *
 * <p>Every compile is a new pass of the comping, and the clips of every
 * earlier pass are superseded output that never re-enters the track
 * ({@link TakeComping#isSupersededOutput}, {@link Track#addClip}). That
 * keeps the invariant against the undo of a clip edit, too: an erase, a
 * cut, a paste-over or crop, a move to another track, undone after a
 * recompile, cannot put back a clip this compile replaced and so leave the
 * comp playing twice (PR #984 review).</p>
 *
 * <p>A recompile publishes no {@code ClipEvent}: neither {@link #execute()}
 * nor {@link #undo()} announces the clips it removes from the track or the
 * ones it adds ({@link Track#removeClip} and {@link Track#addClip} publish
 * nothing themselves). So a subscriber cannot pair each {@code ClipEvent.Added}
 * for a track with a later {@code ClipEvent.Removed} for it across a
 * recompile: a comp clip a clip edit announced as added to its track can
 * leave that track in a recompile without a removal ever being published.</p>
 */
public final class CompileCompAction implements UndoableAction {

    private final Track track;
    private final TakeComping comping;

    private List<AudioClip> compiledClips = List.of();

    /**
     * Creates a new compile-comp action for {@code track}'s take comping.
     *
     * @param track the track whose take comping should be compiled
     */
    public CompileCompAction(Track track) {
        this.track = Objects.requireNonNull(track, "track must not be null");
        this.comping = track.getTakeComping();
    }

    @Override
    public String description() {
        return "Compile Comp";
    }

    @Override
    public void execute() {
        recompile();
    }

    @Override
    public void undo() {
        recompile();
    }

    /**
     * Replaces the clips the comp occupies on the main lane with a fresh
     * compile of the live comping state: every clip of the track that is
     * the comping's output ({@link TakeComping#compOutputOn(Track)}), of any
     * pass, leaves the track, and the compile's clips, tagged with its new
     * pass, join it.
     */
    private void recompile() {
        List<AudioClip> occupied = comping.compOutputOn(track);
        compiledClips = List.copyOf(comping.compile());
        for (AudioClip previous : occupied) {
            track.removeClip(previous);
        }
        for (AudioClip compiled : compiledClips) {
            track.addClip(compiled);
        }
    }

    /**
     * Returns the clips the last {@link #execute()} or {@link #undo()} put
     * on the main lane; empty before either, or when nothing is selected.
     *
     * @return the compiled clips (unmodifiable)
     */
    public List<AudioClip> getCompiledClips() {
        return compiledClips;
    }
}
