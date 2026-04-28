package com.benesquivelmusic.daw.sdk.export;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Project- and bundle-level metadata embedded in the {@code metadata.json}
 * inside a {@link DeliverableBundle}.
 *
 * <p>Carries the session tempo, key, sample rate, bit depth, master
 * loudness measurements, per-stem descriptors (see {@link StemMetadata}),
 * project title, engineer name, and a render timestamp. Mastering
 * engineers and supervisors can read this file to confirm delivery
 * details without opening the DAW or any audio file.</p>
 *
 * <p>When constructed by a user before export, the per-stem measurements
 * may be empty placeholders; the {@code BundleExportService} produces a
 * final {@code BundleMetadata} with computed measurements after rendering.
 * </p>
 *
 * @param projectTitle    the project / song title
 * @param engineer        the mix engineer name (free-form)
 * @param tempo           the session tempo in BPM
 * @param key             the musical key (free-form, e.g. {@code "Cm"} or {@code "F# major"});
 *                        may be empty
 * @param sampleRate      the project sample rate in Hz (matches master)
 * @param bitDepth        the project bit depth (matches master)
 * @param masterChannels  the master channel count (typically 2)
 * @param integratedLufs  the master integrated loudness in LUFS
 * @param truePeakDbfs    the master peak in dBFS (sample peak, not oversampled)
 * @param renderedAt      the render timestamp (UTC ISO-8601)
 * @param stems           per-stem descriptors with measurements
 */
public record BundleMetadata(
        String projectTitle,
        String engineer,
        double tempo,
        String key,
        int sampleRate,
        int bitDepth,
        int masterChannels,
        double integratedLufs,
        double truePeakDbfs,
        Instant renderedAt,
        List<StemMetadata> stems
) {

    public BundleMetadata {
        Objects.requireNonNull(projectTitle, "projectTitle must not be null");
        Objects.requireNonNull(engineer, "engineer must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(renderedAt, "renderedAt must not be null");
        Objects.requireNonNull(stems, "stems must not be null");
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (bitDepth <= 0) {
            throw new IllegalArgumentException("bitDepth must be positive: " + bitDepth);
        }
        if (masterChannels <= 0) {
            throw new IllegalArgumentException(
                    "masterChannels must be positive: " + masterChannels);
        }
        stems = List.copyOf(stems);
    }

    /**
     * Convenience builder for an input metadata template (no measurements yet).
     *
     * @param projectTitle the project title
     * @param engineer     the mix engineer name
     * @param tempo        the tempo in BPM
     * @param key          the musical key
     * @param sampleRate   the sample rate
     * @param bitDepth     the bit depth
     * @return a metadata record with empty stem list and zeroed master measurements
     */
    public static BundleMetadata template(
            String projectTitle, String engineer,
            double tempo, String key,
            int sampleRate, int bitDepth) {
        return new BundleMetadata(
                projectTitle, engineer, tempo, key,
                sampleRate, bitDepth, 2,
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Instant.EPOCH, List.of());
    }

    /**
     * Returns a copy of this metadata with the given measurements and stem list.
     *
     * @param masterChannels  the master channel count
     * @param integratedLufs  master integrated loudness
     * @param truePeakDbfs    master peak in dBFS
     * @param renderedAt      render timestamp
     * @param stems           per-stem descriptors
     * @return a new {@code BundleMetadata} with the measurements applied
     */
    public BundleMetadata withMeasurements(
            int masterChannels,
            double integratedLufs,
            double truePeakDbfs,
            Instant renderedAt,
            List<StemMetadata> stems) {
        return new BundleMetadata(
                projectTitle, engineer, tempo, key,
                sampleRate, bitDepth, masterChannels,
                integratedLufs, truePeakDbfs, renderedAt, stems);
    }
}
