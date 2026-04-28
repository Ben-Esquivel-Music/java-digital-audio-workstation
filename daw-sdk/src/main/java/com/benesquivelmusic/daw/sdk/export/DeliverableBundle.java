package com.benesquivelmusic.daw.sdk.export;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * A single-click deliverable specification: the mastered stereo render,
 * the stems, project metadata, and an optional PDF track-sheet, all
 * packaged into a single ZIP archive.
 *
 * <p>This is the input record consumed by the bundle-export service.
 * Mastering engineers and music supervisors typically expect:</p>
 * <ul>
 *   <li>{@code &lt;Project&gt;_Master.wav} — the mastered stereo</li>
 *   <li>{@code stems/&lt;Stem&gt;.wav} — one file per logical stem</li>
 *   <li>{@code metadata.json} — project + per-stem descriptors and measurements</li>
 *   <li>{@code track_sheet.pdf} — optional human-readable level overview</li>
 * </ul>
 *
 * @param zipOutput          the path to the output zip archive
 * @param master             the master render format and base filename;
 *                           may be {@code null} for a "stems-only" delivery
 * @param stems              the list of stem specifications (may be empty
 *                           for a "master-only" delivery)
 * @param metadata           project- and bundle-level metadata; the
 *                           service replaces measurements with computed
 *                           values during export
 * @param includeTrackSheet  whether to include a {@code track_sheet.pdf}
 *                           with per-stem peak/RMS/LUFS measurements
 */
public record DeliverableBundle(
        Path zipOutput,
        MasterFormat master,
        List<StemSpec> stems,
        BundleMetadata metadata,
        boolean includeTrackSheet
) {

    public DeliverableBundle {
        Objects.requireNonNull(zipOutput, "zipOutput must not be null");
        Objects.requireNonNull(stems, "stems must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
        if (master == null && stems.isEmpty()) {
            throw new IllegalArgumentException(
                    "DeliverableBundle must include a master, at least one stem, or both");
        }
        stems = List.copyOf(stems);
    }
}
