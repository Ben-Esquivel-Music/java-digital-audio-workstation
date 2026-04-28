package com.benesquivelmusic.daw.sdk.export;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Named presets for common deliverable-bundle shapes.
 *
 * <p>Each preset captures the typical defaults for a delivery target:
 * which formats are used for master and stems, whether a track sheet PDF
 * is included, and whether master / stems are present. The preset is
 * applied to a project + metadata template + selected track indices to
 * produce a {@link DeliverableBundle}.</p>
 *
 * <p>Built-in presets:</p>
 * <ul>
 *   <li>{@link #MASTER_AND_STEMS} — "Master + Stems for Mastering"</li>
 *   <li>{@link #MASTER_ONLY} — "Master Only"</li>
 *   <li>{@link #STEMS_WITH_REFERENCE} — "Stems Only with Reference Master"</li>
 *   <li>{@link #STREAMING_DELIVERY} — "Streaming Delivery Bundle"</li>
 * </ul>
 *
 * @param name              human-readable preset name
 * @param masterConfig      audio config used for the master render;
 *                          {@code null} if the preset has no master
 * @param stemConfig        audio config used for each stem render;
 *                          {@code null} if the preset has no stems
 * @param includeTrackSheet whether the preset includes the PDF track sheet
 */
public record BundlePreset(
        String name,
        AudioExportConfig masterConfig,
        AudioExportConfig stemConfig,
        boolean includeTrackSheet
) {

    public BundlePreset {
        Objects.requireNonNull(name, "name must not be null");
        if (masterConfig == null && stemConfig == null) {
            throw new IllegalArgumentException(
                    "preset must define at least a masterConfig or a stemConfig");
        }
    }

    /** Master + Stems for Mastering: 24-bit / 96 kHz WAV, with track-sheet PDF. */
    public static final BundlePreset MASTER_AND_STEMS = new BundlePreset(
            "Master + Stems for Mastering",
            new AudioExportConfig(AudioExportFormat.WAV, 96_000, 24, DitherType.NONE),
            new AudioExportConfig(AudioExportFormat.WAV, 96_000, 24, DitherType.NONE),
            true);

    /** Master Only: 24-bit / 48 kHz WAV; no stems, no track sheet. */
    public static final BundlePreset MASTER_ONLY = new BundlePreset(
            "Master Only",
            new AudioExportConfig(AudioExportFormat.WAV, 48_000, 24, DitherType.NONE),
            null,
            false);

    /**
     * Stems Only with Reference Master: 24-bit / 48 kHz WAV stems plus a
     * reference master, with track-sheet PDF.
     */
    public static final BundlePreset STEMS_WITH_REFERENCE = new BundlePreset(
            "Stems Only with Reference Master",
            new AudioExportConfig(AudioExportFormat.WAV, 48_000, 24, DitherType.TPDF),
            new AudioExportConfig(AudioExportFormat.WAV, 48_000, 24, DitherType.NONE),
            true);

    /**
     * Streaming Delivery Bundle: 16-bit / 44.1 kHz FLAC master and stems,
     * with track-sheet PDF.
     */
    public static final BundlePreset STREAMING_DELIVERY = new BundlePreset(
            "Streaming Delivery Bundle",
            new AudioExportConfig(AudioExportFormat.FLAC, 44_100, 16, DitherType.TPDF),
            new AudioExportConfig(AudioExportFormat.FLAC, 44_100, 16, DitherType.TPDF),
            true);

    /**
     * Returns the built-in presets in display order.
     *
     * @return an unmodifiable list of presets
     */
    public static List<BundlePreset> builtIns() {
        return List.of(MASTER_AND_STEMS, MASTER_ONLY, STEMS_WITH_REFERENCE, STREAMING_DELIVERY);
    }

    /**
     * Builds a {@link DeliverableBundle} from this preset, project metadata,
     * the selected track indices, and an output zip path.
     *
     * @param zipOutput     the path to the output zip
     * @param projectTracks the available tracks (track name → index)
     * @param selectedStems list of {@code (trackIndex, stemName)} pairs to
     *                      include as stems (ignored if the preset has no
     *                      stem config)
     * @param metadata      the project metadata template
     * @return a {@code DeliverableBundle} suitable for {@code BundleExportService.export(...)}
     */
    public DeliverableBundle toBundle(
            Path zipOutput,
            String masterBaseName,
            List<StemDescriptor> selectedStems,
            BundleMetadata metadata) {
        Objects.requireNonNull(zipOutput, "zipOutput must not be null");
        Objects.requireNonNull(masterBaseName, "masterBaseName must not be null");
        Objects.requireNonNull(selectedStems, "selectedStems must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");

        MasterFormat master = (masterConfig != null)
                ? new MasterFormat(masterConfig, masterBaseName)
                : null;

        List<StemSpec> stems;
        if (stemConfig == null) {
            stems = List.of();
        } else {
            stems = selectedStems.stream()
                    .map(d -> new StemSpec(d.trackIndex(), d.stemName(), stemConfig))
                    .toList();
        }
        return new DeliverableBundle(zipOutput, master, stems, metadata, includeTrackSheet);
    }

    /**
     * A lightweight pairing of track index and stem name used to build
     * {@link DeliverableBundle}s from a {@link BundlePreset} and a list of
     * selected tracks.
     *
     * @param trackIndex the project track index
     * @param stemName   the human-readable stem name (also the file
     *                   base name)
     */
    public record StemDescriptor(int trackIndex, String stemName) {
        public StemDescriptor {
            Objects.requireNonNull(stemName, "stemName must not be null");
            if (trackIndex < 0) {
                throw new IllegalArgumentException(
                        "trackIndex must not be negative: " + trackIndex);
            }
        }
    }
}
