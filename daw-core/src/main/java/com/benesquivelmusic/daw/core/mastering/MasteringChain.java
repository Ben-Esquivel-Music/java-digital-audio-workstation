package com.benesquivelmusic.daw.core.mastering;

import com.benesquivelmusic.daw.core.dsp.GainReductionProvider;
import com.benesquivelmusic.daw.core.metering.LevelTapSlot;
import com.benesquivelmusic.daw.core.metering.TapSnapshot;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.audio.DynamicLatencyProcessor;
import com.benesquivelmusic.daw.sdk.mastering.MasteringChainPreset;
import com.benesquivelmusic.daw.sdk.mastering.MasteringStageConfig;
import com.benesquivelmusic.daw.sdk.mastering.MasteringStageType;

import java.util.*;
import java.util.concurrent.atomic.AtomicLongArray;

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
public final class MasteringChain implements DynamicLatencyProcessor {

    /** Default number of channels for stereo mastering. */
    private static final int DEFAULT_CHANNELS = 2;

    /**
     * A single stage in the mastering chain.
     */
    public static final class Stage {

        private final MasteringStageType type;
        private final String name;
        private record ProcessorState(AudioProcessor processor, int fixedLatency) {
            int latencySamples() {
                return processor instanceof DynamicLatencyProcessor dynamic
                        ? dynamic.getLatencySamples() : fixedLatency;
            }
        }
        private volatile ProcessorState processorState;
        private final boolean terminal;
        private volatile boolean bypassed;
        private volatile boolean solo;

        public Stage(MasteringStageType type, String name, AudioProcessor processor) {
            this(type, name, processor, type == MasteringStageType.DITHERING);
        }

        public Stage(MasteringStageType type, String name, AudioProcessor processor, boolean terminal) {
            this.type = Objects.requireNonNull(type, "type must not be null");
            this.name = Objects.requireNonNull(name, "name must not be null");
            setProcessor(processor);
            this.terminal = terminal;
        }

        /** Returns the stage type. */
        public MasteringStageType getType() { return type; }

        /** Returns the stage display name. */
        public String getName() { return name; }

        /** Returns the audio processor for this stage. */
        public AudioProcessor getProcessor() { return processorState.processor(); }

        /** Installs a fully configured processor prepared on the control thread. */
        public void setProcessor(AudioProcessor processor) {
            Objects.requireNonNull(processor, "processor must not be null");
            // Capture thread-confined/static getters only on the configuring thread.
            processorState = new ProcessorState(processor, processor instanceof DynamicLatencyProcessor
                    ? 0 : processor.getLatencySamples());
        }

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
    private volatile boolean chainBypassed;
    private volatile double referenceGainDb;
    private int allocatedFrameSize;
    private record RenderState(Stage[] stages, float[][][] buffers,
                               AtomicLongArray inputPeaks, AtomicLongArray outputPeaks,
                               AtomicLongArray gainReductions, float[][] inputScratch, float[][] outputScratch, int[] activeFlags) {}
    private volatile RenderState renderState = new RenderState(new Stage[0], null,
            new AtomicLongArray(0), new AtomicLongArray(0), new AtomicLongArray(0), null, null, new int[0]);
    private static final int PARAMETER_CAPACITY = 256;
    private final Runnable[] parameterUpdates = new Runnable[PARAMETER_CAPACITY];
    private volatile long parameterWrite;
    private volatile long parameterRead;
    private volatile ParameterControl[] parameterControls = new ParameterControl[0];

    /** One latest-wins control slot; UI publishes, the render thread applies each value once. */
    public final class ParameterControl implements AutoCloseable {
        private volatile Runnable requested;
        private volatile Runnable applied;
        private volatile boolean closed;

        private ParameterControl() { }

        /** The supplied setter must be allocation-free and nonblocking. */
        public void submit(Runnable update) {
            Objects.requireNonNull(update, "update must not be null");
            if (closed) throw new IllegalStateException("Parameter control is closed");
            requested = update;
        }

        private void applyPending() {
            Runnable next = requested;
            if (next != null && next != applied) {
                next.run();
                applied = next;
            }
        }

        @Override public void close() {
            removeParameterControl(this);
        }
    }

    /** Registers a bounded control slot off the audio thread. */
    public synchronized ParameterControl createParameterControl() {
        compactParameterControls();
        ParameterControl[] current = parameterControls;
        if (current.length >= 1024) throw new IllegalStateException("Too many mastering parameter controls");
        var control = new ParameterControl();
        ParameterControl[] replacement = Arrays.copyOf(current, current.length + 1);
        replacement[current.length] = control;
        parameterControls = replacement;
        return control;
    }

    private synchronized void removeParameterControl(ParameterControl control) {
        if (control.closed) return;
        control.closed = true;
        compactParameterControls();
    }

    private void compactParameterControls() {
        // A closed control's last accepted edit still reaches the next block.
        parameterControls = Arrays.stream(parameterControls)
                .filter(control -> !control.closed || control.requested != control.applied)
                .toArray(ParameterControl[]::new);
    }

    /** Queues an allocation-free, nonblocking setter for the next block; false on saturation. */
    public synchronized boolean enqueueParameterUpdate(Runnable update) {
        Objects.requireNonNull(update, "update must not be null");
        if (parameterWrite - parameterRead >= PARAMETER_CAPACITY) return false;
        parameterUpdates[(int) (parameterWrite % PARAMETER_CAPACITY)] = update;
        parameterWrite++;
        return true;
    }

    /** Applies pending controls before the host samples this block's latency. */
    public void drainParameterUpdates() {
        for (ParameterControl control : parameterControls) control.applyPending();
        long end = parameterWrite;
        while (parameterRead < end) {
            int index = (int) (parameterRead % PARAMETER_CAPACITY);
            Runnable update = parameterUpdates[index];
            parameterUpdates[index] = null;
            parameterRead++;
            update.run();
        }
    }

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
    public synchronized void addStage(MasteringStageType type, String name, AudioProcessor processor) {
        addStage(type, name, processor, type == MasteringStageType.DITHERING);
    }

    /**
     * Adds a stage with an explicit terminal flag.
     *
     * <p>If a terminal stage already exists in the chain, this method throws
     * {@link IllegalStateException} — terminal stages must always be the last
     * node in the chain, and there can be only one terminal stage at a time.
     * Whether a stage is terminal is determined by the {@code terminal} flag
     * passed when it is added (and the stored stage state), not by its
     * {@link MasteringStageType}.</p>
     *
     * @param type      the mastering stage type
     * @param name      the display name
     * @param processor the audio processor
     * @param terminal  whether this stage is terminal (must be the last stage)
     * @throws IllegalStateException if appending would place a non-terminal
     *                               stage after an existing terminal stage,
     *                               or if a second terminal stage is added
     */
    public synchronized void addStage(MasteringStageType type, String name,
                         AudioProcessor processor, boolean terminal) {
        checkStageCount(stages.size() + 1);
        ensureCanAppend(terminal);
        stages.add(new Stage(type, name, processor, terminal));
        publishRenderState();
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
    public synchronized void insertStage(int index, MasteringStageType type, String name,
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
    public synchronized void insertStage(int index, MasteringStageType type, String name,
                            AudioProcessor processor, boolean terminal) {
        checkStageCount(stages.size() + 1);
        ensureCanInsert(index, terminal);
        stages.add(index, new Stage(type, name, processor, terminal));
        publishRenderState();
    }

    /**
     * Removes the stage at the specified index.
     *
     * <p>Metering arrays are re-allocated to match the new stage count.</p>
     *
     * @param index the index of the stage to remove
     * @return the removed stage
     */
    public synchronized Stage removeStage(int index) {
        Stage removed = stages.remove(index);
        publishRenderState();
        return removed;
    }

    /**
     * Returns an unmodifiable view of the stages.
     *
     * @return the list of stages
     */
    public synchronized List<Stage> getStages() {
        return List.copyOf(stages);
    }

    /** Returns the number of stages. */
    public int size() {
        return renderState.stages().length;
    }

    /** Returns whether the chain has no stages. */
    public boolean isEmpty() {
        return renderState.stages().length == 0;
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
    public synchronized void allocateIntermediateBuffers(int channels, int frames) {
        if (channels != this.channels) throw new IllegalArgumentException("channels must match chain channel count");
        if (frames <= 0) throw new IllegalArgumentException("frames must be positive: " + frames);
        allocatedFrameSize = frames;
        publishRenderState();
    }

    /** Publishes a complete preset in one control-thread operation. */
    public synchronized void replaceStages(List<Stage> replacement) {
        var copy = List.copyOf(replacement);
        checkStageCount(copy.size());
        for (int i = 0; i < copy.size() - 1; i++) {
            if (copy.get(i).isTerminal()) throw new IllegalStateException("A terminal stage must be last");
        }
        stages.clear();
        stages.addAll(copy);
        publishRenderState();
    }

    private static void checkStageCount(int count) {
        if (count > com.benesquivelmusic.daw.core.metering.MeterTapPoint.MAX_MASTERING_STAGES) {
            throw new IllegalArgumentException("Too many mastering stages: " + count);
        }
    }

    /** Moves a stage while preserving terminal ordering and atomic render publication. */
    public synchronized void moveStage(int from, int to) {
        var replacement = new ArrayList<>(stages);
        replacement.add(to, replacement.remove(from));
        replaceStages(replacement);
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
        process(inputBuffer, outputBuffer, numFrames, null);
    }

    /** Processes one immutable stage/buffer snapshot and publishes demanded meter lanes. */
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames, TapSnapshot taps) {
        process(inputBuffer, outputBuffer, numFrames, taps, true);
    }

    /** Stopped monitoring still processes audio, while stage readouts publish honest idle. */
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames, TapSnapshot taps,
                        boolean stageMetersActive) {
        if (!stageMetersActive && taps != null) {
            for (int i = 0; i < com.benesquivelmusic.daw.core.metering.MeterTapPoint.MAX_MASTERING_STAGES; i++) {
                LevelTapSlot slot = taps.masteringStage(i);
                if (slot != null) publishSilence(slot, taps, channels, numFrames);
            }
        }
        TapSnapshot stageTaps = stageMetersActive ? taps : null;
        drainParameterUpdates();
        RenderState captured = renderState;
        if ((inputBuffer.length != channels || outputBuffer.length != channels)
                && captured.inputScratch() != null) {
            // Mastering remains stereo on devices exposing additional direct-output lanes.
            // A mono device is duplicated into the stereo processor input and receives left output.
            copyBuffer(inputBuffer, outputBuffer, numFrames);
            for (int ch = 0; ch < channels; ch++) {
                float[] source = inputBuffer[Math.min(ch, inputBuffer.length - 1)];
                System.arraycopy(source, 0, captured.inputScratch()[ch], 0, numFrames);
            }
            processPrepared(captured.inputScratch(), captured.outputScratch(), numFrames, stageTaps, captured);
            copyBuffer(captured.outputScratch(), outputBuffer, numFrames);
            return;
        }
        processPrepared(inputBuffer, outputBuffer, numFrames, stageTaps, captured);
    }

    private void processPrepared(float[][] inputBuffer, float[][] outputBuffer, int numFrames,
                                 TapSnapshot taps, RenderState captured) {
        Stage[] blockStages = captured.stages();
        boolean bypassed = chainBypassed;
        boolean hasSolo = false;
        int[] flags = captured.activeFlags();
        for (int i = 0; i < blockStages.length; i++) {
            Stage stage = blockStages[i];
            boolean solo = stage.isSolo();
            flags[i] = solo ? 2 : stage.isBypassed() ? 0 : 1;
            hasSolo |= solo;
        }
        int lastActive = -1;
        for (int i = 0; i < blockStages.length; i++) {
            flags[i] = !bypassed && (hasSolo ? flags[i] == 2 : flags[i] != 0) ? 1 : 0;
            if (flags[i] == 1) lastActive = i;
        }
        if (lastActive < 0) copyWithGain(inputBuffer, outputBuffer, numFrames, bypassed ? referenceGainDb : 0.0);
        float[][] currentInput = inputBuffer;
        int activeIndex = 0;
        for (int i = 0; i < blockStages.length; i++) {
            Stage stage = blockStages[i];
            LevelTapSlot slot = taps != null ? taps.masteringStage(i) : null;
            boolean active = flags[i] == 1;
            if (!active) {
                captured.inputPeaks().set(i, Double.doubleToRawLongBits(-120.0));
                captured.outputPeaks().set(i, Double.doubleToRawLongBits(-120.0));
                captured.gainReductions().set(i, 0L);
                if (slot != null) publishSilence(slot, taps, outputBuffer.length, numFrames);
                continue;
            }
            float[][] currentOutput;
            if (i == lastActive) currentOutput = outputBuffer;
            else if (captured.buffers() != null && activeIndex < captured.buffers().length) {
                currentOutput = captured.buffers()[activeIndex];
                clearBuffer(currentOutput, numFrames);
            } else {
                copyBuffer(currentInput, outputBuffer, numFrames);
                return;
            }
            double inputPeak = measurePeakDb(currentInput, numFrames);
            AudioProcessor processor = stage.getProcessor();
            processor.process(currentInput, currentOutput, numFrames);
            captured.inputPeaks().set(i, Double.doubleToRawLongBits(inputPeak));
            updatePeak(captured.outputPeaks(), i, currentOutput, numFrames);
            updateGainReduction(captured.gainReductions(), i, processor);
            if (slot != null) {
                slot.beginBlock(taps.epoch(), taps.blockIndex(), currentOutput.length);
                for (int ch = 0; ch < currentOutput.length; ch++) slot.accumulate(ch, currentOutput[ch], numFrames);
                slot.setMasteringLevels(inputPeak, Double.longBitsToDouble(captured.gainReductions().get(i)));
                for (var ring : slot.rings()) ring.write(currentOutput, currentOutput.length, numFrames);
                slot.publish(numFrames);
            }
            currentInput = currentOutput;
            activeIndex++;
        }
    }

    private static void publishSilence(LevelTapSlot slot, TapSnapshot taps, int channels, int numFrames) {
        slot.publishSilence(taps.epoch(), taps.blockIndex(), channels);
        for (var ring : slot.rings()) ring.writeSilence(channels, numFrames);
    }

    /**
     * Resets all processors in the chain.
     */
    @Override
    public void reset() {
        for (Stage stage : renderState.stages()) {
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

    /** Serial latency of the stages that processing will actually run, including solo precedence. */
    @Override
    public int getLatencySamples() {
        if (chainBypassed) return 0;
        Stage[] current = renderState.stages();
        boolean hasSolo = false;
        for (Stage stage : current) hasSolo |= stage.isSolo();
        int latency = 0;
        for (Stage stage : current) {
            if (hasSolo ? stage.isSolo() : !stage.isBypassed()) {
                latency += stage.processorState.latencySamples();
            }
        }
        return latency;
    }

    // --- Metering accessors (read from UI thread) ---

    /**
     * Returns the input peak level in dB for the specified stage.
     *
     * @param stageIndex the stage index
     * @return the input peak level in dB, or {@code -120.0} if not available
     */
    public double getStageInputPeakDb(int stageIndex) {
        AtomicLongArray peaks = renderState.inputPeaks();
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
        AtomicLongArray peaks = renderState.outputPeaks();
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
        AtomicLongArray gr = renderState.gainReductions();
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
        for (Stage stage : getStages()) {
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
    private void publishRenderState() {
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
        float[][][] buffers = allocatedFrameSize > 0
                ? new float[Math.max(n - 1, 0)][channels][allocatedFrameSize] : null;
        renderState = new RenderState(stages.toArray(Stage[]::new), buffers, newInput, newOutput, newGr,
                allocatedFrameSize > 0 ? new float[channels][allocatedFrameSize] : null,
                allocatedFrameSize > 0 ? new float[channels][allocatedFrameSize] : null, new int[n]);
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
