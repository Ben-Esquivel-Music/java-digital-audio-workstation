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
 * soloed lane whole, each referencing the take's segment files), removes
 * from the track exactly the clips the comp currently occupies
 * ({@link TakeComping#getCompClips()}) and adds the new ones. Clips that are
 * not comp output — an earlier recording, an import — are never touched.
 * The take stack itself is preserved, so the user can go on comping; every
 * swipe runs one of these ({@link SetCompRegionAction}).</p>
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
 * the selection before calling it.</p>
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
     * compile of the live comping state: the clips in
     * {@link TakeComping#getCompClips()} leave the track, the compile's
     * clips join it, and the comping records the new occupancy.
     */
    private void recompile() {
        List<AudioClip> occupied = List.copyOf(comping.getCompClips());
        compiledClips = List.copyOf(comping.compile());
        for (AudioClip previous : occupied) {
            track.removeClip(previous);
        }
        for (AudioClip compiled : compiledClips) {
            track.addClip(compiled);
        }
        comping.setCompClips(compiledClips);
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
