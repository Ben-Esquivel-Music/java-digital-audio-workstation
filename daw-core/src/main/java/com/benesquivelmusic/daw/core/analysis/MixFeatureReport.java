package com.benesquivelmusic.daw.core.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable report produced by {@link MixFeatureAnalyzer} describing the
 * low-level audio features of a multitrack mix.
 *
 * <p>Contains a {@link TrackFeatures} entry per analyzed track and a
 * {@link AggregateFeatures} summary with session-level statistics.</p>
 *
 * <p>Based on the feature set defined in <em>Variation in Multitrack
 * Mixes: Analysis of Low-level Audio Signal Features</em> (AES, 2016):
 * spectral centroid, spectral spread, spectral flux, RMS level, crest
 * factor, and stereo width — extended with per-track peak level, LUFS,
 * per-band energy ratios, and session dynamic range.</p>
 *
 * @param tracks    per-track feature measurements in input order
 * @param aggregate session-level aggregate statistics
 */
public record MixFeatureReport(
        List<TrackFeatures> tracks,
        AggregateFeatures aggregate
) {

    public MixFeatureReport {
        if (tracks == null) {
            throw new IllegalArgumentException("tracks must not be null");
        }
        if (aggregate == null) {
            throw new IllegalArgumentException("aggregate must not be null");
        }
        tracks = List.copyOf(tracks);
    }

    /**
     * Per-track low-level audio features.
     *
     * @param name              human-readable track identifier
     * @param rmsDb             RMS level in dBFS (mono-mix)
     * @param peakDb            sample peak level in dBFS
     * @param crestFactorDb     crest factor (peak/RMS) in dB
     * @param spectralCentroidHz spectral centroid in Hz
     * @param spectralSpreadHz  spectral spread (bandwidth) in Hz around the centroid
     * @param spectralFlux      mean frame-to-frame spectral flux (L2 distance of magnitude spectra)
     * @param stereoWidth       stereo width in [0.0, 1.0] (0 = mono, 1 = maximally wide)
     * @param integratedLufs    ITU-R BS.1770 integrated loudness (LUFS)
     * @param bandEnergyRatios  fractional energy in low/mid/high bands, sums to 1.0
     *                          when total energy &gt; 0 (otherwise all 0)
     */
    public record TrackFeatures(
            String name,
            double rmsDb,
            double peakDb,
            double crestFactorDb,
            double spectralCentroidHz,
            double spectralSpreadHz,
            double spectralFlux,
            double stereoWidth,
            double integratedLufs,
            BandEnergyRatios bandEnergyRatios
    ) {
        public TrackFeatures {
            if (name == null) {
                throw new IllegalArgumentException("name must not be null");
            }
            if (bandEnergyRatios == null) {
                throw new IllegalArgumentException("bandEnergyRatios must not be null");
            }
        }
    }

    /**
     * Fractional energy distribution across low/mid/high frequency bands.
     *
     * <p>Band edges default to 250 Hz and 4 kHz. Ratios sum to 1.0 when
     * the source has non-zero energy, otherwise all three values are 0.</p>
     *
     * @param low  fractional energy below the low/mid crossover
     * @param mid  fractional energy between the crossovers
     * @param high fractional energy above the mid/high crossover
     */
    public record BandEnergyRatios(double low, double mid, double high) {
        /** Empty band ratios (all zero). */
        public static final BandEnergyRatios EMPTY = new BandEnergyRatios(0.0, 0.0, 0.0);
    }

    /**
     * Session-level aggregate statistics computed across all tracks.
     *
     * @param trackCount              number of tracks analyzed
     * @param meanRmsDb               arithmetic mean of per-track RMS levels (dB)
     * @param meanPeakDb              arithmetic mean of per-track peak levels (dB)
     * @param meanCrestFactorDb       arithmetic mean of per-track crest factors (dB)
     * @param meanSpectralCentroidHz  arithmetic mean spectral centroid (Hz)
     * @param meanSpectralSpreadHz    arithmetic mean spectral spread (Hz)
     * @param meanSpectralFlux        arithmetic mean spectral flux
     * @param meanStereoWidth         arithmetic mean stereo width
     * @param meanIntegratedLufs      arithmetic mean of per-track integrated LUFS
     *                                (ignoring −Infinity silence tracks)
     * @param dynamicRangeDb          session dynamic range: loudest peak dB − softest RMS dB
     * @param bandEnergyRatios        energy-weighted session-level band ratios
     */
    public record AggregateFeatures(
            int trackCount,
            double meanRmsDb,
            double meanPeakDb,
            double meanCrestFactorDb,
            double meanSpectralCentroidHz,
            double meanSpectralSpreadHz,
            double meanSpectralFlux,
            double meanStereoWidth,
            double meanIntegratedLufs,
            double dynamicRangeDb,
            BandEnergyRatios bandEnergyRatios
    ) {
        public AggregateFeatures {
            if (trackCount < 0) {
                throw new IllegalArgumentException("trackCount must be non-negative");
            }
            if (bandEnergyRatios == null) {
                throw new IllegalArgumentException("bandEnergyRatios must not be null");
            }
        }

        /** Empty aggregate for reports with no tracks. */
        public static final AggregateFeatures EMPTY = new AggregateFeatures(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, BandEnergyRatios.EMPTY);
    }

    /**
     * Delta between two {@link TrackFeatures} measurements.
     *
     * <p>All fields are {@code b − a}. Positive values indicate the
     * second mix has a higher value for the given feature.</p>
     */
    public record TrackFeatureDelta(
            String name,
            double rmsDbDelta,
            double peakDbDelta,
            double crestFactorDbDelta,
            double spectralCentroidHzDelta,
            double spectralSpreadHzDelta,
            double spectralFluxDelta,
            double stereoWidthDelta,
            double integratedLufsDelta
    ) {}

    /**
     * Result of comparing two mixes feature by feature. Contains a
     * per-track delta list (only for tracks present in both mixes, matched
     * by name in input order) and an aggregate delta.
     *
     * @param trackDeltas     per-track deltas (b − a)
     * @param aggregateDelta  aggregate-level delta (b − a)
     */
    public record Comparison(
            List<TrackFeatureDelta> trackDeltas,
            AggregateFeatureDelta aggregateDelta
    ) {
        public Comparison {
            if (trackDeltas == null) {
                throw new IllegalArgumentException("trackDeltas must not be null");
            }
            if (aggregateDelta == null) {
                throw new IllegalArgumentException("aggregateDelta must not be null");
            }
            trackDeltas = List.copyOf(trackDeltas);
        }
    }

    /**
     * Delta between two {@link AggregateFeatures} measurements (b − a).
     */
    public record AggregateFeatureDelta(
            double meanRmsDbDelta,
            double meanPeakDbDelta,
            double meanCrestFactorDbDelta,
            double meanSpectralCentroidHzDelta,
            double meanSpectralSpreadHzDelta,
            double meanSpectralFluxDelta,
            double meanStereoWidthDelta,
            double meanIntegratedLufsDelta,
            double dynamicRangeDbDelta
    ) {}

    /** Empty report with no tracks. */
    public static MixFeatureReport empty() {
        return new MixFeatureReport(
                Collections.unmodifiableList(new ArrayList<>()),
                AggregateFeatures.EMPTY);
    }
}
