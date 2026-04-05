package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.mastering.CrossfadeCurve;

import java.util.*;

/**
 * Manages crossfades between overlapping audio clips on a track.
 *
 * <p>When two clips on the same track overlap, the {@code CrossfadeManager}
 * detects the overlap and creates a {@link ClipCrossfade} so the transition
 * between the clips is a smooth blend rather than an abrupt cut.</p>
 *
 * <p>A configurable {@linkplain #getDefaultCurveType() default curve type}
 * is applied to newly created crossfades. The default can be changed via
 * {@link #setDefaultCurveType(CrossfadeCurve)}.</p>
 */
public final class CrossfadeManager {

    /** The default crossfade curve type used for new crossfades. */
    private static final CrossfadeCurve DEFAULT_CURVE_TYPE = CrossfadeCurve.LINEAR;

    private final List<ClipCrossfade> crossfades = new ArrayList<>();
    private CrossfadeCurve defaultCurveType = DEFAULT_CURVE_TYPE;

    /** Creates a new {@code CrossfadeManager}. */
    public CrossfadeManager() {
    }

    /**
     * Returns the default crossfade curve type applied to newly created crossfades.
     *
     * @return the default curve type (never {@code null})
     */
    public CrossfadeCurve getDefaultCurveType() {
        return defaultCurveType;
    }

    /**
     * Sets the default crossfade curve type for new crossfades.
     *
     * @param curveType the default curve type (must not be {@code null})
     */
    public void setDefaultCurveType(CrossfadeCurve curveType) {
        this.defaultCurveType = Objects.requireNonNull(curveType,
                "curveType must not be null");
    }

    /**
     * Returns an unmodifiable view of all crossfades managed by this instance.
     *
     * @return the list of crossfades
     */
    public List<ClipCrossfade> getCrossfades() {
        return Collections.unmodifiableList(crossfades);
    }

    /**
     * Adds a crossfade to this manager.
     *
     * @param crossfade the crossfade to add (must not be {@code null})
     */
    public void addCrossfade(ClipCrossfade crossfade) {
        Objects.requireNonNull(crossfade, "crossfade must not be null");
        crossfades.add(crossfade);
    }

    /**
     * Removes a crossfade from this manager.
     *
     * @param crossfade the crossfade to remove
     * @return {@code true} if the crossfade was removed
     */
    public boolean removeCrossfade(ClipCrossfade crossfade) {
        return crossfades.remove(crossfade);
    }

    /**
     * Finds the crossfade between two specific clips, if one exists.
     *
     * @param clipA the first clip
     * @param clipB the second clip
     * @return the crossfade, or empty if none exists
     */
    public Optional<ClipCrossfade> findCrossfade(AudioClip clipA, AudioClip clipB) {
        for (ClipCrossfade crossfade : crossfades) {
            if ((crossfade.getOutgoingClip() == clipA && crossfade.getIncomingClip() == clipB)
                    || (crossfade.getOutgoingClip() == clipB && crossfade.getIncomingClip() == clipA)) {
                return Optional.of(crossfade);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns all crossfades that involve the given clip.
     *
     * @param clip the clip to search for
     * @return a list of crossfades involving the clip (may be empty)
     */
    public List<ClipCrossfade> findCrossfadesForClip(AudioClip clip) {
        List<ClipCrossfade> result = new ArrayList<>();
        for (ClipCrossfade crossfade : crossfades) {
            if (crossfade.involvesClip(clip)) {
                result.add(crossfade);
            }
        }
        return result;
    }

    /**
     * Detects overlapping clips on the given track and creates crossfades
     * for any new overlaps. Existing crossfades for pairs that still overlap
     * are retained; crossfades for pairs that no longer overlap are removed.
     *
     * <p>Clips are considered overlapping when one clip starts before another
     * clip ends and vice versa, i.e. their timeline regions intersect.</p>
     *
     * @param track the track whose clips to scan for overlaps
     * @return the list of all current crossfades after detection
     * @throws NullPointerException if track is {@code null}
     */
    public List<ClipCrossfade> detectCrossfades(Track track) {
        Objects.requireNonNull(track, "track must not be null");
        List<AudioClip> clips = track.getClips();

        // Remove crossfades whose clips no longer overlap
        crossfades.removeIf(crossfade -> !crossfade.hasOverlap());

        // Detect new overlaps
        for (int i = 0; i < clips.size(); i++) {
            for (int j = i + 1; j < clips.size(); j++) {
                AudioClip clipA = clips.get(i);
                AudioClip clipB = clips.get(j);

                boolean overlaps = clipA.getStartBeat() < clipB.getEndBeat()
                        && clipB.getStartBeat() < clipA.getEndBeat();

                if (overlaps && findCrossfade(clipA, clipB).isEmpty()) {
                    // Determine which clip is outgoing and which is incoming
                    AudioClip outgoing;
                    AudioClip incoming;
                    if (clipA.getStartBeat() <= clipB.getStartBeat()) {
                        outgoing = clipA;
                        incoming = clipB;
                    } else {
                        outgoing = clipB;
                        incoming = clipA;
                    }
                    ClipCrossfade crossfade = new ClipCrossfade(outgoing, incoming,
                            defaultCurveType);
                    crossfades.add(crossfade);
                }
            }
        }

        return Collections.unmodifiableList(new ArrayList<>(crossfades));
    }

    /** Removes all crossfades from this manager. */
    public void clear() {
        crossfades.clear();
    }
}
