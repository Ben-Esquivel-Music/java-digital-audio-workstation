package com.benesquivelmusic.daw.core.spatial.binaural;

import com.benesquivelmusic.daw.acoustics.common.Coefficients;
import com.benesquivelmusic.daw.acoustics.common.Matrix;
import com.benesquivelmusic.daw.acoustics.common.Vec3;
import com.benesquivelmusic.daw.acoustics.dsp.Buffer;
import com.benesquivelmusic.daw.acoustics.spatialiser.Config;
import com.benesquivelmusic.daw.acoustics.spatialiser.FDN;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link AudioProcessor} adapter that wraps the {@code daw-acoustics} binaural
 * rendering pipeline so it can be used as a headphone monitoring insert in the
 * mixer.
 *
 * <p>The processor accepts mono or multi-channel input, distributes the input
 * across a set of spatialised reverb sources positioned uniformly on a sphere
 * (the same spherical-Fibonacci distribution used by the acoustics library's
 * {@code Reverb}), then pans each reverb source's output to stereo using its
 * horizontal direction — producing a headphone-friendly stereo signal that
 * exhibits an audible sense of spaciousness. Combined with the FDN's
 * frequency-dependent late reverb, this simulates hearing the source in a
 * small room through headphones.</p>
 *
 * <h2>Module boundary</h2>
 * <p>Like {@link com.benesquivelmusic.daw.core.dsp.acoustics.AcousticReverbProcessor},
 * this is an <em>adapter</em> that lives in {@code daw-core}. The
 * {@code daw-acoustics} module remains a standalone library with no dependency
 * on {@code daw-core} or {@code daw-app}.</p>
 *
 * <h2>Non-goals</h2>
 * <p>This adapter does not perform true HRTF convolution, head-tracking, or
 * Ambisonics decoding — those remain the responsibility of
 * {@link com.benesquivelmusic.daw.core.spatial.binaural.DefaultBinauralRenderer}
 * and the object-based spatial audio pipeline.</p>
 */
public final class BinauralMonitoringProcessor implements AudioProcessor {

    private static final int DEFAULT_NUM_REVERB_SOURCES = 8;
    private static final double[] DEFAULT_FREQUENCY_BANDS =
            {250.0, 500.0, 1000.0, 2000.0, 4000.0};
    private static final double DEFAULT_ROOM_T60 = 0.6;
    private static final double[] DEFAULT_ROOM_DIMENSIONS = {5.0, 3.0, 4.0};

    private final int inputChannels;
    private final double sampleRate;
    private final Config config;
    private final FDN fdn;
    private final List<Buffer> fdnOutputs;
    private final Matrix fdnInput;
    private final int numReverbSources;
    private final int framesPerBlock;

    /** Pre-computed per-source pan gains (left, right) derived from source direction. */
    private final double[] leftPanGains;
    private final double[] rightPanGains;

    private double wetLevel = 0.5;

    /**
     * Creates a binaural monitoring processor with default room parameters.
     *
     * @param inputChannels number of input channels (must be &ge; 1)
     * @param sampleRate    audio sample rate in Hz (must be &gt; 0)
     */
    public BinauralMonitoringProcessor(int inputChannels, double sampleRate) {
        this(inputChannels, sampleRate, DEFAULT_ROOM_DIMENSIONS, DEFAULT_ROOM_T60);
    }

    /**
     * Creates a binaural monitoring processor with explicit room parameters.
     *
     * @param inputChannels  number of input channels (must be &ge; 1)
     * @param sampleRate     audio sample rate in Hz (must be &gt; 0)
     * @param roomDimensions room dimensions in metres (width, height, depth);
     *                       must not be {@code null} and must have length &ge; 1
     * @param t60Seconds     broadband T60 in seconds (must be &gt; 0)
     */
    public BinauralMonitoringProcessor(int inputChannels, double sampleRate,
                                       double[] roomDimensions, double t60Seconds) {
        if (inputChannels < 1) throw new IllegalArgumentException("inputChannels must be >= 1");
        if (sampleRate <= 0) throw new IllegalArgumentException("sampleRate must be > 0");
        Objects.requireNonNull(roomDimensions, "roomDimensions must not be null");
        if (roomDimensions.length < 1) throw new IllegalArgumentException("roomDimensions must not be empty");
        if (t60Seconds <= 0) throw new IllegalArgumentException("t60Seconds must be > 0");

        this.inputChannels = inputChannels;
        this.sampleRate = sampleRate;
        this.framesPerBlock = 512;

        Coefficients freqBands = new Coefficients(DEFAULT_FREQUENCY_BANDS);
        this.config = new Config(
                (int) sampleRate,
                framesPerBlock,
                DEFAULT_NUM_REVERB_SOURCES,
                2.0,
                0.98,
                freqBands);
        this.numReverbSources = config.numReverbSources;

        Coefficients t60 = new Coefficients(freqBands.length(), t60Seconds);
        this.fdn = new FDN.HouseholderFDN(t60, roomDimensions.clone(), config);

        // Initialise the FDN's per-channel reflection filters with unity gain
        // so the late reverb passes through; use impulse-response mode so the
        // change takes effect immediately rather than over several blocks.
        config.setImpulseResponseMode(true);
        List<Coefficients> unityReflection = new ArrayList<>(numReverbSources);
        for (int i = 0; i < numReverbSources; i++) {
            unityReflection.add(new Coefficients(freqBands.length(), 1.0));
        }
        fdn.setTargetReflectionFilters(unityReflection);

        this.fdnOutputs = new ArrayList<>(numReverbSources);
        for (int i = 0; i < numReverbSources; i++) {
            fdnOutputs.add(new Buffer(framesPerBlock));
        }
        this.fdnInput = new Matrix(numReverbSources, framesPerBlock);

        // Compute per-source pan gains from a spherical-Fibonacci distribution.
        this.leftPanGains = new double[numReverbSources];
        this.rightPanGains = new double[numReverbSources];
        List<Vec3> directions = sphericalFibonacciDirections(numReverbSources);
        for (int i = 0; i < numReverbSources; i++) {
            Vec3 d = directions.get(i);
            double pan = 0.5 + 0.5 * d.x;             // -1 (left) → 0, +1 (right) → 1
            leftPanGains[i]  = Math.cos(pan * Math.PI / 2.0);
            rightPanGains[i] = Math.sin(pan * Math.PI / 2.0);
        }
    }

    /**
     * Sets the dry/wet reverb amount.
     *
     * @param wetLevel {@code 0.0} = dry only (direct L/R downmix),
     *                 {@code 1.0} = fully wet; clamped to [0, 1]
     */
    public void setWetLevel(double wetLevel) {
        this.wetLevel = Math.max(0.0, Math.min(1.0, wetLevel));
    }

    public double getWetLevel() { return wetLevel; }

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        Objects.requireNonNull(inputBuffer, "inputBuffer must not be null");
        Objects.requireNonNull(outputBuffer, "outputBuffer must not be null");
        if (outputBuffer.length < 2) {
            throw new IllegalArgumentException(
                    "BinauralMonitoringProcessor produces stereo output (requires 2 output channels)");
        }
        if (numFrames <= 0) return;

        int offset = 0;
        while (offset < numFrames) {
            int blockSize = Math.min(framesPerBlock, numFrames - offset);
            processBlock(inputBuffer, outputBuffer, offset, blockSize);
            offset += blockSize;
        }
    }

    private void processBlock(float[][] inputBuffer, float[][] outputBuffer,
                              int offset, int blockSize) {
        // 1. Distribute input channels across reverb sources (round-robin).
        Matrix inputView = (blockSize == framesPerBlock)
                ? fdnInput
                : new Matrix(numReverbSources, blockSize);
        if (blockSize == framesPerBlock) inputView.reset();

        double channelGain = 1.0 / inputChannels;
        for (int src = 0; src < numReverbSources; src++) {
            int inCh = src % inputChannels;
            float[] in = inputBuffer[inCh];
            for (int n = 0; n < blockSize; n++) {
                inputView.set(src, n, in[offset + n] * channelGain);
            }
        }

        // 2. Drive the FDN.
        List<Buffer> outs;
        if (blockSize == framesPerBlock) {
            outs = fdnOutputs;
            for (Buffer b : outs) b.reset();
        } else {
            outs = new ArrayList<>(numReverbSources);
            for (int i = 0; i < numReverbSources; i++) outs.add(new Buffer(blockSize));
        }
        fdn.processAudio(inputView, outs, config.getLerpFactor());

        // 3. Pan each reverb source to stereo using its direction, then mix with
        //    a direct L/R downmix of the input to keep transients audible.
        float[] left = outputBuffer[0];
        float[] right = outputBuffer[1];
        double wet = wetLevel;
        double dry = 1.0 - wetLevel;
        double wetScale = 1.0 / Math.max(1, numReverbSources / 2);

        // Direct sum of input for the dry part — guarantees stereo output even
        // from a mono input, so the contract "mono in → stereo out" is always met.
        for (int n = 0; n < blockSize; n++) {
            double sum = 0.0;
            for (int ch = 0; ch < inputChannels; ch++) sum += inputBuffer[ch][offset + n];
            sum *= channelGain;

            double l = 0.0, r = 0.0;
            for (int src = 0; src < numReverbSources; src++) {
                double y = outs.get(src).get(n);
                l += y * leftPanGains[src];
                r += y * rightPanGains[src];
            }
            left [offset + n] = (float) (dry * sum + wet * wetScale * l);
            right[offset + n] = (float) (dry * sum + wet * wetScale * r);
        }
    }

    @Override
    public void reset() {
        fdn.reset();
    }

    @Override
    public int getInputChannelCount() { return inputChannels; }

    /** Always {@code 2} — this is a binaural (stereo) monitoring processor. */
    @Override
    public int getOutputChannelCount() { return 2; }

    public double getSampleRate() { return sampleRate; }

    /** Spherical-Fibonacci direction distribution (matches the acoustics library). */
    private static List<Vec3> sphericalFibonacciDirections(int n) {
        List<Vec3> positions = new ArrayList<>(n);
        double goldenRatio = (1.0 + Math.sqrt(5.0)) / 2.0;
        for (int i = 0; i < n; i++) {
            double theta = Math.acos(1.0 - 2.0 * (i + 0.5) / n);
            double phi = 2.0 * Math.PI * i / goldenRatio;
            positions.add(new Vec3(
                    Math.sin(theta) * Math.cos(phi),
                    Math.sin(theta) * Math.sin(phi),
                    Math.cos(theta)));
        }
        return positions;
    }
}
