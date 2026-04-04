package com.benesquivelmusic.daw.core.spatial;

import com.benesquivelmusic.daw.core.dsp.CrossoverFilter;
import com.benesquivelmusic.daw.core.dsp.MidSideEncoder;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLabel;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 2D-to-3D ambience upmixer that extracts the ambient/diffuse component from
 * a stereo signal and spatially distributes it into height channels, creating
 * an immersive 3D sound field from 2D source material.
 *
 * <p>This enables existing stereo content to be expanded into immersive
 * formats like 5.1.4 or 7.1.4 Atmos beds by routing frequency-weighted
 * ambient content to overhead speakers.</p>
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Mid/side decomposition using {@link MidSideEncoder} to separate
 *       direct (mid) and ambient (side) content</li>
 *   <li>Perceptual band allocation (PBA): split the ambient signal into
 *       frequency bands using {@link CrossoverFilter} crossovers</li>
 *   <li>Height routing: assign bands to height channels based on PBA weights
 *       — higher frequencies receive stronger overhead routing for maximum
 *       perceived envelopment</li>
 *   <li>Allpass-based decorrelation of each height channel output to prevent
 *       spatial collapse (Schroeder allpass structures with mutually coprime
 *       delay lengths)</li>
 * </ol>
 *
 * <h2>References</h2>
 * <ul>
 *   <li>2D-to-3D Ambience Upmixing based on Perceptual Band Allocation
 *       (AES, 2015)</li>
 *   <li>Perceptual Band Allocation (PBA) for the Rendering of Vertical Image
 *       Spread with a Vertical 2D Loudspeaker Array (AES, 2016)</li>
 *   <li>The Effect of Temporal and Directional Density on Listener
 *       Envelopment (AES, 2023)</li>
 * </ul>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
public final class AmbienceUpmixer implements AudioProcessor {

    /** Default PBA crossover frequencies in Hz. */
    private static final double[] DEFAULT_PBA_FREQUENCIES = {500.0, 2000.0, 8000.0};

    private static final int INPUT_CHANNELS = 2;

    /** Height speaker labels in standard immersive layouts. */
    private static final List<SpeakerLabel> HEIGHT_LABELS = List.of(
            SpeakerLabel.LTF, SpeakerLabel.RTF, SpeakerLabel.LTR, SpeakerLabel.RTR);

    /**
     * Polarity for each height channel — left-side heights get positive side
     * signal, right-side heights get negative, preserving spatial width.
     */
    private static final float[] HEIGHT_POLARITY = {1.0f, -1.0f, 1.0f, -1.0f};

    /**
     * Prime delay lengths (in samples) for Schroeder allpass decorrelators.
     * Chosen to be mutually coprime for maximum inter-channel decorrelation.
     */
    private static final int[] DECORRELATOR_DELAYS = {37, 113, 179, 271};

    /** Allpass gain coefficients — different per height channel. */
    private static final double[] DECORRELATOR_COEFFICIENTS = {0.7, -0.7, 0.5, -0.5};

    private final double sampleRate;
    private SpeakerLayout targetLayout;
    private int outputChannelCount;

    // Parameters
    private double ambientExtraction;
    private double heightLevel;
    private double decorrelationAmount;
    private double[] pbaFrequencies;
    private double[] pbaWeights;

    // Height channel indices in the target layout
    private int[] heightChannelIndices;
    private int activeHeightCount;

    // DSP state — crossover filters for ambient band splitting
    private CrossoverFilter[] crossovers;

    // Allpass decorrelator state — one per active height channel
    private SchroederAllpass[] decorrelators;

    // Pre-allocated work buffers
    private int allocatedFrames;
    private float[] midBuffer;
    private float[] sideBuffer;
    private float[][] bandBuffers;
    private float[] tempLow;
    private float[] tempHigh;
    private float[] decorrelatorTemp;
    private float[] pbaSumBuffer;

    /**
     * Creates an ambience upmixer with default parameters targeting 7.1.4 layout.
     *
     * @param sampleRate the audio sample rate in Hz
     * @throws IllegalArgumentException if sampleRate is not positive
     */
    public AmbienceUpmixer(double sampleRate) {
        this(sampleRate, SpeakerLayout.LAYOUT_7_1_4);
    }

    /**
     * Creates an ambience upmixer targeting the specified speaker layout.
     *
     * <p>Default parameters: ambient extraction = 0.5, height level = 0.7,
     * decorrelation amount = 0.8, PBA crossovers at 500/2000/8000 Hz.</p>
     *
     * @param sampleRate   the audio sample rate in Hz
     * @param targetLayout the target immersive speaker layout
     * @throws IllegalArgumentException if sampleRate is not positive
     * @throws NullPointerException     if targetLayout is null
     */
    public AmbienceUpmixer(double sampleRate, SpeakerLayout targetLayout) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        Objects.requireNonNull(targetLayout, "targetLayout must not be null");

        this.sampleRate = sampleRate;
        this.ambientExtraction = 0.5;
        this.heightLevel = 0.7;
        this.decorrelationAmount = 0.8;
        this.targetLayout = targetLayout;
        this.outputChannelCount = targetLayout.channelCount();

        rebuildHeightChannelIndices();
        setPbaFrequencies(DEFAULT_PBA_FREQUENCIES.clone());
    }

    // ---- Parameter Accessors ----

    /**
     * Returns the ambient extraction amount.
     *
     * @return value in [0.0, 1.0]
     */
    public double getAmbientExtraction() {
        return ambientExtraction;
    }

    /**
     * Sets the ambient extraction amount. At 0.0, no ambient content is
     * extracted (pass-through). At 1.0, maximum ambient content is routed
     * to height channels.
     *
     * @param amount value in [0.0, 1.0]
     * @throws IllegalArgumentException if amount is out of range
     */
    public void setAmbientExtraction(double amount) {
        if (amount < 0.0 || amount > 1.0) {
            throw new IllegalArgumentException(
                    "ambientExtraction must be in [0.0, 1.0]: " + amount);
        }
        this.ambientExtraction = amount;
    }

    /**
     * Returns the height channel level.
     *
     * @return value in [0.0, 1.0]
     */
    public double getHeightLevel() {
        return heightLevel;
    }

    /**
     * Sets the height channel output level.
     *
     * @param level value in [0.0, 1.0]
     * @throws IllegalArgumentException if level is out of range
     */
    public void setHeightLevel(double level) {
        if (level < 0.0 || level > 1.0) {
            throw new IllegalArgumentException(
                    "heightLevel must be in [0.0, 1.0]: " + level);
        }
        this.heightLevel = level;
    }

    /**
     * Returns the decorrelation amount.
     *
     * @return value in [0.0, 1.0]
     */
    public double getDecorrelationAmount() {
        return decorrelationAmount;
    }

    /**
     * Sets the decorrelation amount. Higher values create more spatial
     * separation between height channels, preventing spatial collapse.
     *
     * @param amount value in [0.0, 1.0]
     * @throws IllegalArgumentException if amount is out of range
     */
    public void setDecorrelationAmount(double amount) {
        if (amount < 0.0 || amount > 1.0) {
            throw new IllegalArgumentException(
                    "decorrelationAmount must be in [0.0, 1.0]: " + amount);
        }
        this.decorrelationAmount = amount;
    }

    /**
     * Returns the target speaker layout.
     *
     * @return the current target layout
     */
    public SpeakerLayout getTargetLayout() {
        return targetLayout;
    }

    /**
     * Sets the target speaker layout. Height channels are automatically
     * detected from the layout.
     *
     * @param layout the target speaker layout
     * @throws NullPointerException if layout is null
     */
    public void setTargetLayout(SpeakerLayout layout) {
        Objects.requireNonNull(layout, "layout must not be null");
        this.targetLayout = layout;
        this.outputChannelCount = layout.channelCount();
        rebuildHeightChannelIndices();
    }

    /**
     * Returns a copy of the current PBA crossover frequencies.
     *
     * @return crossover frequencies in Hz (ascending order)
     */
    public double[] getPbaFrequencies() {
        return pbaFrequencies.clone();
    }

    /**
     * Sets the PBA crossover frequencies for band splitting. The number of
     * resulting bands is {@code frequencies.length + 1}. Default PBA weights
     * are recomputed as a linear ramp (lower bands → less height routing,
     * higher bands → more height routing).
     *
     * @param frequencies crossover frequencies in Hz, ascending order
     * @throws IllegalArgumentException if frequencies are empty, out of range,
     *                                  or not in ascending order
     * @throws NullPointerException     if frequencies is null
     */
    public void setPbaFrequencies(double[] frequencies) {
        Objects.requireNonNull(frequencies, "frequencies must not be null");
        if (frequencies.length == 0) {
            throw new IllegalArgumentException("frequencies must not be empty");
        }
        for (int i = 0; i < frequencies.length; i++) {
            if (frequencies[i] <= 0 || frequencies[i] >= sampleRate / 2.0) {
                throw new IllegalArgumentException(
                        "frequency out of range: " + frequencies[i]);
            }
            if (i > 0 && frequencies[i] <= frequencies[i - 1]) {
                throw new IllegalArgumentException(
                        "frequencies must be in ascending order");
            }
        }
        this.pbaFrequencies = frequencies.clone();
        this.pbaWeights = computeDefaultPbaWeights(frequencies.length + 1);
        rebuildCrossovers();
    }

    /**
     * Returns a copy of the current PBA per-band height routing weights.
     *
     * @return per-band weights in [0.0, 1.0]
     */
    public double[] getPbaWeights() {
        return pbaWeights.clone();
    }

    /**
     * Sets custom PBA per-band height routing weights.
     *
     * @param weights per-band weights in [0.0, 1.0]; length must equal
     *                {@code pbaFrequencies.length + 1}
     * @throws IllegalArgumentException if weights length mismatches or
     *                                  values are out of range
     * @throws NullPointerException     if weights is null
     */
    public void setPbaWeights(double[] weights) {
        Objects.requireNonNull(weights, "weights must not be null");
        if (weights.length != pbaFrequencies.length + 1) {
            throw new IllegalArgumentException(
                    "weights.length must be pbaFrequencies.length + 1");
        }
        for (double w : weights) {
            if (w < 0.0 || w > 1.0) {
                throw new IllegalArgumentException(
                        "PBA weight must be in [0.0, 1.0]: " + w);
            }
        }
        this.pbaWeights = weights.clone();
    }

    /**
     * Returns the number of active height channels in the current target layout.
     *
     * @return the height channel count
     */
    public int getActiveHeightCount() {
        return activeHeightCount;
    }

    // ---- AudioProcessor Implementation ----

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        ensureBuffers(numFrames);

        // Zero all provided output channels so any host-supplied channels not
        // used by the current target layout do not retain stale audio.
        for (int ch = 0; ch < outputBuffer.length; ch++) {
            Arrays.fill(outputBuffer[ch], 0, numFrames, 0.0f);
        }

        if (inputBuffer.length == 0) {
            return;
        }

        if (inputBuffer.length < INPUT_CHANNELS) {
            // Mono or unexpected input — pass through to the layout's primary
            // front speaker (prefer L, then C, then first available output).
            int monoIdx = targetLayout.indexOf(SpeakerLabel.L);
            if (monoIdx < 0) {
                monoIdx = targetLayout.indexOf(SpeakerLabel.C);
            }
            if (monoIdx < 0 && outputBuffer.length > 0) {
                monoIdx = 0;
            }
            if (monoIdx >= 0 && monoIdx < outputBuffer.length) {
                System.arraycopy(inputBuffer[0], 0, outputBuffer[monoIdx], 0, numFrames);
            }
            return;
        }

        // When the target layout has no height channels, ambient extraction
        // has nowhere to go — preserve audible output in the base layout.
        if (activeHeightCount == 0) {
            int lIdx = targetLayout.indexOf(SpeakerLabel.L);
            int rIdx = targetLayout.indexOf(SpeakerLabel.R);
            boolean wroteOutput = false;

            if (lIdx >= 0 && lIdx < outputBuffer.length) {
                System.arraycopy(inputBuffer[0], 0, outputBuffer[lIdx], 0, numFrames);
                wroteOutput = true;
            }
            if (rIdx >= 0 && rIdx < outputBuffer.length) {
                System.arraycopy(inputBuffer[1], 0, outputBuffer[rIdx], 0, numFrames);
                wroteOutput = true;
            }

            if (!wroteOutput) {
                int cIdx = targetLayout.indexOf(SpeakerLabel.C);
                int fallbackIdx = (cIdx >= 0 && cIdx < outputBuffer.length) ? cIdx
                        : (outputBuffer.length > 0 ? 0 : -1);

                if (fallbackIdx >= 0) {
                    float[] out = outputBuffer[fallbackIdx];
                    float[] inL = inputBuffer[0];
                    float[] inR = inputBuffer[1];
                    for (int i = 0; i < numFrames; i++) {
                        out[i] = 0.5f * (inL[i] + inR[i]);
                    }
                }
            }
            return;
        }

        // Step 1: M/S encode
        MidSideEncoder.encode(inputBuffer[0], inputBuffer[1],
                midBuffer, sideBuffer, numFrames);

        // Step 2: Output direct signal to L/R with ambient reduced
        //   L = mid + side * (1 - ambientExtraction)
        //   R = mid - side * (1 - ambientExtraction)
        float sideRetain = (float) (1.0 - ambientExtraction);
        int lIdx = targetLayout.indexOf(SpeakerLabel.L);
        int rIdx = targetLayout.indexOf(SpeakerLabel.R);

        for (int i = 0; i < numFrames; i++) {
            float directL = midBuffer[i] + sideBuffer[i] * sideRetain;
            float directR = midBuffer[i] - sideBuffer[i] * sideRetain;
            if (lIdx >= 0 && lIdx < outputBuffer.length) {
                outputBuffer[lIdx][i] = directL;
            }
            if (rIdx >= 0 && rIdx < outputBuffer.length) {
                outputBuffer[rIdx][i] = directR;
            }
        }

        // If no extraction, we're done (L/R already have the full signal)
        if (ambientExtraction == 0.0) {
            return;
        }

        // Step 3: Extract ambient signal for height routing
        float extractGain = (float) ambientExtraction;
        for (int i = 0; i < numFrames; i++) {
            sideBuffer[i] *= extractGain;
        }

        // Step 4: Split ambient into frequency bands via PBA crossovers
        splitIntoBands(sideBuffer, numFrames);

        // Step 5: Compute PBA-weighted band sum once (same across all heights)
        float hGain = (float) heightLevel;
        float decorrelGain = (float) decorrelationAmount;
        float dryGain = 1.0f - decorrelGain;

        for (int i = 0; i < numFrames; i++) {
            float sum = 0.0f;
            for (int b = 0; b < bandBuffers.length; b++) {
                sum += bandBuffers[b][i] * (float) pbaWeights[b];
            }
            pbaSumBuffer[i] = sum * hGain;
        }

        // Step 6: Route to height channels with per-channel polarity and decorrelation
        for (int h = 0; h < heightChannelIndices.length; h++) {
            int outCh = heightChannelIndices[h];
            if (outCh < 0 || outCh >= outputBuffer.length) {
                continue;
            }

            float polarity = HEIGHT_POLARITY[h % HEIGHT_POLARITY.length];

            // Apply polarity to the pre-computed band sum
            for (int i = 0; i < numFrames; i++) {
                decorrelatorTemp[i] = pbaSumBuffer[i] * polarity;
            }

            // Apply decorrelation (dry/wet blend with Schroeder allpass)
            if (decorrelGain > 0 && h < decorrelators.length) {
                SchroederAllpass allpass = decorrelators[h];
                for (int i = 0; i < numFrames; i++) {
                    float dry = decorrelatorTemp[i];
                    float wet = allpass.processSample(dry);
                    outputBuffer[outCh][i] += dry * dryGain + wet * decorrelGain;
                }
            } else {
                for (int i = 0; i < numFrames; i++) {
                    outputBuffer[outCh][i] += decorrelatorTemp[i];
                }
            }
        }
    }

    @Override
    public void reset() {
        if (crossovers != null) {
            for (CrossoverFilter xo : crossovers) {
                xo.reset();
            }
        }
        if (decorrelators != null) {
            for (SchroederAllpass ap : decorrelators) {
                ap.reset();
            }
        }
    }

    @Override
    public int getInputChannelCount() {
        return INPUT_CHANNELS;
    }

    @Override
    public int getOutputChannelCount() {
        return outputChannelCount;
    }

    // ---- Internal Helpers ----

    private void rebuildHeightChannelIndices() {
        List<Integer> indices = new ArrayList<>();
        for (SpeakerLabel label : HEIGHT_LABELS) {
            int idx = targetLayout.indexOf(label);
            if (idx >= 0) {
                indices.add(idx);
            }
        }
        heightChannelIndices = indices.stream().mapToInt(Integer::intValue).toArray();
        activeHeightCount = heightChannelIndices.length;
        rebuildDecorrelators();
    }

    private void rebuildCrossovers() {
        crossovers = new CrossoverFilter[pbaFrequencies.length];
        for (int i = 0; i < pbaFrequencies.length; i++) {
            crossovers[i] = new CrossoverFilter(sampleRate, pbaFrequencies[i]);
        }
        allocatedFrames = 0; // force buffer reallocation
    }

    private void rebuildDecorrelators() {
        decorrelators = new SchroederAllpass[activeHeightCount];
        for (int i = 0; i < activeHeightCount; i++) {
            int delay = DECORRELATOR_DELAYS[i % DECORRELATOR_DELAYS.length];
            double coeff = DECORRELATOR_COEFFICIENTS[i % DECORRELATOR_COEFFICIENTS.length];
            decorrelators[i] = new SchroederAllpass(delay, coeff);
        }
    }

    private static double[] computeDefaultPbaWeights(int numBands) {
        if (numBands == 1) {
            return new double[]{1.0};
        }
        double[] weights = new double[numBands];
        for (int i = 0; i < numBands; i++) {
            weights[i] = (double) i / (numBands - 1);
        }
        return weights;
    }

    private void ensureBuffers(int numFrames) {
        if (numFrames <= allocatedFrames) {
            return;
        }
        allocatedFrames = numFrames;
        midBuffer = new float[numFrames];
        sideBuffer = new float[numFrames];
        tempLow = new float[numFrames];
        tempHigh = new float[numFrames];
        decorrelatorTemp = new float[numFrames];
        pbaSumBuffer = new float[numFrames];
        int numBands = pbaFrequencies.length + 1;
        bandBuffers = new float[numBands][numFrames];
    }

    /**
     * Splits the ambient signal into frequency bands using the cascaded
     * crossover filter chain. Band 0 is the lowest, band N is the highest.
     */
    private void splitIntoBands(float[] ambient, int numFrames) {
        System.arraycopy(ambient, 0, tempLow, 0, numFrames);

        for (int c = 0; c < crossovers.length; c++) {
            crossovers[c].process(tempLow, bandBuffers[c], tempHigh, 0, numFrames);

            if (c < crossovers.length - 1) {
                System.arraycopy(tempHigh, 0, tempLow, 0, numFrames);
            } else {
                System.arraycopy(tempHigh, 0, bandBuffers[c + 1], 0, numFrames);
            }
        }
    }

    // ---- Schroeder Allpass Decorrelator ----

    /**
     * A Schroeder allpass structure for audio decorrelation.
     *
     * <p>Transfer function:
     * {@code H(z) = (-g + z^{-M}) / (1 - g·z^{-M})}</p>
     *
     * <p>Passes all frequencies with unity magnitude but applies
     * frequency-dependent phase shift. Using different delay lengths per
     * channel creates inter-channel decorrelation — preventing spatial
     * collapse when routing the same signal to multiple speakers.</p>
     */
    static final class SchroederAllpass {

        private final int delay;
        private final double gain;
        private final float[] buffer;
        private int writePos;

        SchroederAllpass(int delay, double gain) {
            if (delay <= 0) {
                throw new IllegalArgumentException("delay must be positive: " + delay);
            }
            this.delay = delay;
            this.gain = gain;
            this.buffer = new float[delay];
            this.writePos = 0;
        }

        float processSample(float input) {
            float delayed = buffer[writePos];
            float vn = (float) (input + gain * delayed);
            float output = (float) (-gain * vn + delayed);
            buffer[writePos] = vn;
            writePos = (writePos + 1) % delay;
            return output;
        }

        void reset() {
            Arrays.fill(buffer, 0.0f);
            writePos = 0;
        }
    }
}
