package com.benesquivelmusic.daw.core.spatial.binaural;

import com.benesquivelmusic.daw.sdk.spatial.BinauralRenderer;
import com.benesquivelmusic.daw.sdk.spatial.HrtfData;
import com.benesquivelmusic.daw.sdk.spatial.MonitoringMode;
import com.benesquivelmusic.daw.sdk.spatial.SphericalCoordinate;

import java.util.Arrays;
import java.util.Objects;

/**
 * Default binaural renderer that spatializes audio for headphone playback
 * using HRTF-based convolution.
 *
 * <p>Features:</p>
 * <ul>
 *   <li>HRTF interpolation for arbitrary source positions via
 *       {@link HrtfInterpolator}</li>
 *   <li>Efficient partitioned convolution via {@link PartitionedConvolver}
 *       (overlap-save method)</li>
 *   <li>Interaural time difference (ITD) modeling for improved low-frequency
 *       localization</li>
 *   <li>Smooth crossfade between HRTF filters when source position changes
 *       to avoid audible clicks</li>
 *   <li>A/B monitoring mode switching (speaker ↔ binaural)</li>
 * </ul>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
public final class DefaultBinauralRenderer implements BinauralRenderer {

    private static final SphericalCoordinate DEFAULT_POSITION =
            new SphericalCoordinate(0, 0, 1.0);
    private static final double DEFAULT_CROSSFADE_MS = 20.0;
    private static final int MAX_ITD_SAMPLES = 128;

    private final double sampleRate;
    private final int blockSize;

    private HrtfData hrtfData;
    private HrtfInterpolator interpolator;
    private SphericalCoordinate sourcePosition;
    private MonitoringMode monitoringMode;
    private double crossfadeDurationMs;

    // Current convolution state
    private PartitionedConvolver leftConvolver;
    private PartitionedConvolver rightConvolver;

    // Crossfade state
    private PartitionedConvolver prevLeftConvolver;
    private PartitionedConvolver prevRightConvolver;
    private int crossfadeSamplesRemaining;
    private int crossfadeTotalSamples;

    // ITD delay line buffers
    private float[] leftDelayLine;
    private float[] rightDelayLine;
    private int leftDelaySamples;
    private int rightDelaySamples;

    /**
     * Creates a binaural renderer.
     *
     * @param sampleRate the audio sample rate in Hz
     * @param blockSize  the processing block size (must be a power of 2)
     */
    public DefaultBinauralRenderer(double sampleRate, int blockSize) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (blockSize <= 0 || (blockSize & (blockSize - 1)) != 0) {
            throw new IllegalArgumentException("blockSize must be a positive power of 2: " + blockSize);
        }
        this.sampleRate = sampleRate;
        this.blockSize = blockSize;
        this.sourcePosition = DEFAULT_POSITION;
        this.monitoringMode = MonitoringMode.BINAURAL;
        this.crossfadeDurationMs = DEFAULT_CROSSFADE_MS;
        this.leftDelayLine = new float[MAX_ITD_SAMPLES];
        this.rightDelayLine = new float[MAX_ITD_SAMPLES];
    }

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        if (monitoringMode == MonitoringMode.SPEAKER || leftConvolver == null) {
            passThrough(inputBuffer, outputBuffer, numFrames);
            return;
        }

        // Downmix input to mono
        float[] monoInput = downmixToMono(inputBuffer, numFrames);

        // Convolve with left and right HRTFs
        float[] leftOutput = new float[blockSize];
        float[] rightOutput = new float[blockSize];
        leftConvolver.processBlock(monoInput, leftOutput, 0, 0);
        rightConvolver.processBlock(monoInput, rightOutput, 0, 0);

        // Apply crossfade with previous HRTF if position recently changed
        if (crossfadeSamplesRemaining > 0 && prevLeftConvolver != null) {
            float[] prevLeft = new float[blockSize];
            float[] prevRight = new float[blockSize];
            prevLeftConvolver.processBlock(monoInput, prevLeft, 0, 0);
            prevRightConvolver.processBlock(monoInput, prevRight, 0, 0);

            for (int i = 0; i < numFrames && crossfadeSamplesRemaining > 0; i++) {
                float newWeight = 1.0f - (float) crossfadeSamplesRemaining / crossfadeTotalSamples;
                float oldWeight = 1.0f - newWeight;
                leftOutput[i] = leftOutput[i] * newWeight + prevLeft[i] * oldWeight;
                rightOutput[i] = rightOutput[i] * newWeight + prevRight[i] * oldWeight;
                crossfadeSamplesRemaining--;
            }

            if (crossfadeSamplesRemaining <= 0) {
                prevLeftConvolver = null;
                prevRightConvolver = null;
            }
        }

        // Apply ITD
        float[] leftDelayed = applyDelay(leftOutput, leftDelayLine, leftDelaySamples, numFrames);
        float[] rightDelayed = applyDelay(rightOutput, rightDelayLine, rightDelaySamples, numFrames);

        // Write to stereo output
        int outFrames = Math.min(numFrames, blockSize);
        if (outputBuffer.length >= 2) {
            System.arraycopy(leftDelayed, 0, outputBuffer[0], 0, outFrames);
            System.arraycopy(rightDelayed, 0, outputBuffer[1], 0, outFrames);
        } else if (outputBuffer.length == 1) {
            for (int i = 0; i < outFrames; i++) {
                outputBuffer[0][i] = (leftDelayed[i] + rightDelayed[i]) * 0.5f;
            }
        }
    }

    @Override
    public void loadHrtfData(HrtfData data) {
        Objects.requireNonNull(data, "data must not be null");
        this.hrtfData = data;
        this.interpolator = new HrtfInterpolator(data);
        rebuildConvolvers();
    }

    @Override
    public HrtfData getHrtfData() {
        return hrtfData;
    }

    @Override
    public void setSourcePosition(SphericalCoordinate position) {
        Objects.requireNonNull(position, "position must not be null");
        if (position.equals(this.sourcePosition)) {
            return;
        }

        // Save previous convolvers for crossfade
        if (leftConvolver != null) {
            prevLeftConvolver = leftConvolver;
            prevRightConvolver = rightConvolver;
            crossfadeTotalSamples = (int) (crossfadeDurationMs * sampleRate / 1000.0);
            crossfadeSamplesRemaining = crossfadeTotalSamples;
        }

        this.sourcePosition = position;
        rebuildConvolvers();
    }

    @Override
    public SphericalCoordinate getSourcePosition() {
        return sourcePosition;
    }

    @Override
    public void setMonitoringMode(MonitoringMode mode) {
        Objects.requireNonNull(mode, "mode must not be null");
        this.monitoringMode = mode;
    }

    @Override
    public MonitoringMode getMonitoringMode() {
        return monitoringMode;
    }

    @Override
    public void setCrossfadeDurationMs(double durationMs) {
        if (durationMs < 0) {
            throw new IllegalArgumentException("crossfade duration must be non-negative: " + durationMs);
        }
        this.crossfadeDurationMs = durationMs;
    }

    @Override
    public void reset() {
        if (leftConvolver != null) leftConvolver.reset();
        if (rightConvolver != null) rightConvolver.reset();
        prevLeftConvolver = null;
        prevRightConvolver = null;
        crossfadeSamplesRemaining = 0;
        Arrays.fill(leftDelayLine, 0.0f);
        Arrays.fill(rightDelayLine, 0.0f);
    }

    @Override
    public int getInputChannelCount() {
        return 1; // accepts mono (or downmixes to mono)
    }

    @Override
    public int getOutputChannelCount() {
        return 2; // always stereo binaural output
    }

    // ---- Internal helpers ---------------------------------------------------

    private void rebuildConvolvers() {
        if (interpolator == null || hrtfData == null) {
            return;
        }

        HrtfInterpolator.InterpolatedHrtf hrtf = interpolator.interpolate(sourcePosition);
        leftConvolver = new PartitionedConvolver(hrtf.leftIr(), blockSize);
        rightConvolver = new PartitionedConvolver(hrtf.rightIr(), blockSize);

        // Update ITD delays
        leftDelaySamples = Math.min(Math.round(hrtf.leftDelay()), MAX_ITD_SAMPLES - 1);
        rightDelaySamples = Math.min(Math.round(hrtf.rightDelay()), MAX_ITD_SAMPLES - 1);
        leftDelaySamples = Math.max(0, leftDelaySamples);
        rightDelaySamples = Math.max(0, rightDelaySamples);
    }

    private float[] downmixToMono(float[][] inputBuffer, int numFrames) {
        float[] mono = new float[blockSize];
        int channels = inputBuffer.length;
        int frames = Math.min(numFrames, blockSize);
        if (channels == 0) return mono;

        for (int i = 0; i < frames; i++) {
            float sum = 0;
            for (int ch = 0; ch < channels; ch++) {
                sum += inputBuffer[ch][i];
            }
            mono[i] = sum / channels;
        }
        return mono;
    }

    private static void passThrough(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        int channels = Math.min(inputBuffer.length, outputBuffer.length);
        for (int ch = 0; ch < channels; ch++) {
            System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
        }
    }

    private static float[] applyDelay(float[] input, float[] delayLine,
                                      int delaySamples, int numFrames) {
        if (delaySamples <= 0) {
            return input;
        }

        float[] output = new float[input.length];
        for (int i = 0; i < numFrames; i++) {
            if (i < delaySamples) {
                output[i] = delayLine[delayLine.length - delaySamples + i];
            } else {
                output[i] = input[i - delaySamples];
            }
        }

        // Update delay line: store the tail of the input
        int copyLen = Math.min(delaySamples, numFrames);
        if (copyLen > 0) {
            System.arraycopy(input, numFrames - copyLen,
                    delayLine, delayLine.length - copyLen, copyLen);
        }
        return output;
    }
}
