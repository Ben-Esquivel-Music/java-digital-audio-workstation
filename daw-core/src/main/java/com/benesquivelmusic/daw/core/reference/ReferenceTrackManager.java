package com.benesquivelmusic.daw.core.reference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Manages reference tracks for A/B comparison during mixing and mastering.
 *
 * <p>The manager maintains a collection of reference tracks with a single
 * active selection and an A/B toggle state. When the A/B toggle is active
 * (set to {@code B}), the active reference track's audio is played instead
 * of the project mix output. Reference tracks bypass the mixer effects chain
 * and are automatically level-matched based on integrated LUFS measurements.</p>
 *
 * <p>Reference tracks are excluded from export operations.</p>
 */
public final class ReferenceTrackManager {

    private static final double LUFS_FLOOR = -120.0;

    private final List<ReferenceTrack> referenceTracks = new ArrayList<>();
    private int activeIndex = -1;
    private boolean referenceActive;
    private double mixIntegratedLufs = LUFS_FLOOR;

    /**
     * Adds a reference track.
     *
     * @param referenceTrack the reference track to add
     * @throws NullPointerException if referenceTrack is {@code null}
     */
    public void addReferenceTrack(ReferenceTrack referenceTrack) {
        Objects.requireNonNull(referenceTrack, "referenceTrack must not be null");
        referenceTracks.add(referenceTrack);
        if (activeIndex < 0) {
            activeIndex = 0;
        }
    }

    /**
     * Removes a reference track.
     *
     * @param referenceTrack the reference track to remove
     * @return {@code true} if the track was removed
     */
    public boolean removeReferenceTrack(ReferenceTrack referenceTrack) {
        int index = referenceTracks.indexOf(referenceTrack);
        if (index < 0) {
            return false;
        }
        referenceTracks.remove(index);
        if (referenceTracks.isEmpty()) {
            activeIndex = -1;
            referenceActive = false;
        } else if (activeIndex >= referenceTracks.size()) {
            activeIndex = referenceTracks.size() - 1;
        }
        return true;
    }

    /**
     * Returns an unmodifiable view of the reference tracks.
     *
     * @return the list of reference tracks
     */
    public List<ReferenceTrack> getReferenceTracks() {
        return Collections.unmodifiableList(referenceTracks);
    }

    /**
     * Returns the number of reference tracks.
     *
     * @return reference track count
     */
    public int getReferenceTrackCount() {
        return referenceTracks.size();
    }

    /**
     * Returns the currently active reference track, or {@code null} if none
     * is selected.
     *
     * @return the active reference track, or {@code null}
     */
    public ReferenceTrack getActiveReferenceTrack() {
        if (activeIndex < 0 || activeIndex >= referenceTracks.size()) {
            return null;
        }
        return referenceTracks.get(activeIndex);
    }

    /**
     * Returns the index of the currently active reference track, or {@code -1}
     * if none is selected.
     *
     * @return the active index, or {@code -1}
     */
    public int getActiveIndex() {
        return activeIndex;
    }

    /**
     * Sets the active reference track by index.
     *
     * @param index the index of the reference track to activate
     * @throws IndexOutOfBoundsException if the index is out of range
     */
    public void setActiveIndex(int index) {
        if (index < 0 || index >= referenceTracks.size()) {
            throw new IndexOutOfBoundsException("index out of range: " + index);
        }
        this.activeIndex = index;
    }

    /**
     * Returns whether the A/B toggle is set to reference (B) mode.
     *
     * <p>When {@code true}, the active reference track's audio should be
     * played instead of the project mix output. When {@code false}, the
     * normal mix output is used (A mode).</p>
     *
     * @return {@code true} if reference playback is active (B mode)
     */
    public boolean isReferenceActive() {
        return referenceActive;
    }

    /**
     * Toggles the A/B comparison state.
     *
     * <p>If the toggle would activate reference mode but no reference tracks
     * exist, this method does nothing.</p>
     */
    public void toggleAB() {
        if (!referenceActive && referenceTracks.isEmpty()) {
            return;
        }
        referenceActive = !referenceActive;
    }

    /**
     * Sets the A/B toggle state directly.
     *
     * @param referenceActive {@code true} to activate reference mode (B),
     *                        {@code false} for mix mode (A)
     */
    public void setReferenceActive(boolean referenceActive) {
        if (referenceActive && referenceTracks.isEmpty()) {
            return;
        }
        this.referenceActive = referenceActive;
    }

    /**
     * Sets the integrated LUFS measurement for the project mix output,
     * used for automatic level matching of reference tracks.
     *
     * @param mixIntegratedLufs the mix integrated LUFS
     */
    public void setMixIntegratedLufs(double mixIntegratedLufs) {
        this.mixIntegratedLufs = mixIntegratedLufs;
    }

    /**
     * Returns the integrated LUFS measurement of the project mix output.
     *
     * @return the mix integrated LUFS
     */
    public double getMixIntegratedLufs() {
        return mixIntegratedLufs;
    }

    /**
     * Calculates and applies the gain offset to the active reference track
     * so that its perceived loudness matches the project mix output.
     *
     * <p>The gain offset is computed as the difference between the mix
     * integrated LUFS and the reference track's integrated LUFS. This
     * ensures that when toggling A/B, loudness differences do not bias
     * the comparison.</p>
     *
     * <p>If no active reference track exists, or either LUFS measurement
     * is at the floor value (−120 LUFS), this method does nothing.</p>
     */
    public void levelMatchActiveReference() {
        ReferenceTrack active = getActiveReferenceTrack();
        if (active == null) {
            return;
        }
        double refLufs = active.getIntegratedLufs();
        if (mixIntegratedLufs <= LUFS_FLOOR || refLufs <= LUFS_FLOOR) {
            return;
        }
        active.setGainOffsetDb(mixIntegratedLufs - refLufs);
    }

    /**
     * Calculates and applies level matching to all reference tracks.
     */
    public void levelMatchAllReferences() {
        for (ReferenceTrack ref : referenceTracks) {
            double refLufs = ref.getIntegratedLufs();
            if (mixIntegratedLufs > LUFS_FLOOR && refLufs > LUFS_FLOOR) {
                ref.setGainOffsetDb(mixIntegratedLufs - refLufs);
            }
        }
    }

    /**
     * Returns whether the reference track manager has any reference tracks.
     *
     * @return {@code true} if at least one reference track exists
     */
    public boolean hasReferenceTracks() {
        return !referenceTracks.isEmpty();
    }
}
