package com.benesquivelmusic.daw.core.mastering;

import com.benesquivelmusic.daw.core.dsp.GainReductionProvider;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.mastering.MasteringChainPreset;
import com.benesquivelmusic.daw.sdk.mastering.MasteringStageConfig;
import com.benesquivelmusic.daw.sdk.mastering.MasteringStageType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * An ordered mastering signal chain with preset management, per-stage
 * bypass/solo, and A/B comparison support.
 *
 * <p>Implements the mastering chain described in the mastering-techniques
 * research: Gain staging → EQ (corrective) → Compression → EQ (tonal)
 * → Stereo imaging → Limiting → Dithering.</p>
 *
 * <p>Each stage wraps an {@link AudioProcessor} and has an associated
 * {@link MasteringStageType}. Stages are processed in insertion order.
 * The chain supports:</p>
 * <ul>
 *   <li>Per-stage bypass — skip a single stage without removing it</li>
 *   <li>Per-stage solo — audition only a single stage</li>
 *   <li>A/B comparison — bypass the entire chain with optional gain matching</li>
 *   <li>Preset save/load — capture and restore full chain configuration</li>
 * </ul>
 */
public final class MasteringChain implements AudioProcessor {

    private static final Logger LOG = Logger.getLogger(MasteringChain.class.getName());

    /** Default number of channels for stereo mastering. */
    private static final int DEFAULT_CHANNELS = 2;

    /**
     * A single stage in the mastering chain.
     */
    public static final class Stage {

        private final MasteringStageType type;
        private final String name;
        private final AudioProcessor processor;
        private boolean bypassed;
        private boolean solo;

        Stage(MasteringStageType type, String name, AudioProcessor processor) {
            this.type = Objects.requireNonNull(type, "type must not be null");
            this.name = Objects.requireNonNull(name, "name must not be null");
            this.processor = Objects.requireNonNull(processor, "processor must not be null");
        }

        /** Returns the stage type. */
        public MasteringStageType getType() { return type; }

        /** Returns the stage display name. */
        public String getName() { return name; }

        /** Returns the audio processor for this stage. */
        public AudioProcessor getProcessor() { return processor; }

        /** Returns whether this stage is bypassed. */
        public boolean isBypassed() { return bypassed; }

        /** Sets the bypass state for this stage. */
        public void setBypassed(boolean bypassed) { this.bypassed = bypassed; }

        /** Returns whether this stage is soloed. */
        public boolean isSolo() { return solo; }

        /** Sets the solo state for this stage. */
        public void setSolo(boolean solo) { this.solo = solo; }
    }

    private final List<Stage> stages = new ArrayList<>();
    private final int channels;
    private boolean chainBypassed;
    private double referenceGainDb;
    private float[][][] intermediateBuffers;
    private volatile boolean intermediateBufferWarningLogged;

    // Per-stage metering data: snapshot-publish pattern.
    // Audio thread writes to the snapshot arrays; UI thread reads them.
    // Each mutator (add/insert/remove) publishes new arrays via volatile ref.
    private volatile double[] stageInputPeakDb;
    private volatile double[] stageOutputPeakDb;
    private volatile double[] stageGainReductionDb;

    /**
     * Creates a mastering chain with the default stereo channel count (2).
     */
    public MasteringChain() {
        this(DEFAULT_CHANNELS);
    }

    /**
     * Creates a mastering chain with the specified channel count.
     *
     * @param channels the number of audio channels
     */
    public MasteringChain(int channels) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        this.channels = channels;
    }

    /**
     * Adds a stage to the end of the mastering chain.
     *
     * <p>Metering arrays are re-allocated to match the new stage count.</p>
     *
     * @param type      the mastering stage type
     * @param name      the display name
     * @param processor the audio processor
     */
    public void addStage(MasteringStageType type, String name, AudioProcessor processor) {
        stages.add(new Stage(type, name, processor));
        reallocateMeteringArrays();
    }

    /**
     * Inserts a stage at the specified index.
     *
     * <p>Metering arrays are re-allocated to match the new stage count.</p>
     *
     * @param index     the insertion index
     * @param type      the mastering stage type
     * @param name      the display name
     * @param processor the audio processor
     */
    public void insertStage(int index, MasteringStageType type, String name,
                            AudioProcessor processor) {
        stages.add(index, new Stage(type, name, processor));
        reallocateMeteringArrays();
    }

    /**
     * Removes the stage at the specified index.
     *
     * <p>Metering arrays are re-allocated to match the new stage count.</p>
     *
     * @param index the index of the stage to remove
     * @return the removed stage
     */
    public Stage removeStage(int index) {
        Stage removed = stages.remove(index);
        reallocateMeteringArrays();
        return removed;
    }

    /**
     * Returns an unmodifiable view of the stages.
     *
     * @return the list of stages
     */
    public List<Stage> getStages() {
        return Collections.unmodifiableList(stages);
    }

    /** Returns the number of stages. */
    public int size() {
        return stages.size();
    }

    /** Returns whether the chain has no stages. */
    public boolean isEmpty() {
        return stages.isEmpty();
    }

    /**
     * Returns whether the entire chain is bypassed (A/B comparison mode).
     */
    public boolean isChainBypassed() {
        return chainBypassed;
    }

    /**
     * Sets the chain bypass state for A/B comparison.
     *
     * <p>When bypassed, the input signal is passed through with the
     * reference gain applied, allowing gain-matched comparison against
     * the processed signal.</p>
     *
     * @param bypassed whether to bypass the entire chain
     */
    public void setChainBypassed(boolean bypassed) {
        this.chainBypassed = bypassed;
    }

    /**
     * Returns the reference gain in dB used during A/B bypass comparison.
     */
    public double getReferenceGainDb() {
        return referenceGainDb;
    }

    /**
     * Sets the reference gain in dB for gain-matched A/B comparison.
     *
     * <p>When the chain is bypassed, this gain is applied to the dry
     * signal so that level differences between processed and unprocessed
     * audio do not bias the comparison.</p>
     *
     * @param gainDb the reference gain in dB
     */
    public void setReferenceGainDb(double gainDb) {
        this.referenceGainDb = gainDb;
    }

    /**
     * Pre-allocates intermediate buffers for real-time-safe processing.
     *
     * @param channels the number of audio channels
     * @param frames   the number of sample frames per buffer
     */
    public void allocateIntermediateBuffers(int channels, int frames) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (frames <= 0) {
            throw new IllegalArgumentException("frames must be positive: " + frames);
        }
        int maxNeeded = Math.max(stages.size() - 1, 0);
        intermediateBuffers = new float[maxNeeded][channels][frames];
    }

    /**
     * Processes audio through the mastering chain.
     *
     * <p>Respects chain bypass (A/B mode), per-stage bypass, and solo.
     * Updates per-stage metering data (input/output peak levels and
     * gain reduction) after processing each active stage.</p>
     *
     * <p>This method is real-time safe when intermediate buffers have been
     * pre-allocated via {@link #allocateIntermediateBuffers(int, int)}
     * and metering arrays are pre-allocated via stage mutations.</p>
     *
     * @param inputBuffer  input audio data {@code [channel][frame]}
     * @param outputBuffer output audio data {@code [channel][frame]}
     * @param numFrames    the number of frames to process
     */
    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        if (chainBypassed || stages.isEmpty()) {
            copyWithGain(inputBuffer, outputBuffer, numFrames, referenceGainDb);
            return;
        }

        boolean hasSolo = stages.stream().anyMatch(Stage::isSolo);

        // Snapshot metering arrays (volatile read once)
        double[] inputPeaks = stageInputPeakDb;
        double[] outputPeaks = stageOutputPeakDb;
        double[] gainReductions = stageGainReductionDb;

        float[][] currentInput = inputBuffer;
        int activeIndex = 0;
        for (int stageIndex = 0; stageIndex < stages.size(); stageIndex++) {
            Stage stage = stages.get(stageIndex);

            // Skip inactive stages
            if (hasSolo) {
                if (!stage.isSolo()) continue;
            } else if (stage.isBypassed()) {
                continue;
            }

            float[][] currentOutput;
            // Determine if this is the last active stage by scanning ahead
            boolean isLastActive = isLastActiveStage(stageIndex, hasSolo);
            if (isLastActive) {
                currentOutput = outputBuffer;
            } else if (intermediateBuffers != null && activeIndex < intermediateBuffers.length) {
                currentOutput = intermediateBuffers[activeIndex];
                clearBuffer(currentOutput, numFrames);
            } else {
                // Intermediate buffers not pre-allocated — log warning once
                if (!intermediateBufferWarningLogged) {
                    intermediateBufferWarningLogged = true;
                    LOG.warning("Intermediate buffers not pre-allocated for MasteringChain; "
                            + "call allocateIntermediateBuffers() before processing");
                }
                currentOutput = outputBuffer;
            }

            // Measure input peak level
            updatePeak(inputPeaks, stageIndex, currentInput, numFrames);

            stage.getProcessor().process(currentInput, currentOutput, numFrames);

            // Measure output peak level
            updatePeak(outputPeaks, stageIndex, currentOutput, numFrames);

            // Read gain reduction from dynamics processors
            updateGainReduction(gainReductions, stageIndex, stage.getProcessor());

            currentInput = currentOutput;
            activeIndex++;
        }

        // If no stages were active, pass through
        if (activeIndex == 0) {
            copyBuffer(inputBuffer, outputBuffer, numFrames);
        }
    }

    /**
     * Returns whether the stage at the given index is the last active stage.
     */
    private boolean isLastActiveStage(int fromIndex, boolean hasSolo) {
        for (int i = fromIndex + 1; i < stages.size(); i++) {
            Stage s = stages.get(i);
            if (hasSolo) {
                if (s.isSolo()) return false;
            } else if (!s.isBypassed()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Resets all processors in the chain.
     */
    @Override
    public void reset() {
        for (Stage stage : stages) {
            stage.getProcessor().reset();
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

    // --- Metering accessors (read from UI thread) ---

    /**
     * Returns the input peak level in dB for the specified stage.
     *
     * @param stageIndex the stage index
     * @return the input peak level in dB, or {@code -120.0} if not available
     */
    public double getStageInputPeakDb(int stageIndex) {
        double[] peaks = stageInputPeakDb;
        return (peaks != null && stageIndex >= 0 && stageIndex < peaks.length)
                ? peaks[stageIndex] : -120.0;
    }

    /**
     * Returns the output peak level in dB for the specified stage.
     *
     * @param stageIndex the stage index
     * @return the output peak level in dB, or {@code -120.0} if not available
     */
    public double getStageOutputPeakDb(int stageIndex) {
        double[] peaks = stageOutputPeakDb;
        return (peaks != null && stageIndex >= 0 && stageIndex < peaks.length)
                ? peaks[stageIndex] : -120.0;
    }

    /**
     * Returns the gain reduction in dB for the specified stage.
     *
     * <p>Returns {@code 0.0} for stages that do not perform dynamics processing.</p>
     *
     * @param stageIndex the stage index
     * @return the gain reduction in dB (≤ 0), or {@code 0.0} if not applicable
     */
    public double getStageGainReductionDb(int stageIndex) {
        double[] gr = stageGainReductionDb;
        return (gr != null && stageIndex >= 0 && stageIndex < gr.length)
                ? gr[stageIndex] : 0.0;
    }

    /**
     * Captures the current chain configuration as a serializable preset.
     *
     * <p>Processor parameters are extracted using the supplied parameter
     * extractor function. If the extractor is {@code null}, stages are
     * saved with empty parameter maps.</p>
     *
     * @param presetName the name for the preset
     * @param genre      the genre tag
     * @param extractor  extracts parameters from a processor (may be {@code null})
     * @return a new preset capturing the current chain configuration
     */
    public MasteringChainPreset savePreset(String presetName, String genre,
                                           ParameterExtractor extractor) {
        List<MasteringStageConfig> configs = new ArrayList<>();
        for (Stage stage : stages) {
            Map<String, Double> params = (extractor != null)
                    ? extractor.extractParameters(stage.getProcessor())
                    : Map.of();
            configs.add(new MasteringStageConfig(
                    stage.getType(), stage.getName(), params, stage.isBypassed()));
        }
        return new MasteringChainPreset(presetName, genre, configs);
    }

    /**
     * Returns the stage configuration list matching the current chain state,
     * without requiring a parameter extractor.
     *
     * @param presetName the name for the preset
     * @param genre      the genre tag
     * @return a new preset with empty parameter maps
     */
    public MasteringChainPreset savePreset(String presetName, String genre) {
        return savePreset(presetName, genre, null);
    }

    /**
     * Functional interface for extracting parameter maps from processors.
     */
    @FunctionalInterface
    public interface ParameterExtractor {

        /**
         * Extracts the current parameter values from a processor.
         *
         * @param processor the audio processor
         * @return a map of parameter names to values
         */
        Map<String, Double> extractParameters(AudioProcessor processor);
    }

    // --- Private helpers ---

    private static void copyBuffer(float[][] src, float[][] dst, int numFrames) {
        int channels = Math.min(src.length, dst.length);
        for (int ch = 0; ch < channels; ch++) {
            System.arraycopy(src[ch], 0, dst[ch], 0, numFrames);
        }
    }

    private static void copyWithGain(float[][] src, float[][] dst, int numFrames, double gainDb) {
        int channels = Math.min(src.length, dst.length);
        if (gainDb == 0.0) {
            copyBuffer(src, dst, numFrames);
            return;
        }
        double gainLinear = Math.pow(10.0, gainDb / 20.0);
        for (int ch = 0; ch < channels; ch++) {
            for (int i = 0; i < numFrames; i++) {
                dst[ch][i] = (float) (src[ch][i] * gainLinear);
            }
        }
    }

    private static void clearBuffer(float[][] buffer, int numFrames) {
        for (float[] channel : buffer) {
            Arrays.fill(channel, 0, numFrames, 0.0f);
        }
    }

    // --- Metering helpers ---

    /**
     * Allocates new metering arrays matching the current stage count.
     * Called from add/insert/remove stage — never from the audio thread.
     * Publishes via volatile reference for lock-free cross-thread reads.
     */
    private void reallocateMeteringArrays() {
        int n = stages.size();
        double[] newInput = new double[n];
        double[] newOutput = new double[n];
        double[] newGr = new double[n];
        Arrays.fill(newInput, -120.0);
        Arrays.fill(newOutput, -120.0);
        // Publish all three via volatile writes
        stageInputPeakDb = newInput;
        stageOutputPeakDb = newOutput;
        stageGainReductionDb = newGr;
    }

    private static void updatePeak(double[] peaks, int stageIndex,
                                    float[][] buffer, int numFrames) {
        if (peaks != null && stageIndex >= 0 && stageIndex < peaks.length) {
            peaks[stageIndex] = measurePeakDb(buffer, numFrames);
        }
    }

    private static void updateGainReduction(double[] gr, int stageIndex,
                                             AudioProcessor processor) {
        if (gr != null && stageIndex >= 0 && stageIndex < gr.length) {
            if (processor instanceof GainReductionProvider provider) {
                gr[stageIndex] = provider.getGainReductionDb();
            } else {
                gr[stageIndex] = 0.0;
            }
        }
    }

    private static double measurePeakDb(float[][] buffer, int numFrames) {
        double peak = 0.0;
        for (float[] channel : buffer) {
            for (int i = 0; i < numFrames; i++) {
                double abs = Math.abs(channel[i]);
                if (abs > peak) {
                    peak = abs;
                }
            }
        }
        return (peak > 0.0) ? 20.0 * Math.log10(peak) : -120.0;
    }
}
