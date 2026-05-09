package com.benesquivelmusic.daw.core.comping;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Undoable "Compile comp" maintenance command.
 *
 * <p>Renders the current comp regions on a {@link Track}'s {@link TakeComping}
 * stack into a single composite {@link AudioClip} and replaces any clips on
 * the main track lane that fall inside the composite's beat range with the
 * compiled clip. The take stack itself is preserved (collapsed beneath the
 * main lane) so the user can return to comping later or via {@link #undo()}.</p>
 *
 * <p>Undo restores the prior clips on the main lane exactly as they were and
 * (because the take stack was never destroyed) leaves the take comping
 * untouched.</p>
 */
public final class CompileCompAction implements UndoableAction {

    private final Track track;
    private final CompManager compManager;
    private final double sampleRate;
    private final double tempoBpm;

    private List<AudioClip> previousMainLaneClips;
    private AudioClip compiledClip;

    /**
     * Creates a new compile-comp action.
     *
     * @param track       the track whose take comping should be compiled
     * @param compManager the comp manager used to render the composite
     * @param sampleRate  the sample-rate to render at, in Hz
     * @param tempoBpm    the tempo to convert beats &harr; samples
     */
    public CompileCompAction(Track track,
                             CompManager compManager,
                             double sampleRate,
                             double tempoBpm) {
        this.track = Objects.requireNonNull(track, "track must not be null");
        this.compManager = Objects.requireNonNull(compManager, "compManager must not be null");
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be > 0: " + sampleRate);
        }
        if (tempoBpm <= 0) {
            throw new IllegalArgumentException("tempoBpm must be > 0: " + tempoBpm);
        }
        this.sampleRate = sampleRate;
        this.tempoBpm = tempoBpm;
    }

    @Override
    public String description() {
        return "Compile Comp";
    }

    @Override
    public void execute() {
        compiledClip = compManager.compileToClip(sampleRate, tempoBpm);
        if (compiledClip == null) {
            previousMainLaneClips = null;
            return;
        }
        previousMainLaneClips = List.copyOf(track.getClips());
        // Remove any existing main-lane clips that fall inside the composite
        // beat range — the composite replaces them. Clips outside the range
        // are left intact.
        double start = compiledClip.getStartBeat();
        double end = compiledClip.getEndBeat();
        for (AudioClip existing : new ArrayList<>(track.getClips())) {
            if (existing.getStartBeat() < end && existing.getEndBeat() > start) {
                track.removeClip(existing);
            }
        }
        track.addClip(compiledClip);
    }

    @Override
    public void undo() {
        if (previousMainLaneClips == null) {
            return;
        }
        for (AudioClip clip : new ArrayList<>(track.getClips())) {
            track.removeClip(clip);
        }
        for (AudioClip clip : previousMainLaneClips) {
            track.addClip(clip);
        }
        compiledClip = null;
    }

    /** Returns the compiled clip produced by {@link #execute()}, or {@code null}. */
    public AudioClip getCompiledClip() {
        return compiledClip;
    }
}
