package com.benesquivelmusic.daw.core.comping;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.CompOutputOwner;
import com.benesquivelmusic.daw.core.track.Track;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Manages multi-take comping for a single track.
 *
 * <p>During recording sessions, musicians record multiple takes of the same
 * section. This class stores the stacked take lanes and the user's comp
 * selections, and compiles the selected regions into composite clips on
 * the main track lane.</p>
 *
 * <p>Key concepts:</p>
 * <ul>
 *   <li><strong>Take Lane</strong> — A {@link TakeLane} holding the audio
 *       clips from one recording pass.</li>
 *   <li><strong>Comp Region</strong> — A {@link CompRegion} identifying a
 *       beat range on a specific take that should be included in the comp.</li>
 *   <li><strong>Compile</strong> — The process of assembling the selected
 *       comp regions into a flat list of {@link AudioClip}s for the main
 *       track lane.</li>
 * </ul>
 *
 * <p>This is the one take model (Recording Reliability book §3.5, story
 * 328): loop-record ingests each lap as a lane ({@code RecordingPipeline}),
 * the COMP tool selects across the lanes, and the compiled output is what
 * the main lane plays. Every {@link #compile()} is a new <em>pass</em>, and
 * every clip it produces is tagged with that pass as its
 * {@link CompOutputOwner}; the main lane's comp output is whatever clips of
 * the live track carry a pass of this comping ({@link #isCompOutput},
 * {@link #compOutputOn(Track)}) — not a remembered list. So the output
 * stays comp output through clip edits that keep the tag — a Scissors split
 * gives it to both halves, a paste-over or crop to the remnants it leaves
 * ({@link AudioClip#splitAt}, {@link AudioClip#trimmedCopy}) — and a
 * re-compile ({@link CompileCompAction}) replaces all of it, of every pass,
 * and nothing else on the track: a duplicate or paste of comp output, an
 * earlier recording, an import is a user clip and is never touched. A comp
 * clip the user trimmed or deleted is replaced by the next compile as well
 * — the main lane is what the comping state says it is. A swipe's compile
 * is undoable; an audition's (the COMP tool's Alt-click, or the main-lane
 * click that ends it) is not, so it discards such an edit with no undo
 * entry (future story 353). A comp clip moved to another track keeps its
 * tag, which is inert there: only this comping's own track scans for its
 * output ({@link #compOutputOn(Track)}) or refuses it ({@link Track#addClip}),
 * and {@link #isCompOutput} holds only for this comping's passes, so on
 * another track the clip behaves as a user clip — a swipe there never
 * touches it. Moved back before a recompile here, it is this comping's
 * output again and the next swipe replaces it; a move clears the tag only
 * when the destination's comping holds the clip as superseded output
 * ({@code CrossTrackMoveAction}, {@code GroupMoveClipsAction}).</p>
 *
 * <p><strong>Superseded output never re-enters its track</strong> (PR #984
 * review). Output of an earlier pass ({@link #isSupersededOutput}) is what a
 * later compile replaced, and the later compile already represents the
 * comp; {@link Track#addClip} refuses it. So an undo that puts back a clip
 * snapshot — of an erase, a cut, a paste-over, a crop, a move to another
 * track — after a swipe, a swipe's undo or an audition recompiled the main
 * lane cannot leave the comp playing twice. That is the one choke point
 * every such undo and redo goes through; no action needs a guard of its
 * own.</p>
 *
 * <p><strong>Every comp clip on this comping's track is output of the
 * current pass</strong>, or derived from such a clip by an edit that keeps
 * the tag. In production only {@link CompileCompAction} advances the pass
 * — {@link #compile()} is package-private — and it removes every clip of the
 * track that is this comping's output, of any pass, before it adds the new
 * pass's clips; from then on {@link Track#addClip} keeps the earlier
 * passes' clips off the track. That is why an edit that adds a clip
 * derived from one on the track — the second half of a Scissors split, a
 * glue of two halves ({@code SplitClipAction}, {@code GlueClipsAction}) —
 * or a fresh clip ({@code AddClipAction}) is never refused, and announces
 * the clip without checking {@link Track#addClip}'s result.</p>
 *
 * <p><strong>Auditions.</strong> A soloed lane ({@link TakeLane#isSoloed()},
 * the COMP tool's Alt-click) makes {@link #compile()} play that lane whole
 * instead of the selection. An audition is a monitoring state, not an edit:
 * no undo or redo replays it, and every undoable action that recompiles
 * ends it first ({@link #endAuditions()}).</p>
 */
public final class TakeComping {

    private final List<TakeLane> takeLanes = new ArrayList<>();
    private final List<CompRegion> compRegions = new ArrayList<>();
    /** The pass the last {@link #compile()} tagged its clips with. */
    private CompPass currentPass = new CompPass(this);

    /**
     * One {@link #compile()} of a comping: the owner its clips carry. Clips
     * derived from them by edits that keep the tag (a split's halves, a
     * remnant, a glue of halves) share the pass, so they stand or fall with
     * it.
     */
    private static final class CompPass implements CompOutputOwner {

        private final TakeComping comping;

        private CompPass(TakeComping comping) {
            this.comping = comping;
        }
    }

    /**
     * Adds a new take lane.
     *
     * @param lane the take lane to add
     * @throws NullPointerException if lane is {@code null}
     */
    public void addTakeLane(TakeLane lane) {
        Objects.requireNonNull(lane, "lane must not be null");
        takeLanes.add(lane);
    }

    /**
     * Removes a take lane and any comp regions that reference it.
     *
     * @param lane the take lane to remove
     * @return {@code true} if the lane was removed
     */
    public boolean removeTakeLane(TakeLane lane) {
        int index = takeLanes.indexOf(lane);
        if (index < 0) {
            return false;
        }
        takeLanes.remove(index);
        // Remove comp regions that reference the removed lane and adjust
        // indices of regions referencing higher lanes
        List<CompRegion> adjusted = new ArrayList<>();
        for (CompRegion region : compRegions) {
            if (region.takeIndex() == index) {
                continue; // drop regions from removed lane
            }
            if (region.takeIndex() > index) {
                adjusted.add(new CompRegion(
                        region.takeIndex() - 1,
                        region.startBeat(),
                        region.durationBeats()));
            } else {
                adjusted.add(region);
            }
        }
        compRegions.clear();
        compRegions.addAll(adjusted);
        return true;
    }

    /**
     * Returns an unmodifiable view of the take lanes.
     *
     * @return the list of take lanes
     */
    public List<TakeLane> getTakeLanes() {
        return Collections.unmodifiableList(takeLanes);
    }

    /**
     * Returns the number of take lanes.
     *
     * @return the take lane count
     */
    public int getTakeLaneCount() {
        return takeLanes.size();
    }

    /**
     * Returns the take lane at the given index.
     *
     * @param index the zero-based lane index
     * @return the take lane
     * @throws IndexOutOfBoundsException if the index is out of range
     */
    public TakeLane getTakeLane(int index) {
        return takeLanes.get(index);
    }

    /**
     * Adds a comp region, removing any existing regions that overlap with
     * it in the same beat range (regardless of which take they belong to).
     *
     * <p>This implements the "swipe comping" behavior where selecting a
     * region on one take automatically deselects overlapping regions on
     * other takes.</p>
     *
     * @param region the comp region to add
     * @throws NullPointerException     if region is {@code null}
     * @throws IllegalArgumentException if the take index is out of range
     */
    public void addCompRegion(CompRegion region) {
        Objects.requireNonNull(region, "region must not be null");
        if (region.takeIndex() >= takeLanes.size()) {
            throw new IllegalArgumentException(
                    "takeIndex out of range: " + region.takeIndex()
                            + " (lanes: " + takeLanes.size() + ")");
        }
        // Remove overlapping regions
        compRegions.removeIf(existing ->
                existing.overlaps(region.startBeat(), region.endBeat()));
        compRegions.add(region);
    }

    /**
     * Removes a specific comp region.
     *
     * @param region the region to remove
     * @return {@code true} if the region was removed
     */
    public boolean removeCompRegion(CompRegion region) {
        return compRegions.remove(region);
    }

    /**
     * Replaces all comp regions with the given list.
     *
     * @param regions the new comp regions
     */
    public void setCompRegions(List<CompRegion> regions) {
        Objects.requireNonNull(regions, "regions must not be null");
        compRegions.clear();
        compRegions.addAll(regions);
    }

    /**
     * Returns an unmodifiable view of the current comp regions.
     *
     * @return the list of comp regions
     */
    public List<CompRegion> getCompRegions() {
        return Collections.unmodifiableList(compRegions);
    }

    /**
     * Clears all comp regions.
     */
    public void clearCompRegions() {
        compRegions.clear();
    }

    /**
     * Compiles the selected comp regions into a list of audio clips
     * suitable for placement on the main track lane.
     *
     * <p>For each comp region, the corresponding audio clip from the
     * referenced take lane is located, and a new clip is created that
     * covers exactly the comp region's beat range. The returned clips
     * represent the composite and can replace the track's existing clips.
     * Each compile is a new pass, and each clip is tagged with it as this
     * comping's output ({@link #isCompOutput}); every clip of an earlier
     * pass is superseded from then on ({@link #isSupersededOutput}).</p>
     *
     * <p>Package-private: a pass supersedes the clips of the previous one, so
     * its only production caller is {@link CompileCompAction}, which replaces
     * the comp's output on the track with the clips this returns. A call that
     * did not would leave the live output on the track superseded — refused
     * by {@link Track#addClip} if an undo ever tried to put it back.</p>
     *
     * @return an unmodifiable list of compiled audio clips
     */
    List<AudioClip> compile() {
        currentPass = new CompPass(this);
        List<AudioClip> compiled = new ArrayList<>();
        for (TakeLane lane : takeLanes) {
            if (lane.isSoloed()) {
                // Audition (Alt-click): the soloed lane plays whole, in
                // place of the composite, until the main lane is clicked.
                for (AudioClip takeClip : lane.getClips()) {
                    compiled.add(slice(takeClip, takeClip.getStartBeat(), takeClip.getEndBeat()));
                }
                return Collections.unmodifiableList(compiled);
            }
        }
        for (CompRegion region : compRegions) {
            if (region.takeIndex() >= takeLanes.size()) {
                continue;
            }
            TakeLane lane = takeLanes.get(region.takeIndex());
            for (AudioClip takeClip : lane.getClips()) {
                // Check if the take clip covers the comp region
                if (takeClip.getStartBeat() < region.endBeat()
                        && takeClip.getEndBeat() > region.startBeat()) {
                    // Create a new clip covering the comp region
                    double clipStart = Math.max(region.startBeat(), takeClip.getStartBeat());
                    double clipEnd = Math.min(region.endBeat(), takeClip.getEndBeat());
                    if (clipEnd - clipStart <= 0) {
                        continue;
                    }
                    compiled.add(slice(takeClip, clipStart, clipEnd));
                }
            }
        }
        return Collections.unmodifiableList(compiled);
    }

    /**
     * A main-lane clip playing {@code [clipStart, clipEnd)} of {@code takeClip}:
     * it references the take's segment files (all of them — a take that
     * rotated spans several) at the matching source offset and shares the
     * take's audio array, so nothing is copied or invented. It is output of
     * this comping's current pass.
     */
    private AudioClip slice(AudioClip takeClip, double clipStart, double clipEnd) {
        AudioClip compClip = new AudioClip(
                takeClip.getName(),
                clipStart,
                clipEnd - clipStart,
                takeClip.getSourceFilePath());
        compClip.setSourceSegmentPaths(takeClip.getSourceSegmentPaths());
        compClip.setSourceOffsetBeats(takeClip.getSourceOffsetBeats() + (clipStart - takeClip.getStartBeat()));
        compClip.setGainDb(takeClip.getGainDb());
        compClip.setAudioData(takeClip.getAudioData());
        compClip.setSourceRateMetadata(takeClip.getSourceRateMetadata());
        compClip.setCompOutputOwner(currentPass);
        return compClip;
    }

    /**
     * Returns whether {@code clip} is output of this comping — a clip
     * {@link #compile()} produced, of any pass, or one an edit derived from
     * it that kept its tag ({@link AudioClip#splitAt},
     * {@link AudioClip#trimmedCopy}, a glue of two such clips). This is the
     * one owner predicate: a recompile replaces every clip of the track it
     * holds for ({@link #compOutputOn(Track)}).
     *
     * @param clip the clip to test; not {@code null}
     * @return {@code true} if the clip is this comping's output
     */
    public boolean isCompOutput(AudioClip clip) {
        Objects.requireNonNull(clip, "clip must not be null");
        return clip.compOutputOwner().orElse(null) instanceof CompPass pass && pass.comping == this;
    }

    /**
     * Returns whether {@code clip} is this comping's output of an earlier
     * pass than the last {@link #compile()}: output that compile replaced.
     * Such a clip never re-enters this comping's track
     * ({@link Track#addClip}) — the later compile already represents the
     * comp, so putting it back would play that range twice.
     *
     * @param clip the clip to test; not {@code null}
     * @return {@code true} if the clip is superseded output of this comping
     */
    public boolean isSupersededOutput(AudioClip clip) {
        return isCompOutput(clip) && !clip.isCompOutputOf(currentPass);
    }

    /**
     * Returns the clips on {@code track} that are this comping's output, in
     * the track's order: what the main lane's comp occupies now, after
     * whatever edits were made to it. {@link CompileCompAction} replaces
     * exactly these.
     *
     * @param track the track to scan — this comping's own track
     * @return the comp output on the track (unmodifiable; possibly empty)
     */
    public List<AudioClip> compOutputOn(Track track) {
        Objects.requireNonNull(track, "track must not be null");
        List<AudioClip> output = new ArrayList<>();
        for (AudioClip clip : track.getClips()) {
            if (isCompOutput(clip)) {
                output.add(clip);
            }
        }
        return Collections.unmodifiableList(output);
    }

    /**
     * Ends every lane audition: clears the solo of every take lane, so the
     * next {@link #compile()} plays the comp selection. Every undoable
     * action that recompiles calls this first — an audition is a monitoring
     * state, never an edit, so no execute, undo or redo may present one in
     * place of the selection it restores.
     *
     * @return whether a lane was soloed
     */
    public boolean endAuditions() {
        boolean auditioning = false;
        for (TakeLane lane : takeLanes) {
            auditioning |= lane.isSoloed();
            lane.setSoloed(false);
        }
        return auditioning;
    }

    /**
     * Returns whether comping is active (at least one take lane exists).
     *
     * @return {@code true} if take lanes are present
     */
    public boolean isActive() {
        return !takeLanes.isEmpty();
    }
}
