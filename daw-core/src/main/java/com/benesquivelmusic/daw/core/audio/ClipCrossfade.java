package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.mastering.CrossfadeCurve;

import java.util.Objects;
import java.util.UUID;

/**
 * Represents a crossfade region between two overlapping audio clips on the
 * same track.
 *
 * <p>When two clips overlap on the timeline, a crossfade blends the outgoing
 * clip (being faded out) with the incoming clip (being faded in) so that the
 * transition is smooth rather than an abrupt cut.</p>
 *
 * <p>The crossfade region is defined by the overlap between the two clips:
 * it starts where the incoming clip begins and ends where the outgoing clip
 * ends. The {@link CrossfadeCurve} determines the shape of the gain
 * envelope applied during the transition.</p>
 */
public final class ClipCrossfade {

    private final String id;
    private final AudioClip outgoingClip;
    private final AudioClip incomingClip;
    private CrossfadeCurve curveType;

    /**
     * Creates a new crossfade between two overlapping clips.
     *
     * @param outgoingClip the clip being faded out (ends after the incoming clip starts)
     * @param incomingClip the clip being faded in (starts before the outgoing clip ends)
     * @param curveType    the crossfade curve type
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if the clips do not overlap
     */
    public ClipCrossfade(AudioClip outgoingClip, AudioClip incomingClip,
                         CrossfadeCurve curveType) {
        this.id = UUID.randomUUID().toString();
        this.outgoingClip = Objects.requireNonNull(outgoingClip, "outgoingClip must not be null");
        this.incomingClip = Objects.requireNonNull(incomingClip, "incomingClip must not be null");
        this.curveType = Objects.requireNonNull(curveType, "curveType must not be null");
        if (outgoingClip == incomingClip) {
            throw new IllegalArgumentException("outgoingClip and incomingClip must be different");
        }
        if (!hasOverlap()) {
            throw new IllegalArgumentException(
                    "clips do not overlap: outgoing ends at " + outgoingClip.getEndBeat()
                            + " but incoming starts at " + incomingClip.getStartBeat());
        }
    }

    /** Returns the unique identifier for this crossfade. */
    public String getId() {
        return id;
    }

    /** Returns the outgoing clip (the clip being faded out). */
    public AudioClip getOutgoingClip() {
        return outgoingClip;
    }

    /** Returns the incoming clip (the clip being faded in). */
    public AudioClip getIncomingClip() {
        return incomingClip;
    }

    /** Returns the crossfade curve type. */
    public CrossfadeCurve getCurveType() {
        return curveType;
    }

    /**
     * Sets the crossfade curve type.
     *
     * @param curveType the curve type (must not be {@code null})
     */
    public void setCurveType(CrossfadeCurve curveType) {
        this.curveType = Objects.requireNonNull(curveType, "curveType must not be null");
    }

    /**
     * Returns the start beat of the crossfade region.
     *
     * <p>This is the point where the incoming clip starts (and thus where
     * the crossfade blending begins).</p>
     *
     * @return the start beat of the crossfade
     */
    public double getStartBeat() {
        return Math.max(outgoingClip.getStartBeat(), incomingClip.getStartBeat());
    }

    /**
     * Returns the end beat of the crossfade region.
     *
     * <p>This is the point where the outgoing clip ends (and thus where
     * the crossfade blending finishes).</p>
     *
     * @return the end beat of the crossfade
     */
    public double getEndBeat() {
        return Math.min(outgoingClip.getEndBeat(), incomingClip.getEndBeat());
    }

    /**
     * Returns the duration of the crossfade region in beats.
     *
     * @return the crossfade duration in beats
     */
    public double getDurationBeats() {
        return getEndBeat() - getStartBeat();
    }

    /**
     * Returns whether the two clips still overlap.
     *
     * <p>Clips may stop overlapping if one is moved or trimmed after the
     * crossfade was created.</p>
     *
     * @return {@code true} if the clips overlap
     */
    public boolean hasOverlap() {
        return incomingClip.getStartBeat() < outgoingClip.getEndBeat()
                && outgoingClip.getStartBeat() < incomingClip.getEndBeat();
    }

    /**
     * Returns whether this crossfade involves the given clip.
     *
     * @param clip the clip to check
     * @return {@code true} if the clip is the outgoing or incoming clip
     */
    public boolean involvesClip(AudioClip clip) {
        return outgoingClip == clip || incomingClip == clip;
    }
}
