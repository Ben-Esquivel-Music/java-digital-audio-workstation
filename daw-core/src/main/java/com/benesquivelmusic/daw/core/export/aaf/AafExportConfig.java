package com.benesquivelmusic.daw.core.export.aaf;

import java.util.List;
import java.util.Objects;

/**
 * User-configurable settings for an OMF / AAF export, populated by the
 * {@code AafExportDialog} and consumed by {@link AafExportService}.
 *
 * @param frameRate           timeline frame rate (one of the supported
 *                            broadcast / cinema rates)
 * @param startTimecode       user-configurable start-of-timeline
 *                            timecode (anchored to {@link #frameRate})
 * @param embedMedia          {@code true} → write a self-contained file
 *                            with PCM media embedded; {@code false}
 *                            → reference-only file (smaller, downstream
 *                            tool resolves source paths)
 * @param includedTrackIndices zero-based indices of tracks to include
 *                             (empty == include all audio tracks)
 * @param compositionName     name to use for the AAF
 *                            {@code CompositionMob}
 */
public record AafExportConfig(AafFrameRate frameRate,
                              AafTimecode startTimecode,
                              boolean embedMedia,
                              List<Integer> includedTrackIndices,
                              String compositionName) {

    public AafExportConfig {
        Objects.requireNonNull(frameRate, "frameRate must not be null");
        Objects.requireNonNull(startTimecode, "startTimecode must not be null");
        Objects.requireNonNull(includedTrackIndices, "includedTrackIndices must not be null");
        Objects.requireNonNull(compositionName, "compositionName must not be null");
        if (startTimecode.frameRate() != frameRate) {
            throw new IllegalArgumentException(
                    "startTimecode frame rate must match config frame rate");
        }
        includedTrackIndices = List.copyOf(includedTrackIndices);
    }

    /**
     * Convenience constructor for an export at zero start-TC, all tracks
     * included, with media referenced (not embedded).
     */
    public static AafExportConfig defaults(String compositionName, AafFrameRate frameRate) {
        return new AafExportConfig(
                frameRate,
                AafTimecode.zero(frameRate),
                /* embedMedia */ false,
                List.of(),
                compositionName);
    }
}
