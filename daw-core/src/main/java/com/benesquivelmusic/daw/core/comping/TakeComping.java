package com.benesquivelmusic.daw.core.comping;

import com.benesquivelmusic.daw.core.audio.AudioClip;

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
 */
public final class TakeComping {

    private final List<TakeLane> takeLanes = new ArrayList<>();
    private final List<CompRegion> compRegions = new ArrayList<>();

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
     * Returns the comp regions that reference the given take lane index,
     * representing the "active" (highlighted) portions of that take.
     *
     * @param takeIndex the take lane index
     * @return the list of comp regions for that take
     */
    public List<CompRegion> getCompRegionsForTake(int takeIndex) {
        List<CompRegion> result = new ArrayList<>();
        for (CompRegion region : compRegions) {
            if (region.takeIndex() == takeIndex) {
                result.add(region);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Compiles the selected comp regions into a list of audio clips
     * suitable for placement on the main track lane.
     *
     * <p>For each comp region, the corresponding audio clip from the
     * referenced take lane is located, and a new clip is created that
     * covers exactly the comp region's beat range. The returned clips
     * represent the composite and can replace the track's existing clips.</p>
     *
     * @return an unmodifiable list of compiled audio clips
     */
    public List<AudioClip> compile() {
        List<AudioClip> compiled = new ArrayList<>();
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
                    double duration = clipEnd - clipStart;
                    if (duration <= 0) {
                        continue;
                    }
                    double sourceOffset = takeClip.getSourceOffsetBeats()
                            + (clipStart - takeClip.getStartBeat());
                    AudioClip compClip = new AudioClip(
                            takeClip.getName(),
                            clipStart,
                            duration,
                            takeClip.getSourceFilePath());
                    compClip.setSourceOffsetBeats(sourceOffset);
                    compClip.setGainDb(takeClip.getGainDb());
                    compClip.setAudioData(takeClip.getAudioData());
                    compiled.add(compClip);
                }
            }
        }
        return Collections.unmodifiableList(compiled);
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
