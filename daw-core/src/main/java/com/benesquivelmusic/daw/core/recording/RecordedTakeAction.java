package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.comping.CompRegion;
import com.benesquivelmusic.daw.core.comping.CompileCompAction;
import com.benesquivelmusic.daw.core.comping.TakeComping;
import com.benesquivelmusic.daw.core.comping.TakeLane;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The undo-history entry of a published take (story 328, Recording
 * Reliability book §3.5): what {@link RecordingPipeline#completeStop()} put
 * on the armed tracks, and how to take it off and put it back.
 *
 * <p>A plain take added one recorded clip per track. A loop-record take
 * added its laps as take lanes of the track's one take model, selected the
 * last whole lap, and put the compiled comp on the main lane — and, in
 * doing so, deselected every earlier comp region the new selection
 * overlapped ({@link TakeComping#addCompRegion(CompRegion)}). The pipeline
 * remembers the selection each track had immediately before that
 * ({@link RecordingPipeline#getCompSelectionBeforeIngestion()}), so
 * {@link #undo()} removes the take's lanes, restores that selection and
 * recompiles: an earlier take's comp plays again exactly as it did before
 * Record was pressed (story 328 review). {@link #execute()} re-adds the
 * lanes, reinstates the selection the take left and recompiles; on its
 * first run — the pipeline has already applied the take — it finds
 * everything in place and changes nothing.</p>
 *
 * <p>The main lane is never restored from a snapshot of clips: every path
 * recompiles from the live comping state through {@link CompileCompAction},
 * so an audition (a lane solo, not an edit) between a take and its undo
 * cannot leave its clips orphaned or doubled.</p>
 */
public final class RecordedTakeAction implements UndoableAction {

    private final Map<Track, AudioClip> plainClips;
    private final Map<Track, List<TakeLane>> lanes;
    private final Map<Track, List<CompRegion>> selectionAfter;
    private final Map<Track, List<CompRegion>> selectionBefore;

    /**
     * Creates the entry of the take {@code pipeline} just published: its
     * recorded clips, its lanes, each lane track's comp selection as the
     * take left it, and the selection the track had before the take's laps
     * were ingested. Call once {@code completeStop()} has returned.
     *
     * @param pipeline the pipeline whose take was just published
     */
    public RecordedTakeAction(RecordingPipeline pipeline) {
        Objects.requireNonNull(pipeline, "pipeline must not be null");
        this.lanes = Map.copyOf(pipeline.getRecordedTakeLanes());
        Map<Track, AudioClip> plain = new LinkedHashMap<>();
        for (Map.Entry<Track, AudioClip> entry : pipeline.getRecordedClips().entrySet()) {
            // A loop-record take's recorded clip is comp output: the
            // recompile owns it, so it is not a plain clip of this entry.
            if (!lanes.containsKey(entry.getKey())) {
                plain.put(entry.getKey(), entry.getValue());
            }
        }
        this.plainClips = Map.copyOf(plain);
        Map<Track, List<CompRegion>> after = new LinkedHashMap<>();
        Map<Track, List<CompRegion>> before = new LinkedHashMap<>();
        Map<Track, List<CompRegion>> remembered = pipeline.getCompSelectionBeforeIngestion();
        for (Track track : lanes.keySet()) {
            after.put(track, List.copyOf(track.getTakeComping().getCompRegions()));
            before.put(track, List.copyOf(remembered.getOrDefault(track, List.of())));
        }
        this.selectionAfter = Map.copyOf(after);
        this.selectionBefore = Map.copyOf(before);
    }

    @Override
    public String description() {
        return "Record Audio";
    }

    @Override
    public void execute() {
        for (Map.Entry<Track, List<TakeLane>> entry : lanes.entrySet()) {
            Track track = entry.getKey();
            TakeComping comping = track.getTakeComping();
            boolean changed = false;
            for (TakeLane lane : entry.getValue()) {
                if (!comping.getTakeLanes().contains(lane)) {
                    comping.addTakeLane(lane);
                    changed = true;
                }
            }
            if (!comping.getCompRegions().equals(selectionAfter.get(track))) {
                comping.setCompRegions(selectionAfter.get(track));
                changed = true;
            }
            // The first run finds the take as the pipeline applied it, comp
            // clips included; only a redo has something to put back.
            if (changed) {
                new CompileCompAction(track).execute();
            }
        }
        for (Map.Entry<Track, AudioClip> entry : plainClips.entrySet()) {
            if (!entry.getKey().getClips().contains(entry.getValue())) {
                entry.getKey().addClip(entry.getValue());
            }
        }
    }

    @Override
    public void undo() {
        for (Map.Entry<Track, AudioClip> entry : plainClips.entrySet()) {
            entry.getKey().removeClip(entry.getValue());
        }
        for (Map.Entry<Track, List<TakeLane>> entry : lanes.entrySet()) {
            Track track = entry.getKey();
            TakeComping comping = track.getTakeComping();
            for (TakeLane lane : entry.getValue()) {
                comping.removeTakeLane(lane);
            }
            // The earlier takes' selection, as it was before this take
            // deselected what it overlapped; their comp plays again.
            comping.setCompRegions(selectionBefore.get(track));
            new CompileCompAction(track).execute();
        }
    }

    /** The take lanes this entry adds and removes, per track; empty for a plain take. */
    public Map<Track, List<TakeLane>> lanes() {
        return lanes;
    }
}
