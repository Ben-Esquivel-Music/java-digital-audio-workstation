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

    /** Returns the end beat position (start + duration). */
    public double getEndBeat() {
        return startBeat + durationBeats;
    }
}
