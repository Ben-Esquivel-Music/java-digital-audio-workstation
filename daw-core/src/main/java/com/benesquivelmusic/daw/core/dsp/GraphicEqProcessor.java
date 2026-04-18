package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.core.mixer.InsertEffect;

import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.Arrays;
import java.util.Objects;

/**
 * Graphic equalizer processor supporting octave and third-octave band
 * configurations with optional linear-phase mode.
 *
 * <p>Unlike the {@link ParametricEqProcessor} which provides fully configurable
 * parametric bands, a graphic EQ offers fixed-frequency band sliders familiar
 * to audio engineers for broad tonal shaping — particularly useful for live
 * monitoring correction and quick tonal adjustments.</p>
 *
 * <h2>Band Configurations</h2>
 * <ul>
 *   <li>{@link BandType#OCTAVE} — 10 bands at ISO octave center frequencies
 *       from 31.5&nbsp;Hz to 16&nbsp;kHz</li>
 *   <li>{@link BandType#THIRD_OCTAVE} — 31 bands at ISO 1/3-octave center
 *       frequencies from 20&nbsp;Hz to 20&nbsp;kHz</li>
 * </ul>
 *
 * <h2>Filter Modes</h2>
 * <ul>
 *   <li>{@link FilterMode#MINIMUM_PHASE} (default) — Each band is processed
 *       using a biquad peak filter. Zero latency but introduces
 *       frequency-dependent phase shifts.</li>
 *   <li>{@link FilterMode#LINEAR_PHASE} — The combined biquad magnitude
 *       response is converted to a symmetric FIR filter via
 *       {@link LinearPhaseFilter}. Avoids phase distortion but introduces
 *       latency of {@code (firOrder - 1) / 2} samples.</li>
 * </ul>
 *
 * <h2>AES Research References</h2>
 * <ul>
 *   <li>Linear-Phase Octave Graphic Equalizer (2022) — symmetric FIR design
 *       with flat-sum magnitude response</li>
 *   <li>Design of a Digitally Controlled Graphic Equalizer (2017) — minimum-phase
 *       design with optimal band interaction</li>
 *   <li>Parametric Equalization (2012) — foundational filter design reference</li>
 * </ul>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
@RealTimeSafe
@InsertEffect(type = "GRAPHIC_EQ", displayName = "Graphic EQ")
public final class GraphicEqProcessor implements AudioProcessor {

    /** Maximum gain magnitude allowed per band (±12 dB). */
    public static final double MAX_GAIN_DB = 12.0;

    /** Band spacing configuration. */
    public enum BandType {
        /** 10 bands at ISO octave center frequencies (31.5 Hz – 16 kHz). */
        OCTAVE,
        /** 31 bands at ISO 1/3-octave center frequencies (20 Hz – 20 kHz). */
        THIRD_OCTAVE
    }

    /** Filter implementation mode. */
    public enum FilterMode {
        /** Minimum-phase biquad (IIR) filtering — zero latency. */
        MINIMUM_PHASE,
        /** Linear-phase FIR filtering — zero phase distortion, adds latency. */
        LINEAR_PHASE
    }

    /** ISO octave center frequencies in Hz (31.5 Hz – 16 kHz). */
    static final double[] OCTAVE_FREQUENCIES = {
            31.5, 63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0
    };

    /** ISO 1/3-octave center frequencies in Hz (20 Hz – 20 kHz, 31 bands). */
    static final double[] THIRD_OCTAVE_FREQUENCIES = {
            20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0,
            250.0, 315.0, 400.0, 500.0, 630.0, 800.0, 1000.0, 1250.0, 1600.0, 2000.0,
            2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0, 16000.0, 20000.0
    };

    private final int channels;
    private final double sampleRate;
    private BandType bandType;
    private FilterMode filterMode;
    private int firOrder;

    private double[] frequencies;
    private double[] gainDb;
    private double q;

    // Minimum-phase filters: [band][channel]
    private BiquadFilter[][] filters;

    // Linear-phase composite filters: [channel]
    private LinearPhaseFilter[] linearFilters;

    /**
     * Creates a graphic EQ with the specified channel count and sample rate.
     *
     * <p>Defaults to {@link BandType#OCTAVE} with {@link FilterMode#MINIMUM_PHASE}.</p>
     *
     * @param channels   number of audio channels (must be positive)
     * @param sampleRate the sample rate in Hz (must be positive)
     */
    public GraphicEqProcessor(int channels, double sampleRate) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.bandType = BandType.OCTAVE;
        this.filterMode = FilterMode.MINIMUM_PHASE;
        this.firOrder = LinearPhaseFilter.DEFAULT_FIR_ORDER;
        this.q = defaultQForBandType(BandType.OCTAVE);
        initBands();
    }

    // ---- Band type / mode getters and setters ----

    /** Returns the current band type. */
    public BandType getBandType() {
        return bandType;
    }

    /**
     * Sets the band type, resetting all band gains to 0 dB.
     *
     * @param bandType the band type
     */
    public void setBandType(BandType bandType) {
        this.bandType = Objects.requireNonNull(bandType, "bandType must not be null");
        this.q = defaultQForBandType(bandType);
        initBands();
    }

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

    // ---- Band gain management ----

    /**
     * Returns the number of bands in the current configuration.
     *
     * @return the band count
     */
    public int getBandCount() {
        return frequencies.length;
    }

    /**
     * Returns the center frequency of the band at the given index.
     *
     * @param bandIndex the band index
     * @return the center frequency in Hz
     */
    public double getBandFrequency(int bandIndex) {
        return frequencies[bandIndex];
    }

    /**
     * Returns the gain of the band at the given index.
     *
     * @param bandIndex the band index
     * @return the gain in dB
     */
    public double getBandGain(int bandIndex) {
        return gainDb[bandIndex];
    }

    /**
     * Sets the gain of a single band, clamped to ±{@value #MAX_GAIN_DB} dB.
     *
     * @param bandIndex the band index
     * @param gain      the gain in dB (clamped to ±12 dB)
     */
    public void setBandGain(int bandIndex, double gain) {
        gainDb[bandIndex] = Math.max(-MAX_GAIN_DB, Math.min(MAX_GAIN_DB, gain));
        rebuildFilters();
    }

    /**
     * Sets the gains for all bands at once, clamped to ±{@value #MAX_GAIN_DB} dB.
     *
     * @param gains array of gain values in dB (must match band count)
     */
    public void setAllBandGains(double[] gains) {
        Objects.requireNonNull(gains, "gains must not be null");
        if (gains.length != frequencies.length) {
            throw new IllegalArgumentException(
                    "gains length " + gains.length + " does not match band count " + frequencies.length);
        }
        for (int i = 0; i < gains.length; i++) {
            gainDb[i] = Math.max(-MAX_GAIN_DB, Math.min(MAX_GAIN_DB, gains[i]));
        }
        rebuildFilters();
    }

    /**
     * Resets all band gains to 0 dB (flat response).
     */
    public void flattenAllBands() {
        Arrays.fill(gainDb, 0.0);
        rebuildFilters();
    }

    /**
     * Returns the current Q factor for all bands.
     *
     * @return the Q factor
     */
    @ProcessorParam(id = 0, name = "Q", min = 0.1, max = 10.0, defaultValue = 1.0)
    public double getQ() {
        return q;
    }

    /**
     * Sets the Q factor for all bands.
     *
     * @param q the Q factor (must be positive)
     */
    public void setQ(double q) {
        if (q <= 0) {
            throw new IllegalArgumentException("q must be positive: " + q);
        }
        this.q = q;
        rebuildFilters();
    }

    /**
     * Returns a copy of the center frequencies array.
     *
     * @return the center frequencies in Hz
     */
    public double[] getFrequencies() {
        return Arrays.copyOf(frequencies, frequencies.length);
    }

    /**
     * Returns a copy of all band gains.
     *
     * @return the gains in dB
     */
    public double[] getAllBandGains() {
        return Arrays.copyOf(gainDb, gainDb.length);
    }

    // ---- AudioProcessor implementation ----

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        // Copy input to output
        for (int ch = 0; ch < Math.min(inputBuffer.length, outputBuffer.length); ch++) {
            System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
        }

        if (filterMode == FilterMode.LINEAR_PHASE) {
            if (linearFilters != null) {
                for (int ch = 0; ch < Math.min(channels, outputBuffer.length); ch++) {
                    linearFilters[ch].process(outputBuffer[ch], 0, numFrames);
                }
            }
        } else {
            for (int band = 0; band < filters.length; band++) {
                if (gainDb[band] == 0.0) {
                    continue;
                }
                for (int ch = 0; ch < Math.min(channels, outputBuffer.length); ch++) {
                    filters[band][ch].process(outputBuffer[ch], 0, numFrames);
                }
            }
        }
    }

    @Override
    public void reset() {
        for (BiquadFilter[] bandFilters : filters) {
            for (BiquadFilter filter : bandFilters) {
                filter.reset();
            }
        }
        if (linearFilters != null) {
            for (LinearPhaseFilter filter : linearFilters) {
                filter.reset();
            }
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

    // ---- Internal methods ----

    private void initBands() {
        double[] sourceFreqs = (bandType == BandType.OCTAVE)
                ? OCTAVE_FREQUENCIES
                : THIRD_OCTAVE_FREQUENCIES;

        // Filter out frequencies above Nyquist
        double nyquist = sampleRate / 2.0;
        int count = 0;
        for (double freq : sourceFreqs) {
            if (freq < nyquist) {
                count++;
            }
        }
        frequencies = new double[count];
        System.arraycopy(sourceFreqs, 0, frequencies, 0, count);

        gainDb = new double[count];
        rebuildFilters();
    }

    private void rebuildFilters() {
        // Always build minimum-phase biquad filters
        filters = new BiquadFilter[frequencies.length][channels];
        for (int band = 0; band < frequencies.length; band++) {
            for (int ch = 0; ch < channels; ch++) {
                filters[band][ch] = BiquadFilter.create(
                        BiquadFilter.FilterType.PEAK_EQ, sampleRate,
                        frequencies[band], q, gainDb[band]);
            }
        }

        // Build linear-phase composite if needed
        if (filterMode == FilterMode.LINEAR_PHASE) {
            // Collect biquads for bands with non-zero gain
            int activeCount = 0;
            for (double gain : gainDb) {
                if (gain != 0.0) {
                    activeCount++;
                }
            }
            if (activeCount > 0) {
                BiquadFilter[] activeBiquads = new BiquadFilter[activeCount];
                int idx = 0;
                for (int band = 0; band < frequencies.length; band++) {
                    if (gainDb[band] != 0.0) {
                        activeBiquads[idx++] = BiquadFilter.create(
                                BiquadFilter.FilterType.PEAK_EQ, sampleRate,
                                frequencies[band], q, gainDb[band]);
                    }
                }
                linearFilters = new LinearPhaseFilter[channels];
                for (int ch = 0; ch < channels; ch++) {
                    linearFilters[ch] = LinearPhaseFilter.fromBiquads(activeBiquads, firOrder);
                }
            } else {
                linearFilters = null;
            }
        } else {
            linearFilters = null;
        }
    }

    private static double defaultQForBandType(BandType type) {
        // Standard Q for constant-bandwidth graphic EQ:
        // Octave bandwidth → Q ≈ 1.414, Third-octave bandwidth → Q ≈ 4.318
        return (type == BandType.OCTAVE) ? 1.414 : 4.318;
    }
}
