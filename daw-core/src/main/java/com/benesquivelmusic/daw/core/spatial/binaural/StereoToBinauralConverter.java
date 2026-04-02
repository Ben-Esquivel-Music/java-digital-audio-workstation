package com.benesquivelmusic.daw.core.spatial.binaural;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.spatial.HrtfData;
import com.benesquivelmusic.daw.sdk.spatial.RoomSimulator;
import com.benesquivelmusic.daw.sdk.spatial.SphericalCoordinate;

import java.util.Arrays;
import java.util.Objects;

/**
 * Converts standard stereo mixes into binaural audio optimized for headphone
 * playback.
 *
 * <p>Places the left and right channels at configurable virtual speaker
 * positions (default ±30° azimuth) and convolves each with the corresponding
 * HRTF from the loaded dataset using {@link PartitionedConvolver} for
 * zero-latency processing. This produces a binaural stereo signal that
 * simulates a speaker-like spatial presentation over headphones.</p>
 *
 * <p>Four convolution paths are maintained:</p>
 * <ul>
 *   <li>Left input → left ear (via left speaker HRTF)</li>
 *   <li>Left input → right ear (via left speaker HRTF)</li>
 *   <li>Right input → left ear (via right speaker HRTF)</li>
 *   <li>Right input → right ear (via right speaker HRTF)</li>
 * </ul>
 *
 * <p>Optional features:</p>
 * <ul>
 *   <li>Early reflection modeling (3 reflections per speaker) for enhanced
 *       externalization, based on HRTF-filtered virtual reflections at
 *       offset azimuths and elevations</li>
 *   <li>Room simulation via {@link RoomSimulator} for an ambient
 *       reverb tail</li>
 * </ul>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 *
 * @see HrtfInterpolator
 * @see PartitionedConvolver
 * @see SofaFileParser
 */
public final class StereoToBinauralConverter implements AudioProcessor {

    private static final double DEFAULT_SPEAKER_AZIMUTH = 30.0;
    private static final double DEFAULT_SPEAKER_DISTANCE = 1.0;

    /** Number of early reflections modeled per virtual speaker. */
    private static final int NUM_EARLY_REFLECTIONS = 3;

    /** Azimuth offsets (degrees) for early reflections relative to the speaker. */
    private static final double[] REFLECTION_AZIMUTH_OFFSETS = {15.0, -15.0, 0.0};

    /** Elevation offsets (degrees) for early reflections relative to the speaker. */
    private static final double[] REFLECTION_ELEVATION_OFFSETS = {0.0, 0.0, 30.0};

    /** Time delays (milliseconds) for each early reflection. */
    private static final double[] REFLECTION_DELAYS_MS = {3.0, 5.0, 7.0};

    /** Amplitude gains for each early reflection. */
    private static final double[] REFLECTION_GAINS = {0.5, 0.4, 0.3};

    private final double sampleRate;
    private final int blockSize;

    private HrtfData hrtfData;
    private HrtfInterpolator interpolator;

    private double speakerAzimuth;
    private double speakerDistance;
    private double roomInfluence;
    private boolean earlyReflectionsEnabled;

    // Four convolvers for cross-channel HRTF paths
    private PartitionedConvolver leftToLeftEar;
    private PartitionedConvolver leftToRightEar;
    private PartitionedConvolver rightToLeftEar;
    private PartitionedConvolver rightToRightEar;

    // Optional room simulator for ambient tail
    private RoomSimulator roomSimulator;

    // Pre-allocated workspace buffers (avoid allocation in process)
    private final float[] leftInputPadded;
    private final float[] rightInputPadded;
    private final float[] leftEarAccum;
    private final float[] rightEarAccum;
    private final float[] convWork;
    private final float[] roomMonoWork;

    /**
     * Creates a stereo-to-binaural converter.
     *
     * @param sampleRate the audio sample rate in Hz
     * @param blockSize  the processing block size (must be a positive power of 2)
     */
    public StereoToBinauralConverter(double sampleRate, int blockSize) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (blockSize <= 0 || (blockSize & (blockSize - 1)) != 0) {
            throw new IllegalArgumentException("blockSize must be a positive power of 2: " + blockSize);
        }
        this.sampleRate = sampleRate;
        this.blockSize = blockSize;
        this.speakerAzimuth = DEFAULT_SPEAKER_AZIMUTH;
        this.speakerDistance = DEFAULT_SPEAKER_DISTANCE;
        this.roomInfluence = 0.0;
        this.earlyReflectionsEnabled = false;

        this.leftInputPadded = new float[blockSize];
        this.rightInputPadded = new float[blockSize];
        this.leftEarAccum = new float[blockSize];
        this.rightEarAccum = new float[blockSize];
        this.convWork = new float[blockSize];
        this.roomMonoWork = new float[blockSize];
    }

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        if (leftToLeftEar == null) {
            passThrough(inputBuffer, outputBuffer, numFrames);
            return;
        }

        int frames = Math.min(numFrames, blockSize);

        // Prepare zero-padded input buffers
        Arrays.fill(leftInputPadded, 0.0f);
        Arrays.fill(rightInputPadded, 0.0f);
        System.arraycopy(inputBuffer[0], 0, leftInputPadded, 0, frames);
        if (inputBuffer.length > 1) {
            System.arraycopy(inputBuffer[1], 0, rightInputPadded, 0, frames);
        }

        // Convolve left channel through left speaker HRTF
        leftToLeftEar.processBlock(leftInputPadded, leftEarAccum, 0, 0);
        leftToRightEar.processBlock(leftInputPadded, rightEarAccum, 0, 0);

        // Convolve right channel through right speaker HRTF and accumulate
        rightToLeftEar.processBlock(rightInputPadded, convWork, 0, 0);
        for (int i = 0; i < frames; i++) {
            leftEarAccum[i] += convWork[i];
        }

        rightToRightEar.processBlock(rightInputPadded, convWork, 0, 0);
        for (int i = 0; i < frames; i++) {
            rightEarAccum[i] += convWork[i];
        }

        // Write binaural output
        System.arraycopy(leftEarAccum, 0, outputBuffer[0], 0, frames);
        System.arraycopy(rightEarAccum, 0, outputBuffer[1], 0, frames);

        // Mix in room simulation if configured
        if (roomSimulator != null && roomInfluence > 0.0) {
            Arrays.fill(roomMonoWork, 0, blockSize, 0.0f);
            for (int i = 0; i < frames; i++) {
                roomMonoWork[i] = (leftInputPadded[i] + rightInputPadded[i]) * 0.5f;
            }
            float[][] roomIn = {roomMonoWork};
            float[][] roomOut = {convWork};
            Arrays.fill(convWork, 0, blockSize, 0.0f);
            roomSimulator.process(roomIn, roomOut, frames);

            float roomGain = (float) roomInfluence;
            for (int i = 0; i < frames; i++) {
                outputBuffer[0][i] += convWork[i] * roomGain;
                outputBuffer[1][i] += convWork[i] * roomGain;
            }
        }
    }

    /**
     * Loads an HRTF dataset and rebuilds the internal convolution structures.
     *
     * @param data the HRTF dataset to use
     */
    public void loadHrtfData(HrtfData data) {
        Objects.requireNonNull(data, "data must not be null");
        this.hrtfData = data;
        this.interpolator = new HrtfInterpolator(data);
        rebuildConvolvers();
    }

    /**
     * Returns the currently loaded HRTF dataset, or {@code null} if none
     * is loaded.
     *
     * @return the current HRTF data
     */
    public HrtfData getHrtfData() {
        return hrtfData;
    }

    /**
     * Sets the half-angle azimuth for virtual speaker placement.
     *
     * <p>The left speaker is positioned at {@code +azimuth} and the right
     * speaker at {@code 360° − azimuth} (SOFA convention). Default is 30°,
     * corresponding to standard stereo speaker placement at ±30°.</p>
     *
     * @param azimuthDegrees the speaker azimuth in degrees, in the range [0, 90]
     */
    public void setSpeakerAzimuth(double azimuthDegrees) {
        if (azimuthDegrees < 0 || azimuthDegrees > 90.0) {
            throw new IllegalArgumentException("speakerAzimuth must be in [0, 90]: " + azimuthDegrees);
        }
        this.speakerAzimuth = azimuthDegrees;
        rebuildConvolvers();
    }

    /** Returns the current speaker azimuth in degrees. */
    public double getSpeakerAzimuth() {
        return speakerAzimuth;
    }

    /**
     * Sets the virtual speaker distance in meters.
     *
     * @param distanceMeters the speaker distance (must be positive)
     */
    public void setSpeakerDistance(double distanceMeters) {
        if (distanceMeters <= 0) {
            throw new IllegalArgumentException("speakerDistance must be positive: " + distanceMeters);
        }
        this.speakerDistance = distanceMeters;
        rebuildConvolvers();
    }

    /** Returns the current speaker distance in meters. */
    public double getSpeakerDistance() {
        return speakerDistance;
    }

    /**
     * Sets how much room simulation to mix into the output.
     *
     * @param influence the room influence level in the range [0, 1]
     */
    public void setRoomInfluence(double influence) {
        if (influence < 0 || influence > 1.0) {
            throw new IllegalArgumentException("roomInfluence must be in [0, 1]: " + influence);
        }
        this.roomInfluence = influence;
    }

    /** Returns the current room influence level. */
    public double getRoomInfluence() {
        return roomInfluence;
    }

    /**
     * Enables or disables early reflection modeling for enhanced
     * externalization.
     *
     * <p>When enabled, three early reflections per virtual speaker are
     * modeled at offset azimuths and elevations with time delays of
     * 3–7 ms, producing a composite impulse response that enhances the
     * sense of sound originating outside the head.</p>
     *
     * @param enabled {@code true} to enable early reflections
     */
    public void setEarlyReflectionsEnabled(boolean enabled) {
        if (this.earlyReflectionsEnabled != enabled) {
            this.earlyReflectionsEnabled = enabled;
            rebuildConvolvers();
        }
    }

    /** Returns whether early reflections are enabled. */
    public boolean isEarlyReflectionsEnabled() {
        return earlyReflectionsEnabled;
    }

    /**
     * Sets an optional room simulator for ambient tail generation.
     *
     * <p>The simulator must be pre-configured via
     * {@link RoomSimulator#configure} before being set here.
     * Pass {@code null} to disable room simulation.</p>
     *
     * @param simulator the configured room simulator, or {@code null}
     */
    public void setRoomSimulator(RoomSimulator simulator) {
        this.roomSimulator = simulator;
    }

    /** Returns the currently set room simulator, or {@code null} if none. */
    public RoomSimulator getRoomSimulator() {
        return roomSimulator;
    }

    @Override
    public void reset() {
        if (leftToLeftEar != null) leftToLeftEar.reset();
        if (leftToRightEar != null) leftToRightEar.reset();
        if (rightToLeftEar != null) rightToLeftEar.reset();
        if (rightToRightEar != null) rightToRightEar.reset();
        if (roomSimulator != null) roomSimulator.reset();
        Arrays.fill(leftInputPadded, 0.0f);
        Arrays.fill(rightInputPadded, 0.0f);
        Arrays.fill(leftEarAccum, 0.0f);
        Arrays.fill(rightEarAccum, 0.0f);
        Arrays.fill(convWork, 0.0f);
        Arrays.fill(roomMonoWork, 0.0f);
    }

    @Override
    public int getInputChannelCount() {
        return 2;
    }

    @Override
    public int getOutputChannelCount() {
        return 2;
    }

    // ---- Internal helpers ---------------------------------------------------

    private void rebuildConvolvers() {
        if (interpolator == null || hrtfData == null) {
            return;
        }

        SphericalCoordinate leftSpeaker =
                new SphericalCoordinate(speakerAzimuth, 0.0, speakerDistance);
        SphericalCoordinate rightSpeaker =
                new SphericalCoordinate(360.0 - speakerAzimuth, 0.0, speakerDistance);

        if (earlyReflectionsEnabled) {
            float[] llIr = buildCompositeIr(leftSpeaker, 0);
            float[] lrIr = buildCompositeIr(leftSpeaker, 1);
            float[] rlIr = buildCompositeIr(rightSpeaker, 0);
            float[] rrIr = buildCompositeIr(rightSpeaker, 1);

            leftToLeftEar = new PartitionedConvolver(llIr, blockSize);
            leftToRightEar = new PartitionedConvolver(lrIr, blockSize);
            rightToLeftEar = new PartitionedConvolver(rlIr, blockSize);
            rightToRightEar = new PartitionedConvolver(rrIr, blockSize);
        } else {
            HrtfInterpolator.InterpolatedHrtf leftHrtf =
                    interpolator.interpolate(leftSpeaker);
            HrtfInterpolator.InterpolatedHrtf rightHrtf =
                    interpolator.interpolate(rightSpeaker);

            leftToLeftEar = new PartitionedConvolver(leftHrtf.leftIr(), blockSize);
            leftToRightEar = new PartitionedConvolver(leftHrtf.rightIr(), blockSize);
            rightToLeftEar = new PartitionedConvolver(rightHrtf.leftIr(), blockSize);
            rightToRightEar = new PartitionedConvolver(rightHrtf.rightIr(), blockSize);
        }
    }

    /**
     * Builds a composite impulse response that includes the direct HRTF
     * plus early reflections at offset directions.
     *
     * @param speakerPos the virtual speaker position
     * @param earIndex   0 for left ear, 1 for right ear
     * @return the composite impulse response
     */
    private float[] buildCompositeIr(SphericalCoordinate speakerPos, int earIndex) {
        HrtfInterpolator.InterpolatedHrtf directHrtf =
                interpolator.interpolate(speakerPos);
        float[] directIr = (earIndex == 0) ? directHrtf.leftIr() : directHrtf.rightIr();

        // Find the maximum reflection delay in samples
        int maxDelaySamples = 0;
        for (int r = 0; r < NUM_EARLY_REFLECTIONS; r++) {
            int delaySamples = (int) (REFLECTION_DELAYS_MS[r] * sampleRate / 1000.0);
            if (delaySamples > maxDelaySamples) {
                maxDelaySamples = delaySamples;
            }
        }

        int compositeLength = directIr.length + maxDelaySamples;
        float[] composite = new float[compositeLength];

        // Add direct sound
        System.arraycopy(directIr, 0, composite, 0, directIr.length);

        // Add early reflections
        for (int r = 0; r < NUM_EARLY_REFLECTIONS; r++) {
            double reflAzimuth = speakerPos.azimuthDegrees() + REFLECTION_AZIMUTH_OFFSETS[r];
            double reflElevation = Math.max(-90.0, Math.min(90.0,
                    speakerPos.elevationDegrees() + REFLECTION_ELEVATION_OFFSETS[r]));

            SphericalCoordinate reflPos = new SphericalCoordinate(
                    reflAzimuth, reflElevation, speakerPos.distanceMeters()).normalize();

            HrtfInterpolator.InterpolatedHrtf reflHrtf =
                    interpolator.interpolate(reflPos);
            float[] reflIr = (earIndex == 0) ? reflHrtf.leftIr() : reflHrtf.rightIr();

            int delaySamples = (int) (REFLECTION_DELAYS_MS[r] * sampleRate / 1000.0);
            double gain = REFLECTION_GAINS[r];

            int reflEnd = Math.min(reflIr.length, compositeLength - delaySamples);
            for (int s = 0; s < reflEnd; s++) {
                composite[s + delaySamples] += (float) (reflIr[s] * gain);
            }
        }

        return composite;
    }

    private static void passThrough(float[][] inputBuffer, float[][] outputBuffer,
                                    int numFrames) {
        int channels = Math.min(inputBuffer.length, outputBuffer.length);
        for (int ch = 0; ch < channels; ch++) {
            System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
        }
    }
}
