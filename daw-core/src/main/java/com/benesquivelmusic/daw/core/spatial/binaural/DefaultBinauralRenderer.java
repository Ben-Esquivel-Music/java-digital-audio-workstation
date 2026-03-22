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

    // Pre-allocated workspace buffers (avoid allocation in process)
    private final float[] monoWorkBuffer;
    private final float[] leftWorkBuffer;
    private final float[] rightWorkBuffer;
    private final float[] prevLeftWorkBuffer;
    private final float[] prevRightWorkBuffer;

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
        this.monoWorkBuffer = new float[blockSize];
        this.leftWorkBuffer = new float[blockSize];
        this.rightWorkBuffer = new float[blockSize];
        this.prevLeftWorkBuffer = new float[blockSize];
        this.prevRightWorkBuffer = new float[blockSize];
    }

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        if (monitoringMode == MonitoringMode.SPEAKER || leftConvolver == null) {
            passThrough(inputBuffer, outputBuffer, numFrames);
            return;
        }

        // Downmix input to mono (uses pre-allocated monoWorkBuffer)
        downmixToMono(inputBuffer, numFrames);

        // Convolve with left and right HRTFs (uses pre-allocated work buffers)
        Arrays.fill(leftWorkBuffer, 0.0f);
        Arrays.fill(rightWorkBuffer, 0.0f);
        leftConvolver.processBlock(monoWorkBuffer, leftWorkBuffer, 0, 0);
        rightConvolver.processBlock(monoWorkBuffer, rightWorkBuffer, 0, 0);

        // Apply crossfade with previous HRTF if position recently changed
        if (crossfadeSamplesRemaining > 0 && prevLeftConvolver != null) {
            Arrays.fill(prevLeftWorkBuffer, 0.0f);
            Arrays.fill(prevRightWorkBuffer, 0.0f);
            prevLeftConvolver.processBlock(monoWorkBuffer, prevLeftWorkBuffer, 0, 0);
            prevRightConvolver.processBlock(monoWorkBuffer, prevRightWorkBuffer, 0, 0);

            for (int i = 0; i < numFrames && crossfadeSamplesRemaining > 0; i++) {
                float newWeight = 1.0f - (float) crossfadeSamplesRemaining / crossfadeTotalSamples;
                float oldWeight = 1.0f - newWeight;
                leftWorkBuffer[i] = leftWorkBuffer[i] * newWeight + prevLeftWorkBuffer[i] * oldWeight;
                rightWorkBuffer[i] = rightWorkBuffer[i] * newWeight + prevRightWorkBuffer[i] * oldWeight;
                crossfadeSamplesRemaining--;
            }

            if (crossfadeSamplesRemaining <= 0) {
                prevLeftConvolver = null;
                prevRightConvolver = null;
            }
        }

        // Apply ITD and write to output channels
        applyDelay(leftWorkBuffer, leftDelayLine, leftDelaySamples, numFrames, outputBuffer, 0);
        applyDelay(rightWorkBuffer, rightDelayLine, rightDelaySamples, numFrames, outputBuffer, 1);
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

    private void downmixToMono(float[][] inputBuffer, int numFrames) {
        Arrays.fill(monoWorkBuffer, 0.0f);
        int channels = inputBuffer.length;
        int frames = Math.min(numFrames, blockSize);
        if (channels == 0) return;

        for (int i = 0; i < frames; i++) {
            float sum = 0;
            for (int ch = 0; ch < channels; ch++) {
                sum += inputBuffer[ch][i];
            }
            monoWorkBuffer[i] = sum / channels;
        }
    }

    private static void passThrough(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        int channels = Math.min(inputBuffer.length, outputBuffer.length);
        for (int ch = 0; ch < channels; ch++) {
            System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
        }
    }

    private static void applyDelay(float[] input, float[] delayLine,
                                   int delaySamples, int numFrames,
                                   float[][] outputBuffer, int channelIndex) {
        if (channelIndex >= outputBuffer.length) {
            return;
        }
        float[] output = outputBuffer[channelIndex];

        if (delaySamples <= 0) {
            System.arraycopy(input, 0, output, 0, numFrames);
        } else {
            for (int i = 0; i < numFrames; i++) {
                if (i < delaySamples) {
                    output[i] = delayLine[delayLine.length - delaySamples + i];
                } else {
                    output[i] = input[i - delaySamples];
                }
            }
        }

        // Update delay line: store the tail of the input
        int copyLen = Math.min(delaySamples, numFrames);
        if (copyLen > 0) {
            System.arraycopy(input, numFrames - copyLen,
                    delayLine, delayLine.length - copyLen, copyLen);
        }
    }
}
