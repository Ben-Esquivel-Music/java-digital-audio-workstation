package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.audio.TimelineRegion;

import java.util.Objects;
import java.util.UUID;

/**
 * An audio clip placed on a track's timeline.
 *
 * <p>Each clip references a source audio file or buffer and is positioned
 * at a specific beat on the timeline with a given duration.</p>
 */
public final class AudioClip implements TimelineRegion {

    private final String id;
    private String name;
    private double startBeat;
    private double durationBeats;
    private double sourceOffsetBeats;
    private String sourceFilePath;
    private double gainDb;
    private boolean reversed;
    private double fadeInBeats;
    private double fadeOutBeats;
    private FadeCurveType fadeInCurveType;
    private FadeCurveType fadeOutCurveType;
    private float[][] audioData;

    /**
     * Creates a new audio clip.
     *
     * @param name           the display name
     * @param startBeat      the start position in beats
     * @param durationBeats  the duration in beats
     * @param sourceFilePath the path to the source audio file (may be {@code null})
     */
    public AudioClip(String name, double startBeat, double durationBeats, String sourceFilePath) {
        this.id = UUID.randomUUID().toString();
        this.name = Objects.requireNonNull(name, "name must not be null");
        if (startBeat < 0) {
            throw new IllegalArgumentException("startBeat must not be negative: " + startBeat);
        }
        if (durationBeats <= 0) {
            throw new IllegalArgumentException("durationBeats must be positive: " + durationBeats);
        }
        this.startBeat = startBeat;
        this.durationBeats = durationBeats;
        this.sourceOffsetBeats = 0.0;
        this.sourceFilePath = sourceFilePath;
        this.gainDb = 0.0;
        this.reversed = false;
        this.fadeInBeats = 0.0;
        this.fadeOutBeats = 0.0;
        this.fadeInCurveType = FadeCurveType.LINEAR;
        this.fadeOutCurveType = FadeCurveType.LINEAR;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = Objects.requireNonNull(name, "name must not be null");
    }

    @Override
    public double getStartBeat() {
        return startBeat;
    }

    public void setStartBeat(double startBeat) {
        if (startBeat < 0) {
            throw new IllegalArgumentException("startBeat must not be negative: " + startBeat);
        }
        this.startBeat = startBeat;
    }

    @Override
    public double getDurationBeats() {
        return durationBeats;
    }

    public void setDurationBeats(double durationBeats) {
        if (durationBeats <= 0) {
            throw new IllegalArgumentException("durationBeats must be positive: " + durationBeats);
        }
        this.durationBeats = durationBeats;
    }

    @Override
    public double getSourceOffsetBeats() {
        return sourceOffsetBeats;
    }

    public void setSourceOffsetBeats(double sourceOffsetBeats) {
        this.sourceOffsetBeats = sourceOffsetBeats;
    }

    /** Returns the path to the source audio file. */
    public String getSourceFilePath() {
        return sourceFilePath;
    }

    /** Sets the source audio file path. */
    public void setSourceFilePath(String sourceFilePath) {
        this.sourceFilePath = sourceFilePath;
    }

    /** Returns the clip gain in dB. */
    public double getGainDb() {
        return gainDb;
    }

    /** Sets the clip gain in dB. */
    public void setGainDb(double gainDb) {
        this.gainDb = gainDb;
    }

    /** Returns whether this clip's audio is reversed. */
    public boolean isReversed() {
        return reversed;
    }

    /** Sets whether this clip's audio is reversed. */
    public void setReversed(boolean reversed) {
        this.reversed = reversed;
    }

    /** Returns the fade-in duration in beats. */
    public double getFadeInBeats() {
        return fadeInBeats;
    }

    /**
     * Sets the fade-in duration in beats.
     *
     * @param fadeInBeats the fade-in duration (must not be negative)
     */
    public void setFadeInBeats(double fadeInBeats) {
        if (fadeInBeats < 0) {
            throw new IllegalArgumentException("fadeInBeats must not be negative: " + fadeInBeats);
        }
        this.fadeInBeats = fadeInBeats;
    }

    /** Returns the fade-out duration in beats. */
    public double getFadeOutBeats() {
        return fadeOutBeats;
    }

    /**
     * Sets the fade-out duration in beats.
     *
     * @param fadeOutBeats the fade-out duration (must not be negative)
     */
    public void setFadeOutBeats(double fadeOutBeats) {
        if (fadeOutBeats < 0) {
            throw new IllegalArgumentException("fadeOutBeats must not be negative: " + fadeOutBeats);
        }
        this.fadeOutBeats = fadeOutBeats;
    }

    /** Returns the fade-in curve type. */
    public FadeCurveType getFadeInCurveType() {
        return fadeInCurveType;
    }

    /**
     * Sets the fade-in curve type.
     *
     * @param fadeInCurveType the curve type (must not be {@code null})
     */
    public void setFadeInCurveType(FadeCurveType fadeInCurveType) {
        this.fadeInCurveType = Objects.requireNonNull(fadeInCurveType, "fadeInCurveType must not be null");
    }

    /** Returns the fade-out curve type. */
    public FadeCurveType getFadeOutCurveType() {
        return fadeOutCurveType;
    }

    /**
     * Sets the fade-out curve type.
     *
     * @param fadeOutCurveType the curve type (must not be {@code null})
     */
    public void setFadeOutCurveType(FadeCurveType fadeOutCurveType) {
        this.fadeOutCurveType = Objects.requireNonNull(fadeOutCurveType, "fadeOutCurveType must not be null");
    }

    /**
     * Returns the raw audio sample data, or {@code null} if this clip
     * references an external file rather than an in-memory buffer.
     *
     * @return audio data as {@code [channel][sample]} in [-1.0, 1.0], or {@code null}
     */
    public float[][] getAudioData() {
        return audioData;
    }

    /**
     * Sets the raw audio sample data for this clip.
     *
     * @param audioData audio data as {@code [channel][sample]}, or {@code null}
     */
    public void setAudioData(float[][] audioData) {
        this.audioData = audioData;
    }

    /** Returns the end beat position (start + duration). */
    public double getEndBeat() {
        return startBeat + durationBeats;
    }

    /**
     * Trims this clip to the specified beat range by adjusting the start,
     * duration, and source offset so that only the audio between
     * {@code newStartBeat} and {@code newEndBeat} is retained.
     *
     * <p>The new range must be within the clip's current bounds.</p>
     *
     * @param newStartBeat the new start beat (must be &ge; current start)
     * @param newEndBeat   the new end beat (must be &le; current end and &gt; newStartBeat)
     * @throws IllegalArgumentException if the range is invalid
     */
    public void trimTo(double newStartBeat, double newEndBeat) {
        if (newStartBeat < startBeat) {
            throw new IllegalArgumentException(
                    "newStartBeat must be >= startBeat: " + newStartBeat);
        }
        if (newEndBeat > getEndBeat()) {
            throw new IllegalArgumentException(
                    "newEndBeat must be <= endBeat: " + newEndBeat);
        }
        if (newEndBeat <= newStartBeat) {
            throw new IllegalArgumentException(
                    "newEndBeat must be > newStartBeat: " + newEndBeat);
        }
        double offsetDelta = newStartBeat - startBeat;
        this.sourceOffsetBeats += offsetDelta;
        this.startBeat = newStartBeat;
        this.durationBeats = newEndBeat - newStartBeat;
    }

    /**
     * Creates a duplicate of this clip with a new unique ID.
     *
     * @return a new {@code AudioClip} with the same properties but a different ID
     */
    public AudioClip duplicate() {
        AudioClip copy = new AudioClip(name, startBeat, durationBeats, sourceFilePath);
        copy.setSourceOffsetBeats(sourceOffsetBeats);
        copy.setGainDb(gainDb);
        copy.setReversed(reversed);
        copy.setFadeInBeats(fadeInBeats);
        copy.setFadeOutBeats(fadeOutBeats);
        copy.setFadeInCurveType(fadeInCurveType);
        copy.setFadeOutCurveType(fadeOutCurveType);
        copy.setAudioData(audioData);
        return copy;
    }

    /**
     * Splits this clip at the given beat position, truncating this clip and
     * returning a new clip that covers the remainder.
     *
     * <p>After the split, this clip's duration is reduced so that it ends at
     * {@code splitBeat}, and the returned clip starts at {@code splitBeat}
     * with the remaining duration.</p>
     *
     * @param splitBeat the beat position at which to split (must be strictly
     *                  between {@code startBeat} and {@code getEndBeat()})
     * @return the new clip covering the portion after the split point
     * @throws IllegalArgumentException if {@code splitBeat} is not within this clip's bounds
     */
    public AudioClip splitAt(double splitBeat) {
        if (splitBeat <= startBeat || splitBeat >= getEndBeat()) {
            throw new IllegalArgumentException(
                    "splitBeat must be strictly between startBeat and endBeat: " + splitBeat);
        }
        double remainingDuration = getEndBeat() - splitBeat;
        double splitSourceOffset = sourceOffsetBeats + (splitBeat - startBeat);

        AudioClip second = new AudioClip(name + " (split)", splitBeat, remainingDuration, sourceFilePath);
        second.setSourceOffsetBeats(splitSourceOffset);
        second.setGainDb(gainDb);
        second.setReversed(reversed);
        second.setFadeInBeats(0.0);
        second.setFadeOutBeats(fadeOutBeats);
        second.setFadeOutCurveType(fadeOutCurveType);

        // Truncate this clip
        this.durationBeats = splitBeat - startBeat;
        this.fadeOutBeats = 0.0;

        return second;
    }
}
