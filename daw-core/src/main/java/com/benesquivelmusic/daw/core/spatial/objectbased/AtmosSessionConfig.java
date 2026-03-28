package com.benesquivelmusic.daw.core.spatial.objectbased;

import com.benesquivelmusic.daw.sdk.spatial.SpeakerLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Holds the configuration for a Dolby Atmos session, including bed channel
 * assignments and audio object definitions.
 *
 * <p>This is the central model used by the Atmos session configuration dialog
 * and the ADM BWF export workflow. It aggregates the speaker layout,
 * bed channels, audio objects, and export parameters (sample rate, bit depth)
 * needed to validate and export an Atmos session.</p>
 */
public final class AtmosSessionConfig {

    private SpeakerLayout layout;
    private final List<BedChannel> bedChannels;
    private final List<AudioObject> audioObjects;
    private int sampleRate;
    private int bitDepth;

    /**
     * Creates an Atmos session configuration with defaults.
     *
     * <p>Defaults to a 7.1.4 layout, 48 kHz sample rate, and 24-bit depth.</p>
     */
    public AtmosSessionConfig() {
        this(SpeakerLayout.LAYOUT_7_1_4, 48000, 24);
    }

    /**
     * Creates an Atmos session configuration with the given parameters.
     *
     * @param layout     the speaker layout for bed channels
     * @param sampleRate the sample rate in Hz
     * @param bitDepth   the target bit depth (16, 24, or 32)
     */
    public AtmosSessionConfig(SpeakerLayout layout, int sampleRate, int bitDepth) {
        this.layout = Objects.requireNonNull(layout, "layout must not be null");
        this.sampleRate = validateSampleRate(sampleRate);
        this.bitDepth = validateBitDepth(bitDepth);
        this.bedChannels = new ArrayList<>();
        this.audioObjects = new ArrayList<>();
    }

    /** Returns the speaker layout. */
    public SpeakerLayout getLayout() {
        return layout;
    }

    /**
     * Sets the speaker layout.
     *
     * @param layout the new speaker layout
     */
    public void setLayout(SpeakerLayout layout) {
        this.layout = Objects.requireNonNull(layout, "layout must not be null");
    }

    /** Returns the sample rate in Hz. */
    public int getSampleRate() {
        return sampleRate;
    }

    /**
     * Sets the sample rate.
     *
     * @param sampleRate the sample rate in Hz (must be 44100, 48000, 88200, or 96000)
     */
    public void setSampleRate(int sampleRate) {
        this.sampleRate = validateSampleRate(sampleRate);
    }

    /** Returns the bit depth. */
    public int getBitDepth() {
        return bitDepth;
    }

    /**
     * Sets the bit depth.
     *
     * @param bitDepth the target bit depth (16, 24, or 32)
     */
    public void setBitDepth(int bitDepth) {
        this.bitDepth = validateBitDepth(bitDepth);
    }

    /**
     * Returns an unmodifiable view of the bed channel assignments.
     *
     * @return the bed channels
     */
    public List<BedChannel> getBedChannels() {
        return Collections.unmodifiableList(bedChannels);
    }

    /**
     * Adds a bed channel assignment.
     *
     * @param bedChannel the bed channel to add
     */
    public void addBedChannel(BedChannel bedChannel) {
        Objects.requireNonNull(bedChannel, "bedChannel must not be null");
        bedChannels.add(bedChannel);
    }

    /**
     * Removes a bed channel assignment.
     *
     * @param bedChannel the bed channel to remove
     * @return {@code true} if the bed channel was removed
     */
    public boolean removeBedChannel(BedChannel bedChannel) {
        return bedChannels.remove(bedChannel);
    }

    /** Removes all bed channel assignments. */
    public void clearBedChannels() {
        bedChannels.clear();
    }

    /**
     * Returns an unmodifiable view of the audio objects.
     *
     * @return the audio objects
     */
    public List<AudioObject> getAudioObjects() {
        return Collections.unmodifiableList(audioObjects);
    }

    /**
     * Adds an audio object.
     *
     * @param audioObject the audio object to add
     */
    public void addAudioObject(AudioObject audioObject) {
        Objects.requireNonNull(audioObject, "audioObject must not be null");
        audioObjects.add(audioObject);
    }

    /**
     * Removes an audio object.
     *
     * @param audioObject the audio object to remove
     * @return {@code true} if the audio object was removed
     */
    public boolean removeAudioObject(AudioObject audioObject) {
        return audioObjects.remove(audioObject);
    }

    /** Removes all audio objects. */
    public void clearAudioObjects() {
        audioObjects.clear();
    }

    /** Returns the total number of tracks (bed channels + audio objects). */
    public int getTotalTrackCount() {
        return bedChannels.size() + audioObjects.size();
    }

    /**
     * Returns the maximum number of audio objects allowed given the number
     * of bed channels, based on the Atmos 128-track limit.
     *
     * @param bedCount the number of bed channels
     * @return the maximum number of audio objects
     */
    public static int maxObjectsForBedCount(int bedCount) {
        return Math.min(AtmosSessionValidator.MAX_OBJECTS,
                AtmosSessionValidator.MAX_TOTAL_TRACKS - bedCount);
    }

    /**
     * Validates this configuration using {@link AtmosSessionValidator}.
     *
     * @return a list of validation error messages (empty if valid)
     */
    public List<String> validate() {
        return AtmosSessionValidator.validate(bedChannels, audioObjects, layout);
    }

    private static int validateSampleRate(int sampleRate) {
        if (sampleRate != 44100 && sampleRate != 48000
                && sampleRate != 88200 && sampleRate != 96000) {
            throw new IllegalArgumentException(
                    "sampleRate must be 44100, 48000, 88200, or 96000: " + sampleRate);
        }
        return sampleRate;
    }

    private static int validateBitDepth(int bitDepth) {
        if (bitDepth != 16 && bitDepth != 24 && bitDepth != 32) {
            throw new IllegalArgumentException("bitDepth must be 16, 24, or 32: " + bitDepth);
        }
        return bitDepth;
    }
}
