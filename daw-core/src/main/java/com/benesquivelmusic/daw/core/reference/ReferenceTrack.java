package com.benesquivelmusic.daw.core.reference;

import java.util.Objects;
import java.util.UUID;

/**
 * Represents a reference track used for A/B comparison during mixing and mastering.
 *
 * <p>A reference track holds imported audio data that bypasses the mixer effects
 * chain entirely. It supports independent loop regions and LUFS-based gain
 * matching so that volume differences do not bias A/B comparisons.</p>
 *
 * <p>Reference tracks are never included in the final export.</p>
 */
public final class ReferenceTrack {

    private final String id;
    private String name;
    private String sourceFilePath;
    private float[][] audioData;
    private double gainOffsetDb;
    private boolean loopEnabled;
    private double loopStartInBeats;
    private double loopEndInBeats;
    private double integratedLufs;

    /**
     * Creates a new reference track.
     *
     * @param name           the display name for this reference track
     * @param sourceFilePath the path to the source audio file
     * @throws NullPointerException if name or sourceFilePath is {@code null}
     */
    public ReferenceTrack(String name, String sourceFilePath) {
        this.id = UUID.randomUUID().toString();
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.sourceFilePath = Objects.requireNonNull(sourceFilePath, "sourceFilePath must not be null");
        this.gainOffsetDb = 0.0;
        this.loopEnabled = false;
        this.loopStartInBeats = 0.0;
        this.loopEndInBeats = 0.0;
        this.integratedLufs = -120.0;
    }

    /** Returns the unique identifier for this reference track. */
    public String getId() {
        return id;
    }

    /** Returns the display name. */
    public String getName() {
        return name;
    }

    /** Sets the display name. */
    public void setName(String name) {
        this.name = Objects.requireNonNull(name, "name must not be null");
    }

    /** Returns the source file path. */
    public String getSourceFilePath() {
        return sourceFilePath;
    }

    /** Sets the source file path. */
    public void setSourceFilePath(String sourceFilePath) {
        this.sourceFilePath = Objects.requireNonNull(sourceFilePath, "sourceFilePath must not be null");
    }

    /**
     * Returns the audio data for this reference track, or {@code null}
     * if no audio has been loaded.
     *
     * @return audio data as {@code [channel][sample]}, or {@code null}
     */
    public float[][] getAudioData() {
        return audioData;
    }

    /**
     * Sets the audio data for this reference track.
     *
     * @param audioData audio data as {@code [channel][sample]}, or {@code null} to clear
     */
    public void setAudioData(float[][] audioData) {
        this.audioData = audioData;
    }

    /**
     * Returns the gain offset in dB applied to level-match this reference
     * to the project mix output.
     *
     * @return gain offset in dB (positive = louder, negative = quieter)
     */
    public double getGainOffsetDb() {
        return gainOffsetDb;
    }

    /**
     * Sets the gain offset in dB for level matching.
     *
     * @param gainOffsetDb gain offset in dB
     */
    public void setGainOffsetDb(double gainOffsetDb) {
        this.gainOffsetDb = gainOffsetDb;
    }

    /**
     * Returns the linear gain multiplier derived from {@link #getGainOffsetDb()}.
     *
     * @return linear gain multiplier
     */
    public double getGainMultiplier() {
        return Math.pow(10.0, gainOffsetDb / 20.0);
    }

    /** Returns whether independent loop mode is enabled for this reference track. */
    public boolean isLoopEnabled() {
        return loopEnabled;
    }

    /** Enables or disables independent loop mode. */
    public void setLoopEnabled(boolean loopEnabled) {
        this.loopEnabled = loopEnabled;
    }

    /** Returns the loop start position in beats. */
    public double getLoopStartInBeats() {
        return loopStartInBeats;
    }

    /** Returns the loop end position in beats. */
    public double getLoopEndInBeats() {
        return loopEndInBeats;
    }

    /**
     * Sets the independent loop region for this reference track.
     *
     * @param startInBeats loop start position (must be &ge; 0)
     * @param endInBeats   loop end position (must be greater than {@code startInBeats})
     */
    public void setLoopRegion(double startInBeats, double endInBeats) {
        if (startInBeats < 0) {
            throw new IllegalArgumentException("loop start must not be negative: " + startInBeats);
        }
        if (endInBeats <= startInBeats) {
            throw new IllegalArgumentException(
                    "loop end must be greater than loop start: start=" + startInBeats + ", end=" + endInBeats);
        }
        this.loopStartInBeats = startInBeats;
        this.loopEndInBeats = endInBeats;
    }

    /**
     * Returns the integrated LUFS measurement for this reference track.
     *
     * @return integrated LUFS (default −120.0 for silence/unmeasured)
     */
    public double getIntegratedLufs() {
        return integratedLufs;
    }

    /**
     * Sets the integrated LUFS measurement for this reference track.
     *
     * @param integratedLufs the measured integrated LUFS value
     */
    public void setIntegratedLufs(double integratedLufs) {
        this.integratedLufs = integratedLufs;
    }
}
