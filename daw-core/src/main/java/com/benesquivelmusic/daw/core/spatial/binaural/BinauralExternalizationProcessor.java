package com.benesquivelmusic.daw.core.spatial.binaural;

import com.benesquivelmusic.daw.core.dsp.BiquadFilter;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.spatial.HrtfData;
import com.benesquivelmusic.daw.sdk.spatial.SphericalCoordinate;

import java.util.Arrays;
import java.util.Objects;

/**
 * Binaural externalization processor that improves the perceived spatial
 * quality of headphone monitoring.
 *
 * <p>Binaural audio often suffers from in-head localization (sound appears
 * inside the head rather than outside). This processor adds subtle early
 * reflections, HRTF-based crosstalk, and room coloration to move the
 * perceived sound image outside the listener's head.</p>
 *
 * <p>Processing stages:</p>
 * <ol>
 *   <li><b>Crossfeed</b> — frequency-dependent inter-channel bleed with
 *       interaural time difference (ITD) delay, simulating natural
 *       loudspeaker crosstalk. Uses a {@link BiquadFilter} low-pass filter
 *       on the crossfeed signal.</li>
 *   <li><b>Early reflections</b> — four short delays with directional HRTF
 *       filters (when HRTF data is loaded via {@link #loadHrtfData}) or
 *       simple gain-scaled delay taps (when no HRTF is available). Uses
 *       {@link HrtfInterpolator} for directional filtering.</li>
 *   <li><b>Room coloration</b> — subtle low-order Feedback Delay Network
 *       (FDN) reverb with Householder feedback matrix, reusing techniques
 *       from {@link com.benesquivelmusic.daw.acoustics.simulator.AcousticsRoomSimulator},
 *       to add environmental context.</li>
 *   <li><b>Headphone compensation EQ</b> — a high-shelf
 *       {@link BiquadFilter} to compensate for headphone response
 *       differences between spatial and stereo content.</li>
 * </ol>
 *
 * <p>Parameters:</p>
 * <ul>
 *   <li>{@code crossfeedLevel} — inter-channel bleed amount [0, 1]</li>
 *   <li>{@code roomSize} — room coloration intensity [0, 1]</li>
 *   <li>{@code externalizationAmount} — overall wet/dry mix [0, 1]</li>
 *   <li>{@code headphoneCompensationGainDb} — shelf EQ gain in dB</li>
 * </ul>
 *
 * <p>Integrates with the existing {@link DefaultBinauralRenderer} monitoring
 * path as a post-processing stage for headphone output.</p>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 *
 * @see DefaultBinauralRenderer
 * @see HrtfInterpolator
 * @see BiquadFilter
 */
public final class BinauralExternalizationProcessor implements AudioProcessor {

    /** Number of early reflection taps per channel. */
    private static final int NUM_EARLY_REFLECTIONS = 4;

    /** Early reflection delay times in milliseconds. */
    private static final double[] REFLECTION_DELAYS_MS = {2.5, 4.0, 5.5, 7.0};

    /** Early reflection gain factors. */
    private static final double[] REFLECTION_GAINS = {0.4, 0.3, 0.25, 0.2};

    /** Azimuth offsets for HRTF-based early reflections relative to the virtual speaker. */
    private static final double[] REFLECTION_AZIMUTH_OFFSETS = {-30.0, 30.0, -60.0, 60.0};

    /** Elevation offsets for HRTF-based early reflections relative to the virtual speaker. */
    private static final double[] REFLECTION_ELEVATION_OFFSETS = {0.0, 0.0, 15.0, 15.0};

    /** Default virtual speaker azimuth for HRTF-based reflections (degrees). */
    private static final double DEFAULT_SPEAKER_AZIMUTH = 30.0;

    /** Crossfeed ITD delay in milliseconds (simulates loudspeaker crosstalk). */
    private static final double CROSSFEED_ITD_MS = 0.3;

    /** Crossfeed low-pass cutoff frequency in Hz. */
    private static final double CROSSFEED_LPF_FREQ = 700.0;

    /** Number of FDN delay lines for room coloration. */
    private static final int FDN_ORDER = 4;

    /** Prime-number base delay lengths (in samples at 48 kHz) for the FDN. */
    private static final int[] FDN_BASE_DELAYS = {347, 521, 743, 907};

    /** Target RT60 for room coloration FDN in seconds. */
    private static final double FDN_TARGET_RT60 = 0.3;

    /** FDN one-pole damping coefficient (higher = more high-frequency absorption). */
    private static final double FDN_DAMPING_COEFF = 0.3;

    /** Headphone EQ center frequency for the high-shelf filter in Hz. */
    private static final double HP_EQ_CENTER_FREQ = 3000.0;

    private static final double DEFAULT_CROSSFEED_LEVEL = 0.3;
    private static final double DEFAULT_ROOM_SIZE = 0.3;
    private static final double DEFAULT_EXTERNALIZATION_AMOUNT = 0.5;
    private static final double DEFAULT_HP_COMPENSATION_GAIN_DB = 0.0;

    private final double sampleRate;
    private final int blockSize;

    // Parameters
    private double crossfeedLevel;
    private double roomSize;
    private double externalizationAmount;
    private double headphoneCompensationGainDb;

    // Crossfeed: frequency-dependent inter-channel bleed with ITD delay
    private final BiquadFilter leftCrossfeedFilter;
    private final BiquadFilter rightCrossfeedFilter;
    private final float[] leftCrossfeedDelayLine;
    private final float[] rightCrossfeedDelayLine;
    private final int crossfeedDelaySamples;
    private int crossfeedDelayWritePos;

    // Early reflections (non-HRTF mode): simple delay taps
    private final float[][] leftReflectionDelayLines;
    private final float[][] rightReflectionDelayLines;
    private final int[] reflectionDelaySamples;
    private final int[] reflectionDelayWritePos;

    // HRTF-based early reflections (optional, enabled via loadHrtfData)
    private HrtfData hrtfData;
    private HrtfInterpolator interpolator;
    private PartitionedConvolver leftToLeftEarReflConvolver;
    private PartitionedConvolver leftToRightEarReflConvolver;
    private PartitionedConvolver rightToLeftEarReflConvolver;
    private PartitionedConvolver rightToRightEarReflConvolver;

    // Room coloration FDN
    private final float[][] fdnDelayLines;
    private final int[] fdnDelayLengths;
    private final int[] fdnWritePositions;
    private final float[] fdnFeedbackGains;
    private final float[] fdnDampingStore;

    // Headphone compensation EQ (per channel)
    private BiquadFilter leftHeadphoneEq;
    private BiquadFilter rightHeadphoneEq;

    // Pre-allocated work buffers
    private final float[] leftWork;
    private final float[] rightWork;
    private final float[] convWork;

    /**
     * Creates a binaural externalization processor.
     *
     * @param sampleRate the audio sample rate in Hz (must be positive)
     * @param blockSize  the processing block size (must be a positive power of 2)
     */
    public BinauralExternalizationProcessor(double sampleRate, int blockSize) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (blockSize <= 0 || (blockSize & (blockSize - 1)) != 0) {
            throw new IllegalArgumentException(
                    "blockSize must be a positive power of 2: " + blockSize);
        }

        this.sampleRate = sampleRate;
        this.blockSize = blockSize;
        this.crossfeedLevel = DEFAULT_CROSSFEED_LEVEL;
        this.roomSize = DEFAULT_ROOM_SIZE;
        this.externalizationAmount = DEFAULT_EXTERNALIZATION_AMOUNT;
        this.headphoneCompensationGainDb = DEFAULT_HP_COMPENSATION_GAIN_DB;

        // Work buffers
        this.leftWork = new float[blockSize];
        this.rightWork = new float[blockSize];
        this.convWork = new float[blockSize];

        // Crossfeed: LP filter + ITD delay
        this.crossfeedDelaySamples = Math.max(1,
                (int) (CROSSFEED_ITD_MS * sampleRate / 1000.0));
        this.leftCrossfeedDelayLine = new float[crossfeedDelaySamples];
        this.rightCrossfeedDelayLine = new float[crossfeedDelaySamples];
        this.crossfeedDelayWritePos = 0;
        this.leftCrossfeedFilter = BiquadFilter.create(
                BiquadFilter.FilterType.LOW_PASS, sampleRate,
                CROSSFEED_LPF_FREQ, 0.707, 0.0);
        this.rightCrossfeedFilter = BiquadFilter.create(
                BiquadFilter.FilterType.LOW_PASS, sampleRate,
                CROSSFEED_LPF_FREQ, 0.707, 0.0);

        // Early reflections: delay taps
        this.reflectionDelaySamples = new int[NUM_EARLY_REFLECTIONS];
        this.reflectionDelayWritePos = new int[NUM_EARLY_REFLECTIONS];
        this.leftReflectionDelayLines = new float[NUM_EARLY_REFLECTIONS][];
        this.rightReflectionDelayLines = new float[NUM_EARLY_REFLECTIONS][];
        for (int i = 0; i < NUM_EARLY_REFLECTIONS; i++) {
            reflectionDelaySamples[i] = Math.max(1,
                    (int) (REFLECTION_DELAYS_MS[i] * sampleRate / 1000.0));
            leftReflectionDelayLines[i] = new float[reflectionDelaySamples[i]];
            rightReflectionDelayLines[i] = new float[reflectionDelaySamples[i]];
            reflectionDelayWritePos[i] = 0;
        }

        // Room coloration FDN
        this.fdnDelayLines = new float[FDN_ORDER][];
        this.fdnDelayLengths = new int[FDN_ORDER];
        this.fdnWritePositions = new int[FDN_ORDER];
        this.fdnFeedbackGains = new float[FDN_ORDER];
        this.fdnDampingStore = new float[FDN_ORDER];
        initializeFdn();

        // Headphone compensation EQ
        this.leftHeadphoneEq = BiquadFilter.create(
                BiquadFilter.FilterType.HIGH_SHELF, sampleRate,
                HP_EQ_CENTER_FREQ, 0.707, headphoneCompensationGainDb);
        this.rightHeadphoneEq = BiquadFilter.create(
                BiquadFilter.FilterType.HIGH_SHELF, sampleRate,
                HP_EQ_CENTER_FREQ, 0.707, headphoneCompensationGainDb);
    }

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        int frames = Math.min(numFrames, blockSize);

        // Copy input to work buffers
        System.arraycopy(inputBuffer[0], 0, leftWork, 0, frames);
        if (inputBuffer.length > 1) {
            System.arraycopy(inputBuffer[1], 0, rightWork, 0, frames);
        } else {
            System.arraycopy(inputBuffer[0], 0, rightWork, 0, frames);
        }

        // 1. Crossfeed: frequency-dependent inter-channel bleed with ITD delay
        applyCrossfeed(frames);

        // 2. Early reflections
        if (interpolator != null && leftToLeftEarReflConvolver != null) {
            applyHrtfReflections(inputBuffer, frames);
        } else {
            applySimpleReflections(frames);
        }

        // 3. Room coloration (FDN)
        applyRoomColoration(frames);

        // 4. Headphone compensation EQ
        leftHeadphoneEq.process(leftWork, 0, frames);
        rightHeadphoneEq.process(rightWork, 0, frames);

        // 5. Mix dry/wet based on externalization amount
        float dryWeight = (float) (1.0 - externalizationAmount);
        float wetWeight = (float) externalizationAmount;
        for (int i = 0; i < frames; i++) {
            outputBuffer[0][i] = inputBuffer[0][i] * dryWeight + leftWork[i] * wetWeight;
            if (outputBuffer.length > 1) {
                float rightDry = (inputBuffer.length > 1) ? inputBuffer[1][i] : inputBuffer[0][i];
                outputBuffer[1][i] = rightDry * dryWeight + rightWork[i] * wetWeight;
            }
        }
    }

    // --- Parameter accessors -------------------------------------------------

    /** Returns the crossfeed level [0, 1]. */
    public double getCrossfeedLevel() {
        return crossfeedLevel;
    }

    /**
     * Sets the crossfeed level controlling inter-channel bleed.
     *
     * @param crossfeedLevel the crossfeed amount in the range [0, 1]
     */
    public void setCrossfeedLevel(double crossfeedLevel) {
        if (crossfeedLevel < 0 || crossfeedLevel > 1.0) {
            throw new IllegalArgumentException(
                    "crossfeedLevel must be in [0, 1]: " + crossfeedLevel);
        }
        this.crossfeedLevel = crossfeedLevel;
    }

    /** Returns the room size parameter [0, 1]. */
    public double getRoomSize() {
        return roomSize;
    }

    /**
     * Sets the room coloration intensity.
     *
     * @param roomSize the room size in the range [0, 1]
     */
    public void setRoomSize(double roomSize) {
        if (roomSize < 0 || roomSize > 1.0) {
            throw new IllegalArgumentException(
                    "roomSize must be in [0, 1]: " + roomSize);
        }
        this.roomSize = roomSize;
    }

    /** Returns the externalization amount [0, 1]. */
    public double getExternalizationAmount() {
        return externalizationAmount;
    }

    /**
     * Sets the overall externalization wet/dry mix.
     *
     * @param externalizationAmount the mix amount in the range [0, 1]
     */
    public void setExternalizationAmount(double externalizationAmount) {
        if (externalizationAmount < 0 || externalizationAmount > 1.0) {
            throw new IllegalArgumentException(
                    "externalizationAmount must be in [0, 1]: " + externalizationAmount);
        }
        this.externalizationAmount = externalizationAmount;
    }

    /** Returns the headphone compensation EQ gain in dB. */
    public double getHeadphoneCompensationGainDb() {
        return headphoneCompensationGainDb;
    }

    /**
     * Sets the headphone compensation EQ gain.
     *
     * <p>Adjusts a high-shelf filter to compensate for headphone frequency
     * response differences between spatial and stereo content. A value of
     * 0 dB produces a flat (unity) response.</p>
     *
     * @param gainDb the shelf EQ gain in dB
     */
    public void setHeadphoneCompensationGainDb(double gainDb) {
        this.headphoneCompensationGainDb = gainDb;
        this.leftHeadphoneEq = BiquadFilter.create(
                BiquadFilter.FilterType.HIGH_SHELF, sampleRate,
                HP_EQ_CENTER_FREQ, 0.707, gainDb);
        this.rightHeadphoneEq = BiquadFilter.create(
                BiquadFilter.FilterType.HIGH_SHELF, sampleRate,
                HP_EQ_CENTER_FREQ, 0.707, gainDb);
    }

    /**
     * Loads an HRTF dataset to enable directional early reflection filtering.
     *
     * <p>When HRTF data is loaded, early reflections are synthesized by
     * convolving the input with HRTF impulse responses at offset directions,
     * producing more realistic externalization than simple delay taps.</p>
     *
     * @param data the HRTF dataset to use (must not be null)
     */
    public void loadHrtfData(HrtfData data) {
        Objects.requireNonNull(data, "data must not be null");
        this.hrtfData = data;
        this.interpolator = new HrtfInterpolator(data);
        rebuildReflectionConvolvers();
    }

    /** Returns the currently loaded HRTF dataset, or {@code null} if none is loaded. */
    public HrtfData getHrtfData() {
        return hrtfData;
    }

    @Override
    public void reset() {
        // Crossfeed
        leftCrossfeedFilter.reset();
        rightCrossfeedFilter.reset();
        Arrays.fill(leftCrossfeedDelayLine, 0.0f);
        Arrays.fill(rightCrossfeedDelayLine, 0.0f);
        crossfeedDelayWritePos = 0;

        // Early reflections
        for (int i = 0; i < NUM_EARLY_REFLECTIONS; i++) {
            Arrays.fill(leftReflectionDelayLines[i], 0.0f);
            Arrays.fill(rightReflectionDelayLines[i], 0.0f);
            reflectionDelayWritePos[i] = 0;
        }

        // HRTF convolvers
        if (leftToLeftEarReflConvolver != null) leftToLeftEarReflConvolver.reset();
        if (leftToRightEarReflConvolver != null) leftToRightEarReflConvolver.reset();
        if (rightToLeftEarReflConvolver != null) rightToLeftEarReflConvolver.reset();
        if (rightToRightEarReflConvolver != null) rightToRightEarReflConvolver.reset();

        // FDN
        for (int i = 0; i < FDN_ORDER; i++) {
            Arrays.fill(fdnDelayLines[i], 0.0f);
            fdnWritePositions[i] = 0;
            fdnDampingStore[i] = 0.0f;
        }

        // Headphone EQ
        leftHeadphoneEq.reset();
        rightHeadphoneEq.reset();

        // Work buffers
        Arrays.fill(leftWork, 0.0f);
        Arrays.fill(rightWork, 0.0f);
        Arrays.fill(convWork, 0.0f);
    }

    @Override
    public int getInputChannelCount() {
        return 2;
    }

    @Override
    public int getOutputChannelCount() {
        return 2;
    }

    // ---- Internal processing stages -----------------------------------------

    private void applyCrossfeed(int frames) {
        float gain = (float) crossfeedLevel;
        for (int i = 0; i < frames; i++) {
            float leftIn = leftWork[i];
            float rightIn = rightWork[i];

            // Low-pass filter the opposite channel for frequency-dependent bleed
            float filteredRightToLeft = leftCrossfeedFilter.processSample(rightIn);
            float filteredLeftToRight = rightCrossfeedFilter.processSample(leftIn);

            // Read ITD-delayed crossfeed (circular buffer of exact delay length)
            float delayedRightToLeft = leftCrossfeedDelayLine[crossfeedDelayWritePos];
            float delayedLeftToRight = rightCrossfeedDelayLine[crossfeedDelayWritePos];

            // Write new filtered crossfeed to delay lines
            leftCrossfeedDelayLine[crossfeedDelayWritePos] = filteredRightToLeft;
            rightCrossfeedDelayLine[crossfeedDelayWritePos] = filteredLeftToRight;
            crossfeedDelayWritePos = (crossfeedDelayWritePos + 1) % crossfeedDelaySamples;

            // Apply crossfeed
            leftWork[i] = leftIn + delayedRightToLeft * gain;
            rightWork[i] = rightIn + delayedLeftToRight * gain;
        }
    }

    private void applySimpleReflections(int frames) {
        for (int i = 0; i < frames; i++) {
            float leftRefl = 0.0f;
            float rightRefl = 0.0f;

            for (int r = 0; r < NUM_EARLY_REFLECTIONS; r++) {
                int delaySamples = reflectionDelaySamples[r];

                // Read delayed sample from the circular buffer
                float delayedLeft = leftReflectionDelayLines[r][reflectionDelayWritePos[r]];
                float delayedRight = rightReflectionDelayLines[r][reflectionDelayWritePos[r]];

                leftRefl += delayedLeft * (float) REFLECTION_GAINS[r];
                rightRefl += delayedRight * (float) REFLECTION_GAINS[r];

                // Write current sample to the delay line
                leftReflectionDelayLines[r][reflectionDelayWritePos[r]] = leftWork[i];
                rightReflectionDelayLines[r][reflectionDelayWritePos[r]] = rightWork[i];
                reflectionDelayWritePos[r] = (reflectionDelayWritePos[r] + 1) % delaySamples;
            }

            leftWork[i] += leftRefl;
            rightWork[i] += rightRefl;
        }
    }

    private void applyHrtfReflections(float[][] inputBuffer, int frames) {
        // Pad input to blockSize
        float[] leftInput = new float[blockSize];
        float[] rightInput = new float[blockSize];
        System.arraycopy(inputBuffer[0], 0, leftInput, 0, frames);
        if (inputBuffer.length > 1) {
            System.arraycopy(inputBuffer[1], 0, rightInput, 0, frames);
        } else {
            System.arraycopy(inputBuffer[0], 0, rightInput, 0, frames);
        }

        // Left input → left ear reflections
        Arrays.fill(convWork, 0, blockSize, 0.0f);
        leftToLeftEarReflConvolver.processBlock(leftInput, convWork, 0, 0);
        for (int i = 0; i < frames; i++) {
            leftWork[i] += convWork[i];
        }

        // Left input → right ear reflections
        Arrays.fill(convWork, 0, blockSize, 0.0f);
        leftToRightEarReflConvolver.processBlock(leftInput, convWork, 0, 0);
        for (int i = 0; i < frames; i++) {
            rightWork[i] += convWork[i];
        }

        // Right input → left ear reflections
        Arrays.fill(convWork, 0, blockSize, 0.0f);
        rightToLeftEarReflConvolver.processBlock(rightInput, convWork, 0, 0);
        for (int i = 0; i < frames; i++) {
            leftWork[i] += convWork[i];
        }

        // Right input → right ear reflections
        Arrays.fill(convWork, 0, blockSize, 0.0f);
        rightToRightEarReflConvolver.processBlock(rightInput, convWork, 0, 0);
        for (int i = 0; i < frames; i++) {
            rightWork[i] += convWork[i];
        }
    }

    private void applyRoomColoration(int frames) {
        float gain = (float) roomSize;
        if (gain <= 0.0f) {
            return;
        }

        for (int i = 0; i < frames; i++) {
            float mono = (leftWork[i] + rightWork[i]) * 0.5f;
            float roomSample = processFdnSample(mono);
            leftWork[i] += roomSample * gain;
            rightWork[i] += roomSample * gain;
        }
    }

    private float processFdnSample(float input) {
        float output = 0.0f;
        float[] readValues = new float[FDN_ORDER];

        // Read from delay lines
        for (int i = 0; i < FDN_ORDER; i++) {
            readValues[i] = fdnDelayLines[i][fdnWritePositions[i]];
            output += readValues[i];
        }

        // Householder feedback matrix: H = I - (2/N) * ones
        float sum = 0.0f;
        for (int i = 0; i < FDN_ORDER; i++) {
            sum += readValues[i];
        }
        float householderTerm = 2.0f * sum / FDN_ORDER;

        // Write back with feedback, damping, and input injection
        for (int i = 0; i < FDN_ORDER; i++) {
            float feedback = (readValues[i] - householderTerm) * fdnFeedbackGains[i];
            // One-pole low-pass damping (same approach as ReverbProcessor)
            fdnDampingStore[i] = (float) (feedback * (1.0 - FDN_DAMPING_COEFF)
                    + fdnDampingStore[i] * FDN_DAMPING_COEFF);
            fdnDelayLines[i][fdnWritePositions[i]] = fdnDampingStore[i] + input / FDN_ORDER;
            fdnWritePositions[i] = (fdnWritePositions[i] + 1) % fdnDelayLengths[i];
        }

        return output / FDN_ORDER;
    }

    // ---- Initialization helpers ---------------------------------------------

    private void initializeFdn() {
        double scaleRatio = sampleRate / 48000.0;
        for (int i = 0; i < FDN_ORDER; i++) {
            fdnDelayLengths[i] = Math.max(1,
                    (int) (FDN_BASE_DELAYS[i] * scaleRatio));
            fdnDelayLines[i] = new float[fdnDelayLengths[i]];
            fdnWritePositions[i] = 0;

            // Feedback gain from target RT60: g = 10^(-3 * delay / (RT60 * sampleRate))
            double delaySec = (double) fdnDelayLengths[i] / sampleRate;
            fdnFeedbackGains[i] = (float) Math.pow(10.0,
                    -3.0 * delaySec / Math.max(FDN_TARGET_RT60, 0.01));
        }
    }

    private void rebuildReflectionConvolvers() {
        if (interpolator == null || hrtfData == null) {
            return;
        }

        SphericalCoordinate leftSpeaker = new SphericalCoordinate(
                DEFAULT_SPEAKER_AZIMUTH, 0.0, 1.0);
        SphericalCoordinate rightSpeaker = new SphericalCoordinate(
                360.0 - DEFAULT_SPEAKER_AZIMUTH, 0.0, 1.0);

        float[] leftReflLeftEarIr = buildReflectionIr(leftSpeaker, 0);
        float[] leftReflRightEarIr = buildReflectionIr(leftSpeaker, 1);
        float[] rightReflLeftEarIr = buildReflectionIr(rightSpeaker, 0);
        float[] rightReflRightEarIr = buildReflectionIr(rightSpeaker, 1);

        leftToLeftEarReflConvolver = new PartitionedConvolver(leftReflLeftEarIr, blockSize);
        leftToRightEarReflConvolver = new PartitionedConvolver(leftReflRightEarIr, blockSize);
        rightToLeftEarReflConvolver = new PartitionedConvolver(rightReflLeftEarIr, blockSize);
        rightToRightEarReflConvolver = new PartitionedConvolver(rightReflRightEarIr, blockSize);
    }

    /**
     * Builds a reflection-only impulse response for one speaker direction
     * and one ear, using HRTF filtering at offset directions.
     *
     * @param speakerPos the virtual speaker position
     * @param earIndex   0 for left ear, 1 for right ear
     * @return the reflection impulse response
     */
    private float[] buildReflectionIr(SphericalCoordinate speakerPos, int earIndex) {
        int maxDelaySamples = 0;
        for (int r = 0; r < NUM_EARLY_REFLECTIONS; r++) {
            int delaySamples = (int) (REFLECTION_DELAYS_MS[r] * sampleRate / 1000.0);
            if (delaySamples > maxDelaySamples) {
                maxDelaySamples = delaySamples;
            }
        }

        int irLength = hrtfData.irLength() + maxDelaySamples;
        irLength = Math.max(irLength, 1);
        float[] reflIr = new float[irLength];

        for (int r = 0; r < NUM_EARLY_REFLECTIONS; r++) {
            double reflAzimuth = speakerPos.azimuthDegrees() + REFLECTION_AZIMUTH_OFFSETS[r];
            double reflElevation = Math.max(-90.0, Math.min(90.0,
                    speakerPos.elevationDegrees() + REFLECTION_ELEVATION_OFFSETS[r]));

            SphericalCoordinate reflPos = new SphericalCoordinate(
                    reflAzimuth, reflElevation, speakerPos.distanceMeters()).normalize();

            HrtfInterpolator.InterpolatedHrtf reflHrtf = interpolator.interpolate(reflPos);
            float[] ir = (earIndex == 0) ? reflHrtf.leftIr() : reflHrtf.rightIr();

            int delaySamples = (int) (REFLECTION_DELAYS_MS[r] * sampleRate / 1000.0);
            double gain = REFLECTION_GAINS[r];

            int reflEnd = Math.min(ir.length, irLength - delaySamples);
            for (int s = 0; s < reflEnd; s++) {
                reflIr[s + delaySamples] += (float) (ir[s] * gain);
            }
        }

        return reflIr;
    }
}
