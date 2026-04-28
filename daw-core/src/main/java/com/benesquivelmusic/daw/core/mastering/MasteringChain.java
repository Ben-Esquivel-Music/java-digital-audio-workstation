package com.benesquivelmusic.daw.core.mastering;

import com.benesquivelmusic.daw.core.dsp.GainReductionProvider;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.mastering.MasteringChainPreset;
import com.benesquivelmusic.daw.sdk.mastering.MasteringStageConfig;
import com.benesquivelmusic.daw.sdk.mastering.MasteringStageType;

import java.util.*;
import java.util.concurrent.atomic.AtomicLongArray;
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
        private final boolean terminal;
        private boolean bypassed;
        private boolean solo;

        Stage(MasteringStageType type, String name, AudioProcessor processor) {
            this(type, name, processor, type == MasteringStageType.DITHERING);
        }

        Stage(MasteringStageType type, String name, AudioProcessor processor, boolean terminal) {
            this.type = Objects.requireNonNull(type, "type must not be null");
            this.name = Objects.requireNonNull(name, "name must not be null");
            this.processor = Objects.requireNonNull(processor, "processor must not be null");
            this.terminal = terminal;
        }

        /** Returns the stage type. */
        public MasteringStageType getType() { return type; }

        /** Returns the stage display name. */
        public String getName() { return name; }

        /** Returns the audio processor for this stage. */
        public AudioProcessor getProcessor() { return processor; }

        /**
         * Returns whether this stage is <em>terminal</em> — must always be the
         * last node of the chain. The {@link MasteringChain} forbids inserting
         * non-terminal stages after a terminal stage and forbids appending a
         * second terminal stage.
         */
        public boolean isTerminal() { return terminal; }

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
    private int allocatedFrameSize;
    private volatile boolean intermediateBufferWarningLogged;

    // Per-stage metering data: snapshot-publish pattern with per-element atomicity.
    // Audio thread writes via AtomicLongArray (doubleToRawLongBits); UI thread reads
    // via longBitsToDouble. Each mutator (add/insert/remove) publishes new arrays.
    private volatile AtomicLongArray stageInputPeakDb;
    private volatile AtomicLongArray stageOutputPeakDb;
    private volatile AtomicLongArray stageGainReductionDb;

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
        addStage(type, name, processor, type == MasteringStageType.DITHERING);
    }

    /**
     * Adds a stage with an explicit terminal flag.
     *
     * <p>If a terminal stage already exists in the chain, this method throws
     * {@link IllegalStateException} — terminal stages must always be the last
     * node and there can be only one. If {@code terminal} is {@code true} and
     * the chain already contains any stage of type
     * {@link MasteringStageType#DITHERING DITHERING}, the existing stage is
     * already terminal and the new addition is rejected.</p>
     *
     * @param type      the mastering stage type
     * @param name      the display name
     * @param processor the audio processor
     * @param terminal  whether this stage is terminal (must be the last stage)
     * @throws IllegalStateException if appending would place a non-terminal
     *                               stage after an existing terminal stage,
     *                               or if a second terminal stage is added
     */
    public void addStage(MasteringStageType type, String name,
                         AudioProcessor processor, boolean terminal) {
        ensureCanAppend(terminal);
        stages.add(new Stage(type, name, processor, terminal));
        reallocateMeteringArrays();
        resizeIntermediateBuffers();
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
        insertStage(index, type, name, processor, type == MasteringStageType.DITHERING);
    }

    /**
     * Inserts a stage at the specified index with an explicit terminal flag.
     *
     * @param index     the insertion index
     * @param type      the mastering stage type
     * @param name      the display name
     * @param processor the audio processor
     * @param terminal  whether this stage is terminal (must be the last stage)
     * @throws IllegalStateException if the insertion would violate terminal
     *                               ordering — e.g. inserting a non-terminal
     *                               stage at or after the terminal index, or
     *                               inserting a second terminal stage
     */
    public void insertStage(int index, MasteringStageType type, String name,
                            AudioProcessor processor, boolean terminal) {
        ensureCanInsert(index, terminal);
        stages.add(index, new Stage(type, name, processor, terminal));
        reallocateMeteringArrays();
        resizeIntermediateBuffers();
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
        resizeIntermediateBuffers();
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
     * <p>The {@code channels} parameter must match the chain's channel count
     * (as returned by {@link #getInputChannelCount()}) to avoid misconfiguration.</p>
     *
     * @param channels the number of audio channels (must match the chain's channel count)
     * @param frames   the number of sample frames per buffer
     * @throws IllegalArgumentException if channels does not match the chain's channel count
     */
    public void allocateIntermediateBuffers(int channels, int frames) {
        if (channels != this.channels) {
            throw new IllegalArgumentException(
                    "channels (" + channels + ") must match chain channel count (" + this.channels + ")");
        }
        if (frames <= 0) {
            throw new IllegalArgumentException("frames must be positive: " + frames);
        }
        allocatedFrameSize = frames;
        int maxNeeded = Math.max(stages.size() - 1, 0);
        intermediateBuffers = new float[maxNeeded][channels][frames];
    }

    /**
     * Resizes intermediate buffers to match the current stage count, if they
     * have been previously allocated via {@link #allocateIntermediateBuffers(int, int)}.
     *
     * <p>Called from stage mutation methods (add/insert/remove) to keep the
     * intermediate buffer array in sync with the stage count. If buffers have
     * not yet been allocated, this is a no-op.</p>
     */
    private void resizeIntermediateBuffers() {
        if (allocatedFrameSize > 0) {
            int maxNeeded = Math.max(stages.size() - 1, 0);
            intermediateBuffers = new float[maxNeeded][channels][allocatedFrameSize];
        }
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

        // Detect solo with allocation-free indexed loop
        boolean hasSolo = false;
        for (int i = 0; i < stages.size(); i++) {
            if (stages.get(i).isSolo()) {
                hasSolo = true;
                break;
            }
        }

        // Pre-compute the last active stage index (O(n) once, not per-stage)
        int lastActiveIndex = -1;
        for (int i = stages.size() - 1; i >= 0; i--) {
            Stage s = stages.get(i);
            if (hasSolo ? s.isSolo() : !s.isBypassed()) {
                lastActiveIndex = i;
                break;
            }
        }

        if (lastActiveIndex < 0) {
            copyBuffer(inputBuffer, outputBuffer, numFrames);
            return;
        }

        // Snapshot metering arrays (volatile read once)
        AtomicLongArray inputPeaks = stageInputPeakDb;
        AtomicLongArray outputPeaks = stageOutputPeakDb;
        AtomicLongArray gainReductions = stageGainReductionDb;

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
            if (stageIndex == lastActiveIndex) {
                currentOutput = outputBuffer;
            } else if (intermediateBuffers != null && activeIndex < intermediateBuffers.length) {
                currentOutput = intermediateBuffers[activeIndex];
                clearBuffer(currentOutput, numFrames);
            } else {
                // Intermediate buffers not available for non-final stage.
                // Degrade gracefully: log once and bypass remaining stages.
                if (!intermediateBufferWarningLogged) {
                    intermediateBufferWarningLogged = true;
                    LOG.warning("Intermediate buffers not pre-allocated for MasteringChain; "
                            + "call allocateIntermediateBuffers() before processing. "
                            + "Bypassing remaining stages.");
                }
                // Copy what we have so far to the output and stop processing
                copyBuffer(currentInput, outputBuffer, numFrames);
                return;
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
        AtomicLongArray peaks = stageInputPeakDb;
        return (peaks != null && stageIndex >= 0 && stageIndex < peaks.length())
                ? Double.longBitsToDouble(peaks.get(stageIndex)) : -120.0;
    }

    /**
     * Returns the output peak level in dB for the specified stage.
     *
     * @param stageIndex the stage index
     * @return the output peak level in dB, or {@code -120.0} if not available
     */
    public double getStageOutputPeakDb(int stageIndex) {
        AtomicLongArray peaks = stageOutputPeakDb;
        return (peaks != null && stageIndex >= 0 && stageIndex < peaks.length())
                ? Double.longBitsToDouble(peaks.get(stageIndex)) : -120.0;
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
        AtomicLongArray gr = stageGainReductionDb;
        return (gr != null && stageIndex >= 0 && stageIndex < gr.length())
                ? Double.longBitsToDouble(gr.get(stageIndex)) : 0.0;
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

    /**
     * Returns the index of the (single) terminal stage if any is currently in
     * the chain, or {@code -1} if none exists. Terminal stages must always be
     * the final stage of the chain.
     */
    private int terminalIndex() {
        for (int i = 0; i < stages.size(); i++) {
            if (stages.get(i).isTerminal()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Validates that a new stage may be appended.
     *
     * <p>The {@code terminal} flag of the incoming stage is intentionally
     * unused for append: the only constraint at append time is that no
     * existing stage is already terminal (since a terminal stage must always
     * be the last). A second terminal append is therefore caught by the same
     * check — there is no ordering issue if the new stage simply becomes the
     * new last stage.</p>
     *
     * @throws IllegalStateException if the chain already contains a terminal
     *         stage — no stage of any kind may be appended after it.
     */
    @SuppressWarnings("unused") // 'terminal' param documents the call site's intent
    private void ensureCanAppend(boolean terminal) {
        int term = terminalIndex();
        if (term >= 0) {
            throw new IllegalStateException(
                    "Cannot add a stage after the terminal stage at index " + term
                            + " — the mastering chain forbids any stage after a terminal "
                            + "stage (e.g. dithering must always be last).");
        }
    }

    /**
     * Validates that a stage may be inserted at the given index.
     *
     * @throws IllegalStateException if inserting a non-terminal stage at or
     *         after the terminal stage's index, or inserting a second terminal
     *         stage anywhere other than the end.
     */
    private void ensureCanInsert(int index, boolean terminal) {
        int term = terminalIndex();
        if (term >= 0) {
            if (terminal) {
                throw new IllegalStateException(
                        "Cannot insert a second terminal stage — the chain already "
                                + "contains a terminal stage at index " + term + ".");
            }
            if (index > term) {
                throw new IllegalStateException(
                        "Cannot insert a stage at index " + index + " after the "
                                + "terminal stage at index " + term + ".");
            }
        } else if (terminal && index != stages.size()) {
            throw new IllegalStateException(
                    "A terminal stage must be inserted at the end of the chain "
                            + "(index " + stages.size() + "), got index " + index + ".");
        }
    }

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
        long defaultPeak = Double.doubleToRawLongBits(-120.0);
        AtomicLongArray newInput = new AtomicLongArray(n);
        AtomicLongArray newOutput = new AtomicLongArray(n);
        AtomicLongArray newGr = new AtomicLongArray(n);
        for (int i = 0; i < n; i++) {
            newInput.set(i, defaultPeak);
            newOutput.set(i, defaultPeak);
            // GR defaults to 0.0 (AtomicLongArray zero-initializes, and
            // Double.doubleToRawLongBits(0.0) == 0L)
        }
        // Publish all three via volatile writes
        stageInputPeakDb = newInput;
        stageOutputPeakDb = newOutput;
        stageGainReductionDb = newGr;
    }

    private static void updatePeak(AtomicLongArray peaks, int stageIndex,
                                    float[][] buffer, int numFrames) {
        if (peaks != null && stageIndex >= 0 && stageIndex < peaks.length()) {
            peaks.set(stageIndex, Double.doubleToRawLongBits(measurePeakDb(buffer, numFrames)));
        }
    }

    private static void updateGainReduction(AtomicLongArray gr, int stageIndex,
                                             AudioProcessor processor) {
        if (gr != null && stageIndex >= 0 && stageIndex < gr.length()) {
            if (processor instanceof GainReductionProvider provider) {
                gr.set(stageIndex, Double.doubleToRawLongBits(provider.getGainReductionDb()));
            } else {
                gr.set(stageIndex, Double.doubleToRawLongBits(0.0));
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
