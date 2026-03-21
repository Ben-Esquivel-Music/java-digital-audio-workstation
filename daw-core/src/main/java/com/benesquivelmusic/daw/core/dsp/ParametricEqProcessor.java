package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Professional parametric equalizer with configurable filter bands.
 *
 * <p>Each band is an independent biquad filter that can be set to any
 * supported filter type (peak EQ, low-shelf, high-shelf, high-pass,
 * low-pass, etc.). Bands are processed in series.</p>
 *
 * <p>Implements the EQ processing described in the mastering-techniques
 * research (§3 — Equalization and Tonal Balancing), supporting:
 * <ul>
 *   <li>Parametric peak EQ for surgical corrections</li>
 *   <li>Shelving EQ for broad tonal shaping</li>
 *   <li>High-pass/low-pass filters for cleanup</li>
 * </ul>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
public final class ParametricEqProcessor implements AudioProcessor {

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
    private final List<BandConfig> bandConfigs = new ArrayList<>();
    private BiquadFilter[][] filters; // [band][channel]

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
    }

    /**
     * Adds a new EQ band.
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

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        // Copy input to output first
        for (int ch = 0; ch < Math.min(inputBuffer.length, outputBuffer.length); ch++) {
            System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
        }

        // Apply each enabled band
        for (int band = 0; band < filters.length; band++) {
            if (!bandConfigs.get(band).enabled()) continue;
            for (int ch = 0; ch < Math.min(channels, outputBuffer.length); ch++) {
                filters[band][ch].process(outputBuffer[ch], 0, numFrames);
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
    }

    @Override
    public int getInputChannelCount() {
        return channels;
    }

    @Override
    public int getOutputChannelCount() {
        return channels;
    }

    private void rebuildFilters() {
        filters = new BiquadFilter[bandConfigs.size()][channels];
        for (int band = 0; band < bandConfigs.size(); band++) {
            BandConfig config = bandConfigs.get(band);
            for (int ch = 0; ch < channels; ch++) {
                filters[band][ch] = BiquadFilter.create(
                        config.type(), sampleRate, config.frequency(),
                        config.q(), config.gainDb());
            }
        }
    }
}
