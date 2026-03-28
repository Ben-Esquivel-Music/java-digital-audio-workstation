package com.benesquivelmusic.daw.sdk.export;

import java.util.List;
import java.util.Objects;

/**
 * Configuration for a batch stem export operation.
 *
 * <p>Specifies which tracks (by index) to export, the audio export
 * configuration (format, sample rate, bit depth, dithering), the
 * naming convention, and the project name used for filename generation.</p>
 *
 * @param trackIndices      the indices of tracks to export (into the project's track list)
 * @param audioExportConfig the audio format configuration for all stems
 * @param namingConvention  the naming convention for output filenames
 * @param projectName       the project name (used by {@link StemNamingConvention#PROJECT_PREFIX})
 */
public record StemExportConfig(
        List<Integer> trackIndices,
        AudioExportConfig audioExportConfig,
        StemNamingConvention namingConvention,
        String projectName
) {

    public StemExportConfig {
        Objects.requireNonNull(trackIndices, "trackIndices must not be null");
        Objects.requireNonNull(audioExportConfig, "audioExportConfig must not be null");
        Objects.requireNonNull(namingConvention, "namingConvention must not be null");
        Objects.requireNonNull(projectName, "projectName must not be null");
        trackIndices = List.copyOf(trackIndices);
    }
}
