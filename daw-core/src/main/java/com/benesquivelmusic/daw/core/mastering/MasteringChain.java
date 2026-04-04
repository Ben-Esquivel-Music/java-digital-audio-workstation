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

    // Per-stage metering data (written on audio thread, read on UI thread)
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
     * @param type      the mastering stage type
     * @param name      the display name
     * @param processor the audio processor
     */
    public void addStage(MasteringStageType type, String name, AudioProcessor processor) {
        stages.add(new Stage(type, name, processor));
    }

    /**
     * Inserts a stage at the specified index.
     *
     * @param index     the insertion index
     * @param type      the mastering stage type
     * @param name      the display name
     * @param processor the audio processor
     */
    public void insertStage(int index, MasteringStageType type, String name,
                            AudioProcessor processor) {
        stages.add(index, new Stage(type, name, processor));
    }

    /**
     * Removes the stage at the specified index.
     *
     * @param index the index of the stage to remove
     * @return the removed stage
     */
    public Stage removeStage(int index) {
        return stages.remove(index);
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
        List<Stage> activeStages = new ArrayList<>();
        for (Stage stage : stages) {
            if (hasSolo) {
                if (stage.isSolo()) {
                    activeStages.add(stage);
                }
            } else if (!stage.isBypassed()) {
                activeStages.add(stage);
            }
        }

        if (activeStages.isEmpty()) {
            copyBuffer(inputBuffer, outputBuffer, numFrames);
            return;
        }

        ensureMeteringArrays();

        float[][] currentInput = inputBuffer;
        for (int i = 0; i < activeStages.size(); i++) {
            Stage stage = activeStages.get(i);
            int stageIndex = stages.indexOf(stage);

            float[][] currentOutput;
            if (i == activeStages.size() - 1) {
                currentOutput = outputBuffer;
            } else if (intermediateBuffers != null && i < intermediateBuffers.length) {
                currentOutput = intermediateBuffers[i];
                clearBuffer(currentOutput, numFrames);
            } else {
                currentOutput = new float[outputBuffer.length][numFrames];
            }

            // Measure input peak level
            updateInputPeak(stageIndex, currentInput, numFrames);

            stage.getProcessor().process(currentInput, currentOutput, numFrames);

            // Measure output peak level
            updateOutputPeak(stageIndex, currentOutput, numFrames);

            // Read gain reduction from dynamics processors
            updateGainReduction(stageIndex, stage.getProcessor());

            currentInput = currentOutput;
        }
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

    // --- Metering helpers (called on audio thread) ---

    private void ensureMeteringArrays() {
        int n = stages.size();
        if (stageInputPeakDb == null || stageInputPeakDb.length != n) {
            stageInputPeakDb = new double[n];
            stageOutputPeakDb = new double[n];
            stageGainReductionDb = new double[n];
            Arrays.fill(stageInputPeakDb, -120.0);
            Arrays.fill(stageOutputPeakDb, -120.0);
        }
    }

    private void updateInputPeak(int stageIndex, float[][] buffer, int numFrames) {
        double[] peaks = stageInputPeakDb;
        if (peaks != null && stageIndex < peaks.length) {
            peaks[stageIndex] = measurePeakDb(buffer, numFrames);
        }
    }

    private void updateOutputPeak(int stageIndex, float[][] buffer, int numFrames) {
        double[] peaks = stageOutputPeakDb;
        if (peaks != null && stageIndex < peaks.length) {
            peaks[stageIndex] = measurePeakDb(buffer, numFrames);
        }
    }

    private void updateGainReduction(int stageIndex, AudioProcessor processor) {
        double[] gr = stageGainReductionDb;
        if (gr != null && stageIndex < gr.length) {
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
