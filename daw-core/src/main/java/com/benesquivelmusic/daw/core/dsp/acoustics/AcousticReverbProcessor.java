package com.benesquivelmusic.daw.core.dsp.acoustics;

import com.benesquivelmusic.daw.acoustics.common.Coefficients;
import com.benesquivelmusic.daw.acoustics.common.Matrix;
import com.benesquivelmusic.daw.acoustics.dsp.Buffer;
import com.benesquivelmusic.daw.acoustics.spatialiser.Config;
import com.benesquivelmusic.daw.acoustics.spatialiser.FDN;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link AudioProcessor} adapter that exposes the {@code daw-acoustics}
 * Feedback Delay Network (FDN) reverb as a built-in insert effect.
 *
 * <p>This adapter wraps a {@link FDN.HouseholderFDN} configured from a set of
 * room dimensions (width, height, depth) and a frequency-dependent
 * reverberation time (T60). The FDN's internal {@code GraphicEQ} absorption
 * filters give a much more natural, frequency-aware decay than the simpler
 * Schroeder–Moorer {@code ReverbProcessor} it complements.</p>
 *
 * <h2>Module boundary</h2>
 * <p>{@code daw-acoustics} remains a standalone library with no dependencies
 * on {@code daw-core} or {@code daw-app}. This class is the adapter that
 * lives in {@code daw-core} and exposes the acoustics algorithms as an
 * {@code AudioProcessor} usable in the mixer insert chain.</p>
 *
 * <h2>Channel handling</h2>
 * <p>The processor is channel-agnostic: the host {@link #getInputChannelCount()
 * input} and {@link #getOutputChannelCount() output} counts are identical
 * (typically 2 for stereo inserts). On every block the host's channels are
 * distributed onto the FDN's internal reverb sources; the FDN outputs are
 * then summed back into each host channel and mixed with the dry signal
 * using the wet/dry {@link #setMix(double) mix} control.</p>
 */
public final class AcousticReverbProcessor implements AudioProcessor {

    /** Room geometry and decay presets keyed off the issue's use cases. */
    public enum RoomPreset {
        SMALL_ROOM(new double[]{3.0, 2.5, 4.0}, 0.4),
        MEDIUM_ROOM(new double[]{6.0, 3.0, 5.0}, 0.8),
        LARGE_HALL(new double[]{20.0, 10.0, 15.0}, 2.5),
        CATHEDRAL(new double[]{50.0, 20.0, 30.0}, 5.0);

        private final double[] dimensions;
        private final double t60Seconds;

        RoomPreset(double[] dimensions, double t60Seconds) {
            this.dimensions = dimensions;
            this.t60Seconds = t60Seconds;
        }

        public double[] dimensions() { return dimensions.clone(); }
        public double t60Seconds()   { return t60Seconds; }
    }

    private static final int DEFAULT_NUM_REVERB_SOURCES = 8;
    private static final double[] DEFAULT_FREQUENCY_BANDS =
            {250.0, 500.0, 1000.0, 2000.0, 4000.0};

    private final int channels;
    private final double sampleRate;
    private final Config config;
    private final FDN fdn;
    private final List<Buffer> fdnOutputs;
    private final Matrix fdnInput;
    private final int numReverbSources;
    private final int framesPerBlock;

    private double mix;

    /**
     * Creates an acoustic reverb processor with the {@link RoomPreset#MEDIUM_ROOM}
     * preset and a 30 % wet mix.
     *
     * @param channels   number of input/output channels (must be &ge; 1)
     * @param sampleRate audio sample rate in Hz (must be &gt; 0)
     */
    public AcousticReverbProcessor(int channels, double sampleRate) {
        this(channels, sampleRate, RoomPreset.MEDIUM_ROOM, 0.3);
    }

    /**
     * Creates an acoustic reverb processor with a named room preset.
     *
     * @param channels   number of input/output channels (must be &ge; 1)
     * @param sampleRate audio sample rate in Hz (must be &gt; 0)
     * @param preset     geometry / T60 preset (must not be {@code null})
     * @param mix        wet/dry blend, {@code 0.0} = fully dry, {@code 1.0} = fully wet
     */
    public AcousticReverbProcessor(int channels, double sampleRate,
                                   RoomPreset preset, double mix) {
        if (channels < 1) throw new IllegalArgumentException("channels must be >= 1");
        if (sampleRate <= 0) throw new IllegalArgumentException("sampleRate must be > 0");
        Objects.requireNonNull(preset, "preset must not be null");

        this.channels = channels;
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

        Coefficients t60 = new Coefficients(freqBands.length(), preset.t60Seconds());
        this.fdn = new FDN.HouseholderFDN(t60, preset.dimensions(), config);

        // The FDN's per-channel reflection filter is initialised with all
        // gains = 0.0, which would zero the reverb output. Set unity-gain
        // reflection filters so the FDN passes its late-reverb signal through
        // transparently. Parameter lerping happens in impulse-response mode
        // so the gains take effect immediately instead of ramping over
        // hundreds of samples.
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

        setMix(mix);
    }

    /**
     * Sets the wet/dry mix.
     *
     * @param mix {@code 0.0} = fully dry, {@code 1.0} = fully wet; clamped to [0, 1]
     */
    public void setMix(double mix) {
        this.mix = Math.max(0.0, Math.min(1.0, mix));
    }

    public double getMix() { return mix; }

    /**
     * Updates the frequency-dependent reverberation time.
     *
     * @param t60Seconds desired broadband T60 in seconds (must be &gt; 0)
     */
    public void setT60(double t60Seconds) {
        if (t60Seconds <= 0) throw new IllegalArgumentException("t60Seconds must be > 0");
        Coefficients t60 = new Coefficients(DEFAULT_FREQUENCY_BANDS.length, t60Seconds);
        fdn.setTargetT60(t60);
    }

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        Objects.requireNonNull(inputBuffer, "inputBuffer must not be null");
        Objects.requireNonNull(outputBuffer, "outputBuffer must not be null");
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
        // 1. Distribute host channels across FDN reverb sources (round-robin).
        Matrix inputView = (blockSize == framesPerBlock)
                ? fdnInput
                : new Matrix(numReverbSources, blockSize);
        if (blockSize == framesPerBlock) inputView.reset();

        double channelGain = 1.0 / channels;
        for (int src = 0; src < numReverbSources; src++) {
            int hostCh = src % channels;
            float[] in = inputBuffer[hostCh];
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

        // 3. Sum FDN outputs back into each host channel with wet/dry mix.
        double wetGain = mix / Math.max(1, numReverbSources / channels);
        double dryGain = 1.0 - mix;
        for (int ch = 0; ch < channels; ch++) {
            float[] in = inputBuffer[ch];
            float[] out = outputBuffer[ch];
            for (int n = 0; n < blockSize; n++) {
                double wet = 0.0;
                for (int src = 0; src < numReverbSources; src++) {
                    if ((src % channels) == ch) wet += outs.get(src).get(n);
                }
                out[offset + n] = (float) (dryGain * in[offset + n] + wetGain * wet);
            }
        }
    }

    @Override
    public void reset() {
        fdn.reset();
    }

    @Override
    public int getInputChannelCount() { return channels; }

    @Override
    public int getOutputChannelCount() { return channels; }

    /** Returns the underlying {@code daw-acoustics} FDN (for advanced control). */
    public FDN getFdn() { return fdn; }

    /** Returns the sample rate passed at construction, in Hz. */
    public double getSampleRate() { return sampleRate; }
}
