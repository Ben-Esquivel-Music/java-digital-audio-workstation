package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

/**
 * Psychoacoustic bass enhancement processor that generates harmonics of
 * low-frequency content to create the perception of bass on playback
 * systems with limited low-frequency reproduction.
 *
 * <p>Uses the psychoacoustic "missing fundamental" effect — when harmonics
 * of a fundamental are present, the brain perceives the fundamental even
 * when it is absent.</p>
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Isolate low-frequency content using a {@link BiquadFilter} lowpass
 *       at a configurable crossover frequency (40–120 Hz).</li>
 *   <li>Generate harmonics via half-wave rectification of the isolated bass
 *       signal, producing 2nd, 3rd, and 4th harmonics.</li>
 *   <li>Bandpass-filter the generated harmonics to suppress sub-harmonic
 *       artifacts and high-order distortion.</li>
 *   <li>Mix the filtered harmonics back with the original signal at a
 *       configurable harmonic level and dry/wet mix.</li>
 * </ol>
 *
 * <h2>Parameters</h2>
 * <ul>
 *   <li><b>Crossover frequency</b> — The cutoff frequency (40–120 Hz) for
 *       isolating bass content.</li>
 *   <li><b>Harmonic order</b> — Maximum harmonic to generate (2, 3, or 4).</li>
 *   <li><b>Harmonic level</b> — Linear gain applied to the generated harmonics
 *       (0.0–1.0).</li>
 *   <li><b>Dry/wet mix</b> — Balance between the original signal and the
 *       enhanced signal (0.0 = fully dry, 1.0 = fully wet).</li>
 * </ul>
 *
 * <h2>AES Research References</h2>
 * <ul>
 *   <li>Advances in Perceptual Bass Extension for Music and Cinematic Content (2023)</li>
 *   <li>Physiological measurement of the arousing effect of bass amplification in music (2024)</li>
 * </ul>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
public final class BassExtensionProcessor implements AudioProcessor {

    /** Minimum allowed crossover frequency in Hz. */
    public static final double MIN_CROSSOVER_HZ = 40.0;

    /** Maximum allowed crossover frequency in Hz. */
    public static final double MAX_CROSSOVER_HZ = 120.0;

    /** Minimum allowed harmonic order. */
    public static final int MIN_HARMONIC_ORDER = 2;

    /** Maximum allowed harmonic order. */
    public static final int MAX_HARMONIC_ORDER = 4;

    private static final double BUTTERWORTH_Q = 0.707;

    private final int channels;
    private final double sampleRate;

    private double crossoverHz;
    private int harmonicOrder;
    private double harmonicLevel;
    private double mix;

    // Per-channel lowpass filters for bass isolation
    private volatile BiquadFilter[] bassIsolationLp;

    // Per-channel bandpass filters for harmonic shaping (one per channel)
    // Passes the harmonic range: crossover to harmonicOrder × crossover
    private volatile BiquadFilter[] harmonicBpLow;
    private volatile BiquadFilter[] harmonicBpHigh;

    /**
     * Creates a bass extension processor with default settings.
     *
     * <p>Defaults: 80 Hz crossover, harmonic order 3, harmonic level 0.5,
     * 100% wet mix.</p>
     *
     * @param channels   number of audio channels (must be positive)
     * @param sampleRate the sample rate in Hz (must be positive)
     */
    public BassExtensionProcessor(int channels, double sampleRate) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.crossoverHz = 80.0;
        this.harmonicOrder = 3;
        this.harmonicLevel = 0.5;
        this.mix = 1.0;

        rebuildFilters();
    }

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        int activeCh = Math.min(channels, Math.min(inputBuffer.length, outputBuffer.length));

        if (mix == 0.0 || harmonicLevel == 0.0) {
            for (int ch = 0; ch < activeCh; ch++) {
                System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
            }
            return;
        }

        // Snapshot volatile filter references for safe concurrent access
        BiquadFilter[] lpFilters = bassIsolationLp;
        BiquadFilter[] bpLow = harmonicBpLow;
        BiquadFilter[] bpHigh = harmonicBpHigh;

        for (int ch = 0; ch < activeCh; ch++) {
            for (int frame = 0; frame < numFrames; frame++) {
                float dry = inputBuffer[ch][frame];

                // 1. Isolate bass via lowpass filter
                float bass = lpFilters[ch].processSample(dry);

                // 2. Generate harmonics via half-wave rectification
                //    Half-wave rectification of a sinusoid produces a DC component,
                //    the fundamental, and higher harmonics (including 2nd, 3rd, 4th, ...).
                float rectified = Math.max(0.0f, bass);

                // 3. Polynomial waveshaping to emphasize selected harmonics.
                //    x^2 generates 2nd harmonic; x^3 adds 3rd; x^4 adds 4th.
                float harmonicSignal = switch (harmonicOrder) {
                    case 2 -> rectified * rectified;
                    case 3 -> rectified * rectified + 0.5f * rectified * rectified * rectified;
                    case 4 -> rectified * rectified
                            + 0.5f * rectified * rectified * rectified
                            + 0.25f * rectified * rectified * rectified * rectified;
                    default -> rectified * rectified;
                };

                // 4. Bandpass filter harmonics to suppress sub-harmonic artifacts
                //    and high-order distortion
                harmonicSignal = bpLow[ch].processSample(harmonicSignal);
                harmonicSignal = bpHigh[ch].processSample(harmonicSignal);

                // 5. Scale harmonics by harmonic level
                float enhanced = dry + harmonicSignal * (float) harmonicLevel;

                // 6. Dry/wet mix
                outputBuffer[ch][frame] = (float) (dry * (1.0 - mix) + enhanced * mix);
            }
        }
    }

    // --- Parameter accessors ---

    /**
     * Returns the crossover frequency in Hz.
     *
     * @return crossover frequency
     */
    @ProcessorParam(id = 0, name = "Crossover", min = 40.0, max = 120.0, defaultValue = 80.0, unit = "Hz")
    public double getCrossoverHz() {
        return crossoverHz;
    }

    /**
     * Sets the crossover frequency for bass isolation.
     *
     * @param crossoverHz crossover frequency in the range [40, 120] Hz
     */
    public void setCrossoverHz(double crossoverHz) {
        if (crossoverHz < MIN_CROSSOVER_HZ || crossoverHz > MAX_CROSSOVER_HZ) {
            throw new IllegalArgumentException(
                    "crossoverHz must be in [" + MIN_CROSSOVER_HZ + ", "
                            + MAX_CROSSOVER_HZ + "]: " + crossoverHz);
        }
        this.crossoverHz = crossoverHz;
        rebuildFilters();
    }

    /**
     * Returns the maximum harmonic order.
     *
     * @return harmonic order (2, 3, or 4)
     */
    public int getHarmonicOrder() {
        return harmonicOrder;
    }

    /**
     * Sets the maximum harmonic order for generation.
     *
     * @param harmonicOrder 2, 3, or 4
     */
    public void setHarmonicOrder(int harmonicOrder) {
        if (harmonicOrder < MIN_HARMONIC_ORDER || harmonicOrder > MAX_HARMONIC_ORDER) {
            throw new IllegalArgumentException(
                    "harmonicOrder must be in [" + MIN_HARMONIC_ORDER + ", "
                            + MAX_HARMONIC_ORDER + "]: " + harmonicOrder);
        }
        this.harmonicOrder = harmonicOrder;
        rebuildFilters();
    }

    /**
     * Returns the harmonic level (linear gain applied to generated harmonics).
     *
     * @return harmonic level in [0.0, 1.0]
     */
    @ProcessorParam(id = 1, name = "Harmonic Level", min = 0.0, max = 1.0, defaultValue = 0.5)
    public double getHarmonicLevel() {
        return harmonicLevel;
    }

    /**
     * Sets the harmonic level.
     *
     * @param harmonicLevel linear gain in [0.0, 1.0]
     */
    public void setHarmonicLevel(double harmonicLevel) {
        if (harmonicLevel < 0.0 || harmonicLevel > 1.0) {
            throw new IllegalArgumentException(
                    "harmonicLevel must be in [0.0, 1.0]: " + harmonicLevel);
        }
        this.harmonicLevel = harmonicLevel;
    }

    /**
     * Returns the dry/wet mix.
     *
     * @return mix in [0.0, 1.0]
     */
    @ProcessorParam(id = 2, name = "Mix", min = 0.0, max = 1.0, defaultValue = 1.0)
    public double getMix() {
        return mix;
    }

    /**
     * Sets the dry/wet mix.
     *
     * @param mix 0.0 = fully dry, 1.0 = fully wet (enhanced)
     */
    public void setMix(double mix) {
        if (mix < 0.0 || mix > 1.0) {
            throw new IllegalArgumentException("mix must be in [0.0, 1.0]: " + mix);
        }
        this.mix = mix;
    }

    @Override
    public void reset() {
        for (int ch = 0; ch < channels; ch++) {
            bassIsolationLp[ch].reset();
            harmonicBpLow[ch].reset();
            harmonicBpHigh[ch].reset();
        }
    }

    @Override
    public int getInputChannelCount() {
        return channels;
    }

    @Override
    public int getOutputChannelCount() {
        return channels;
    }

    /**
     * Rebuilds all internal filters based on current crossover and harmonic
     * order settings.
     */
    private void rebuildFilters() {
        // Build fully-populated local arrays before assigning to instance fields
        // so that process() on the audio thread never observes partially-initialized arrays.
        BiquadFilter[] newBassIsolationLp = new BiquadFilter[channels];
        BiquadFilter[] newHarmonicBpLow = new BiquadFilter[channels];
        BiquadFilter[] newHarmonicBpHigh = new BiquadFilter[channels];

        // Harmonic bandpass range: from crossover up to harmonicOrder × crossover.
        // The highpass at crossoverHz suppresses the DC and sub-fundamental content
        // created by rectification. The lowpass at harmonicOrder × crossoverHz
        // suppresses high-order distortion above the desired harmonic range.
        double bpHighCutoff = Math.min(harmonicOrder * crossoverHz, sampleRate * 0.45);

        for (int ch = 0; ch < channels; ch++) {
            newBassIsolationLp[ch] = BiquadFilter.create(
                    BiquadFilter.FilterType.LOW_PASS, sampleRate, crossoverHz,
                    BUTTERWORTH_Q, 0);

            newHarmonicBpLow[ch] = BiquadFilter.create(
                    BiquadFilter.FilterType.HIGH_PASS, sampleRate, crossoverHz,
                    BUTTERWORTH_Q, 0);

            newHarmonicBpHigh[ch] = BiquadFilter.create(
                    BiquadFilter.FilterType.LOW_PASS, sampleRate, bpHighCutoff,
                    BUTTERWORTH_Q, 0);
        }

        // Publish fully-populated arrays via volatile writes
        bassIsolationLp = newBassIsolationLp;
        harmonicBpLow = newHarmonicBpLow;
        harmonicBpHigh = newHarmonicBpHigh;
    }
}
