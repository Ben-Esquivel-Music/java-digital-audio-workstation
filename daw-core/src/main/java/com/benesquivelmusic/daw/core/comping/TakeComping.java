package com.benesquivelmusic.daw.core.comping;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.CompOutputOwner;
import com.benesquivelmusic.daw.core.track.Track;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
 * ends it first ({@link #endAuditions()}). Nor is it saved: every project
 * writer writes the main lane as it plays once auditions end
 * ({@link #clipsAsSaved(Track)}, built on the side-effect-free
 * {@link #compileSelection()}), so a save, an autosave checkpoint, a
 * snapshot or an archive taken mid-audition holds the selected comp, and
 * the audition keeps playing untouched (PR #984 review).</p>
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
        return materialize(planSlices(true), currentPass);
    }

    /**
     * Compiles the comp selection as it would play with every audition
     * ended — the comp regions' slices, ignoring any lane solo — as
     * detached clips: what the main lane's comp output is <em>as saved</em>
     * ({@link #clipsAsSaved(Track)}).
     *
     * <p>Side-effect free, unlike {@link #compile()}: it does not advance the
     * pass, so the clips on the track stay current output and nothing an
     * undo puts back is refused because of it; the clips it returns carry no
     * {@link CompOutputOwner} at all, so they can never supersede or be
     * mistaken for live output; and no lane, solo, region or track is
     * touched. It reads the take lanes, comp regions and lane clips through
     * snapshots ({@link #snapshot(List)}), so a project writer may call it
     * off the FX thread (the autosave checkpoint) without a
     * {@link java.util.ConcurrentModificationException}.</p>
     *
     * @return the selection's slices, in comp-region order (unmodifiable;
     *         empty when nothing is selected)
     */
    public List<AudioClip> compileSelection() {
        return materialize(planSlices(false), null);
    }

    /**
     * Returns whether a lane is soloed — the COMP tool's Alt-click audition
     * is playing on the main lane in place of the comp selection. Read-only.
     *
     * @return {@code true} if any take lane is soloed
     */
    public boolean isAuditioning() {
        for (TakeLane lane : snapshot(takeLanes)) {
            if (lane.isSoloed()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the clips a project writer saves for {@code track}, this
     * comping's own track: the main lane as it plays once auditions end.
     *
     * <p>Not auditioning, that is the track's clips verbatim. During an
     * audition the main lane plays the soloed lane, which is a monitoring
     * state, not the user's comp (story 328; PR #984 review): the saved
     * clips are then the track's clips that are not this comping's output
     * ({@link #isCompOutput} — user clips, wherever they lie, the comp range
     * included) in track order, followed by the {@linkplain #compileSelection()
     * selection compile} — exactly what ending the audition puts on the main
     * lane. Nothing live is touched: the track, its clips, the solos, the
     * pass and the regions are as they were.</p>
     *
     * @param track this comping's track
     * @return the clips to save (unmodifiable)
     * @throws IllegalArgumentException if {@code track} is not this comping's track
     */
    public List<AudioClip> clipsAsSaved(Track track) {
        requireOwnTrack(track);
        if (!isAuditioning()) {
            return track.getClips();
        }
        List<AudioClip> saved = userClipsOn(track);
        saved.addAll(compileSelection());
        return Collections.unmodifiableList(saved);
    }

    /**
     * Returns the live clips whose asset references are the ones the
     * {@linkplain #clipsAsSaved(Track) saved clips} carry — what a writer
     * that packages or rewrites asset references (an archive, a
     * consolidation) must walk for {@code track}. Not auditioning, that is
     * the track's clips. During an audition the saved comp clips are
     * detached slices that copy their take clip's source file and segment
     * list, so the holders are the track's non-comp clips and each take-lane
     * clip the selection slices (once each, in slice order): rewriting a
     * holder's reference is what the next {@link #clipsAsSaved(Track)}
     * writes, and the soloed lane — not saved — is not packaged.
     *
     * @param track this comping's track
     * @return the live reference holders (unmodifiable)
     * @throws IllegalArgumentException if {@code track} is not this comping's track
     */
    public List<AudioClip> referenceHoldersAsSaved(Track track) {
        requireOwnTrack(track);
        if (!isAuditioning()) {
            return track.getClips();
        }
        List<AudioClip> holders = userClipsOn(track);
        Set<AudioClip> sources = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Slice slice : planSlices(false)) {
            if (sources.add(slice.takeClip())) {
                holders.add(slice.takeClip());
            }
        }
        return Collections.unmodifiableList(holders);
    }

    /**
     * Returns whether the comp output on {@code track} saves the same as the
     * {@linkplain #compileSelection() selection compile}: the two hold the
     * same clips by every value a project writer saves — position, length,
     * source offset, source file and segments, gain, fades and their curves,
     * reverse, lock, stretch, pitch, gain envelope, name — regardless of
     * clip identity, order or pass tag ({@link ClipSaveValue}).
     *
     * <p>Unedited comp output always does — it is a compile of the same
     * selection. It differs once the user edited a comp clip on the main
     * lane (a fade, a trim, a split, a gain change): starting an audition
     * then changes what a save writes, because the saved comp becomes the
     * selection compile and the edit is discarded when the audition ends
     * (future story 353).</p>
     *
     * @param track this comping's track
     * @return {@code true} if a save would write the same comp clips either way
     * @throws IllegalArgumentException if {@code track} is not this comping's track
     */
    public boolean compOutputSavesAsSelection(Track track) {
        requireOwnTrack(track);
        return ClipSaveValue.sameSavedClips(compOutputOn(track), compileSelection());
    }

    /**
     * The take clip a compile slices and the beat range of the slice — the
     * plan both {@link #compile()} and {@link #compileSelection()} build
     * their clips from, and whose sources {@link #referenceHoldersAsSaved}
     * reports.
     */
    private record Slice(AudioClip takeClip, double start, double end) {
    }

    /**
     * Plans a compile from snapshots of the lanes, regions and lane clips.
     * With {@code honourSolo}, a soloed lane plays whole in place of the
     * selection (the audition); without it, only the comp regions count.
     */
    private List<Slice> planSlices(boolean honourSolo) {
        List<TakeLane> lanes = snapshot(takeLanes);
        List<Slice> plan = new ArrayList<>();
        if (honourSolo) {
            for (TakeLane lane : lanes) {
                if (lane.isSoloed()) {
                    // Audition (Alt-click): the soloed lane plays whole, in
                    // place of the composite, until the main lane is clicked.
                    for (AudioClip takeClip : snapshot(lane.getClips())) {
                        plan.add(new Slice(takeClip, takeClip.getStartBeat(), takeClip.getEndBeat()));
                    }
                    return plan;
                }
            }
        }
        for (CompRegion region : snapshot(compRegions)) {
            if (region.takeIndex() >= lanes.size()) {
                continue;
            }
            TakeLane lane = lanes.get(region.takeIndex());
            for (AudioClip takeClip : snapshot(lane.getClips())) {
                // Check if the take clip covers the comp region
                if (takeClip.getStartBeat() < region.endBeat()
                        && takeClip.getEndBeat() > region.startBeat()) {
                    // Create a new clip covering the comp region
                    double clipStart = Math.max(region.startBeat(), takeClip.getStartBeat());
                    double clipEnd = Math.min(region.endBeat(), takeClip.getEndBeat());
                    if (clipEnd - clipStart <= 0) {
                        continue;
                    }
                    plan.add(new Slice(takeClip, clipStart, clipEnd));
                }
            }
        }
        return plan;
    }

    private static List<AudioClip> materialize(List<Slice> plan, CompPass pass) {
        List<AudioClip> compiled = new ArrayList<>(plan.size());
        for (Slice slice : plan) {
            compiled.add(slice(slice.takeClip(), slice.start(), slice.end(), pass));
        }
        return Collections.unmodifiableList(compiled);
    }

    /**
     * A main-lane clip playing {@code [clipStart, clipEnd)} of {@code takeClip}:
     * it references the take's segment files (all of them — a take that
     * rotated spans several) at the matching source offset and shares the
     * take's audio array, so nothing is copied or invented. It is output of
     * {@code pass}, or of no pass at all when {@code pass} is {@code null}
     * (a detached {@link #compileSelection()} clip).
     */
    private static AudioClip slice(AudioClip takeClip, double clipStart, double clipEnd, CompPass pass) {
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
        compClip.setCompOutputOwner(pass);
        return compClip;
    }

    /** The track's clips that are not this comping's output, in track order (a fresh, mutable list). */
    private List<AudioClip> userClipsOn(Track track) {
        List<AudioClip> userClips = new ArrayList<>();
        for (AudioClip clip : snapshot(track.getClips())) {
            if (!isCompOutput(clip)) {
                userClips.add(clip);
            }
        }
        return userClips;
    }

    private void requireOwnTrack(Track track) {
        Objects.requireNonNull(track, "track must not be null");
        if (track.getTakeComping() != this) {
            throw new IllegalArgumentException("track " + track.getName() + " is not this comping's track");
        }
    }

    /**
     * A point-in-time copy of a live list, for a read that may run off the
     * FX thread (a project writer on the autosave checkpoint's virtual
     * thread). The take lanes, comp regions and lane and track clips are
     * plain {@link ArrayList}s mutated on the FX thread; iterating one
     * directly is fail-fast and can throw a
     * {@link java.util.ConcurrentModificationException} mid-write. The copy
     * goes through {@code toArray()} ({@link ArrayList#ArrayList(java.util.Collection)}),
     * which is not fail-fast, but it copies the backing array while a racing
     * insertion or removal is shifting it: a removal ({@code fastRemove}
     * shifts the tail left, then nulls the last slot) can show up as a
     * {@code null} slot or as an element seen twice, and an insertion that
     * shifts right can likewise show an element twice. Both artifacts are
     * dropped — {@code null}s, and every repeat of an instance after its
     * first occurrence (identity, order kept). That loses nothing real: a
     * take lane, a lane or track clip, or a comp region is one object in one
     * place, never the same instance twice in one of these lists — twice
     * would play or slice it twice, and {@link #removeTakeLane},
     * {@code Track.removeClip} and {@code TakeLane.removeClip} would remove
     * only the first — and {@link #addCompRegion} removes every region
     * overlapping the one it adds, an earlier add of the same instance
     * included. So a repeat can only be the race. A racing edit can still be half-seen —
     * an element the copy missed — the same consistency as an unsynchronized
     * read, but it never aborts the write. No lock is taken, so FX-thread
     * edits never wait.
     */
    private static <T> List<T> snapshot(List<T> live) {
        Set<T> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<T> copy = new ArrayList<>();
        for (T element : new ArrayList<>(live)) {
            if (element != null && seen.add(element)) {
                copy.add(element);
            }
        }
        return copy;
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
