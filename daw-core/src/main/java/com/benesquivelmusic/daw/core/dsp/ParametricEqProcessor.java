package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Professional parametric equalizer with configurable filter bands,
 * supporting linear-phase filtering and Mid/Side processing.
 *
 * <p>Each band is an independent filter that can be set to any supported
 * filter type (peak EQ, low-shelf, high-shelf, high-pass, low-pass, etc.).
 * Bands are processed in series.</p>
 *
 * <p>Implements the EQ processing described in the mastering-techniques
 * research (§3 — Equalization and Tonal Balancing), supporting:
 * <ul>
 *   <li>Parametric peak EQ for surgical corrections</li>
 *   <li>Shelving EQ for broad tonal shaping</li>
 *   <li>High-pass/low-pass filters for cleanup</li>
 * </ul>
 *
 * <h2>Filter Modes</h2>
 * <ul>
 *   <li>{@link FilterMode#MINIMUM_PHASE} (default) — Uses biquad (IIR) filters.
 *       Zero latency but introduces frequency-dependent phase shifts.</li>
 *   <li>{@link FilterMode#LINEAR_PHASE} — Uses FIR filters generated from the
 *       biquad magnitude response. Avoids phase distortion but introduces
 *       latency of {@code (firOrder - 1) / 2} samples. Critical for mastering.</li>
 * </ul>
 *
 * <h2>Processing Modes</h2>
 * <ul>
 *   <li>{@link ProcessingMode#STEREO} (default) — The same EQ bands are applied
 *       to all channels.</li>
 *   <li>{@link ProcessingMode#MID_SIDE} — Requires 2-channel (stereo) input.
 *       The signal is encoded to Mid/Side, independent EQ bands are applied
 *       to the mid and side channels, and the result is decoded back to
 *       Left/Right stereo. Use {@link #addMidBand}, {@link #addSideBand}, etc.
 *       to configure independent mid and side EQ bands.</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 * <pre>{@code
 * // Linear-phase mastering EQ
 * var eq = new ParametricEqProcessor(2, 44100.0);
 * eq.setFilterMode(ParametricEqProcessor.FilterMode.LINEAR_PHASE);
 * eq.addBand(BandConfig.of(BiquadFilter.FilterType.HIGH_SHELF, 8000, 0.707, 2.0));
 * int latency = eq.getLatencySamples(); // report to DAW for compensation
 *
 * // Mid/Side EQ for mastering
 * var msEq = new ParametricEqProcessor(2, 44100.0);
 * msEq.setProcessingMode(ParametricEqProcessor.ProcessingMode.MID_SIDE);
 * msEq.addMidBand(BandConfig.of(BiquadFilter.FilterType.PEAK_EQ, 3000, 1.5, -2.0));
 * msEq.addSideBand(BandConfig.of(BiquadFilter.FilterType.HIGH_SHELF, 8000, 0.707, 3.0));
 * }</pre>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
public final class ParametricEqProcessor implements AudioProcessor {

    /** Filter implementation mode. */
    public enum FilterMode {
        /** Minimum-phase biquad (IIR) filtering — zero latency. */
        MINIMUM_PHASE,
        /** Linear-phase FIR filtering — zero phase distortion, adds latency. */
        LINEAR_PHASE
    }

    /** Channel processing mode. */
    public enum ProcessingMode {
        /** Standard stereo processing — same EQ applied to all channels. */
        STEREO,
        /** Mid/Side processing — independent EQ for center and stereo content. */
        MID_SIDE
    }

    /**
     * Immutable description of a single EQ band.
     *
     * @param type      the filter type
     * @param frequency the center/cutoff frequency in Hz
     * @param q         the Q factor
     * @param gainDb    the gain in dB (for peak/shelf types)
     * @param enabled   whether this band is active
     */
    public record BandConfig(BiquadFilter.FilterType type, double frequency,
                             double q, double gainDb, boolean enabled) {

        public BandConfig {
            Objects.requireNonNull(type, "type must not be null");
            if (frequency <= 0) {
                throw new IllegalArgumentException("frequency must be positive: " + frequency);
            }
            if (q <= 0) {
                throw new IllegalArgumentException("q must be positive: " + q);
            }
        }

        /** Creates an enabled band configuration. */
        public static BandConfig of(BiquadFilter.FilterType type, double frequency,
                                    double q, double gainDb) {
            return new BandConfig(type, frequency, q, gainDb, true);
        }
    }

    private final int channels;
    private final double sampleRate;

    // Stereo-mode bands
    private final List<BandConfig> bandConfigs = new ArrayList<>();
    private BiquadFilter[][] filters; // [band][channel]

    // M/S-mode bands
    private final List<BandConfig> midBandConfigs = new ArrayList<>();
    private final List<BandConfig> sideBandConfigs = new ArrayList<>();
    private BiquadFilter[] midFilters;  // [band] — single-channel for mid
    private BiquadFilter[] sideFilters; // [band] — single-channel for side

    // Linear-phase composite filters
    private LinearPhaseFilter[] compositeLinearFilters; // [channel] for STEREO
    private LinearPhaseFilter compositeMidLinearFilter;   // for MID_SIDE
    private LinearPhaseFilter compositeSideLinearFilter;  // for MID_SIDE

    // Mode settings
    private FilterMode filterMode = FilterMode.MINIMUM_PHASE;
    private ProcessingMode processingMode = ProcessingMode.STEREO;
    private int firOrder = LinearPhaseFilter.DEFAULT_FIR_ORDER;

    // Pre-allocated M/S working buffers (avoid allocation in process callback)
    private float[] midBuffer;
    private float[] sideBuffer;

    /**
     * Creates a parametric EQ with the specified channel count and sample rate.
     *
     * @param channels   number of audio channels
     * @param sampleRate the sample rate in Hz
     */
    public ParametricEqProcessor(int channels, double sampleRate) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.filters = new BiquadFilter[0][0];
        this.midFilters = new BiquadFilter[0];
        this.sideFilters = new BiquadFilter[0];
    }

    // ---- Mode getters/setters ----

    /** Returns the current filter mode. */
    public FilterMode getFilterMode() {
        return filterMode;
    }

    /**
     * Sets the filter mode.
     *
     * @param mode the filter mode
     */
    public void setFilterMode(FilterMode mode) {
        this.filterMode = Objects.requireNonNull(mode, "mode must not be null");
        rebuildFilters();
        rebuildMidSideFilters();
    }

    /** Returns the current processing mode. */
    public ProcessingMode getProcessingMode() {
        return processingMode;
    }

    /**
     * Sets the processing mode.
     *
     * @param mode the processing mode
     */
    public void setProcessingMode(ProcessingMode mode) {
        this.processingMode = Objects.requireNonNull(mode, "mode must not be null");
    }

    /** Returns the FIR order used for linear-phase mode. */
    public int getFirOrder() {
        return firOrder;
    }

    /**
     * Sets the FIR order for linear-phase mode.
     *
     * @param firOrder the FIR order (must be &gt;= 3)
     */
    public void setFirOrder(int firOrder) {
        if (firOrder < 3) {
            throw new IllegalArgumentException("firOrder must be >= 3: " + firOrder);
        }
        this.firOrder = firOrder;
        if (filterMode == FilterMode.LINEAR_PHASE) {
            rebuildFilters();
            rebuildMidSideFilters();
        }
    }

    /**
     * Returns the processing latency in samples.
     *
     * <p>In {@link FilterMode#MINIMUM_PHASE} mode the latency is always zero.
     * In {@link FilterMode#LINEAR_PHASE} mode the latency is
     * {@code (firOrder - 1) / 2} samples (after rounding firOrder to odd).</p>
     *
     * @return the latency in samples
     */
    public int getLatencySamples() {
        if (filterMode == FilterMode.LINEAR_PHASE) {
            int order = firOrder % 2 == 0 ? firOrder + 1 : firOrder;
            return (order - 1) / 2;
        }
        return 0;
    }

    // ---- Stereo-mode band management (existing API) ----

    /**
     * Adds a new EQ band (used in {@link ProcessingMode#STEREO} mode).
     *
     * @param config the band configuration
     */
    public void addBand(BandConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        bandConfigs.add(config);
        rebuildFilters();
    }

    /**
     * Updates an existing band at the given index.
     *
     * @param index  the band index
     * @param config the new configuration
     */
    public void updateBand(int index, BandConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        bandConfigs.set(index, config);
        rebuildFilters();
    }

    /**
     * Removes the band at the given index.
     *
     * @param index the band index
     */
    public void removeBand(int index) {
        bandConfigs.remove(index);
        rebuildFilters();
    }

    /**
     * Returns an unmodifiable view of the band configurations.
     */
    public List<BandConfig> getBands() {
        return Collections.unmodifiableList(bandConfigs);
    }

    // ---- M/S-mode band management ----

    /**
     * Adds an EQ band for the mid (center) channel in {@link ProcessingMode#MID_SIDE} mode.
     *
     * @param config the band configuration
     */
    public void addMidBand(BandConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        midBandConfigs.add(config);
        rebuildMidSideFilters();
    }

    /**
     * Updates a mid-channel band at the given index.
     *
     * @param index  the band index
     * @param config the new configuration
     */
    public void updateMidBand(int index, BandConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        midBandConfigs.set(index, config);
        rebuildMidSideFilters();
    }

    /**
     * Removes a mid-channel band at the given index.
     *
     * @param index the band index
     */
    public void removeMidBand(int index) {
        midBandConfigs.remove(index);
        rebuildMidSideFilters();
    }

    /**
     * Returns an unmodifiable view of the mid-channel band configurations.
     */
    public List<BandConfig> getMidBands() {
        return Collections.unmodifiableList(midBandConfigs);
    }

    /**
     * Adds an EQ band for the side (stereo) channel in {@link ProcessingMode#MID_SIDE} mode.
     *
     * @param config the band configuration
     */
    public void addSideBand(BandConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        sideBandConfigs.add(config);
        rebuildMidSideFilters();
    }

    /**
     * Updates a side-channel band at the given index.
     *
     * @param index  the band index
     * @param config the new configuration
     */
    public void updateSideBand(int index, BandConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        sideBandConfigs.set(index, config);
        rebuildMidSideFilters();
    }

    /**
     * Removes a side-channel band at the given index.
     *
     * @param index the band index
     */
    public void removeSideBand(int index) {
        sideBandConfigs.remove(index);
        rebuildMidSideFilters();
    }

    /**
     * Returns an unmodifiable view of the side-channel band configurations.
     */
    public List<BandConfig> getSideBands() {
        return Collections.unmodifiableList(sideBandConfigs);
    }

    // ---- Processing ----

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        if (processingMode == ProcessingMode.MID_SIDE && channels >= 2) {
            processMidSide(inputBuffer, outputBuffer, numFrames);
        } else {
            processStereo(inputBuffer, outputBuffer, numFrames);
        }
    }

    private void processStereo(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        // Copy input to output first
        for (int ch = 0; ch < Math.min(inputBuffer.length, outputBuffer.length); ch++) {
            System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
        }

        if (filterMode == FilterMode.LINEAR_PHASE) {
            if (compositeLinearFilters != null) {
                for (int ch = 0; ch < Math.min(channels, outputBuffer.length); ch++) {
                    compositeLinearFilters[ch].process(outputBuffer[ch], 0, numFrames);
                }
            }
        } else {
            // Apply each enabled minimum-phase band
            for (int band = 0; band < filters.length; band++) {
                if (!bandConfigs.get(band).enabled()) continue;
                for (int ch = 0; ch < Math.min(channels, outputBuffer.length); ch++) {
                    filters[band][ch].process(outputBuffer[ch], 0, numFrames);
                }
            }
        }
    }

    private void processMidSide(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        // Ensure working buffers are large enough
        if (midBuffer == null || midBuffer.length < numFrames) {
            midBuffer = new float[numFrames];
            sideBuffer = new float[numFrames];
        }

        // Encode L/R → M/S
        MidSideEncoder.encode(inputBuffer[0], inputBuffer[1], midBuffer, sideBuffer, numFrames);

        if (filterMode == FilterMode.LINEAR_PHASE) {
            if (compositeMidLinearFilter != null) {
                compositeMidLinearFilter.process(midBuffer, 0, numFrames);
            }
            if (compositeSideLinearFilter != null) {
                compositeSideLinearFilter.process(sideBuffer, 0, numFrames);
            }
        } else {
            // Apply minimum-phase bands to mid
            for (int band = 0; band < midFilters.length; band++) {
                if (midBandConfigs.get(band).enabled()) {
                    midFilters[band].process(midBuffer, 0, numFrames);
                }
            }
            // Apply minimum-phase bands to side
            for (int band = 0; band < sideFilters.length; band++) {
                if (sideBandConfigs.get(band).enabled()) {
                    sideFilters[band].process(sideBuffer, 0, numFrames);
                }
            }
        }

        // Decode M/S → L/R
        MidSideDecoder.decode(midBuffer, sideBuffer, outputBuffer[0], outputBuffer[1], numFrames);

        // Copy remaining channels unchanged
        for (int ch = 2; ch < Math.min(inputBuffer.length, outputBuffer.length); ch++) {
            System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
        }
    }

    @Override
    public void reset() {
        for (BiquadFilter[] bandFilters : filters) {
            for (BiquadFilter filter : bandFilters) {
                filter.reset();
            }
        }
        for (BiquadFilter f : midFilters) {
            f.reset();
        }
        for (BiquadFilter f : sideFilters) {
            f.reset();
        }
        if (compositeLinearFilters != null) {
            for (LinearPhaseFilter f : compositeLinearFilters) {
                f.reset();
            }
        }
        if (compositeMidLinearFilter != null) {
            compositeMidLinearFilter.reset();
        }
        if (compositeSideLinearFilter != null) {
            compositeSideLinearFilter.reset();
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

    // ---- Filter rebuild methods ----

    private void rebuildFilters() {
        // Minimum-phase filters (always built for reference)
        filters = new BiquadFilter[bandConfigs.size()][channels];
        for (int band = 0; band < bandConfigs.size(); band++) {
            BandConfig config = bandConfigs.get(band);
            for (int ch = 0; ch < channels; ch++) {
                filters[band][ch] = BiquadFilter.create(
                        config.type(), sampleRate, config.frequency(),
                        config.q(), config.gainDb());
            }
        }

        // Linear-phase composite filter
        if (filterMode == FilterMode.LINEAR_PHASE) {
            compositeLinearFilters = buildCompositeLinearArray(bandConfigs);
        } else {
            compositeLinearFilters = null;
        }
    }

    private void rebuildMidSideFilters() {
        // Minimum-phase mid filters
        midFilters = new BiquadFilter[midBandConfigs.size()];
        for (int band = 0; band < midBandConfigs.size(); band++) {
            BandConfig config = midBandConfigs.get(band);
            midFilters[band] = BiquadFilter.create(
                    config.type(), sampleRate, config.frequency(),
                    config.q(), config.gainDb());
        }

        // Minimum-phase side filters
        sideFilters = new BiquadFilter[sideBandConfigs.size()];
        for (int band = 0; band < sideBandConfigs.size(); band++) {
            BandConfig config = sideBandConfigs.get(band);
            sideFilters[band] = BiquadFilter.create(
                    config.type(), sampleRate, config.frequency(),
                    config.q(), config.gainDb());
        }

        // Linear-phase M/S composite filters
        if (filterMode == FilterMode.LINEAR_PHASE) {
            compositeMidLinearFilter = buildCompositeLinear(midBandConfigs);
            compositeSideLinearFilter = buildCompositeLinear(sideBandConfigs);
        } else {
            compositeMidLinearFilter = null;
            compositeSideLinearFilter = null;
        }
    }

    private LinearPhaseFilter[] buildCompositeLinearArray(List<BandConfig> configs) {
        List<BiquadFilter> active = collectActiveBiquads(configs);
        if (active.isEmpty()) return null;
        BiquadFilter[] bqArray = active.toArray(new BiquadFilter[0]);
        LinearPhaseFilter[] result = new LinearPhaseFilter[channels];
        for (int ch = 0; ch < channels; ch++) {
            result[ch] = LinearPhaseFilter.fromBiquads(bqArray, firOrder);
        }
        return result;
    }

    private LinearPhaseFilter buildCompositeLinear(List<BandConfig> configs) {
        List<BiquadFilter> active = collectActiveBiquads(configs);
        if (active.isEmpty()) return null;
        return LinearPhaseFilter.fromBiquads(active.toArray(new BiquadFilter[0]), firOrder);
    }

    private List<BiquadFilter> collectActiveBiquads(List<BandConfig> configs) {
        List<BiquadFilter> active = new ArrayList<>();
        for (BandConfig config : configs) {
            if (config.enabled()) {
                active.add(BiquadFilter.create(
                        config.type(), sampleRate, config.frequency(),
                        config.q(), config.gainDb()));
            }
        }
        return active;
    }
}
