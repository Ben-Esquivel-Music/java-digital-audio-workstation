package com.benesquivelmusic.daw.sdk.session;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Result of exporting a session file.
 *
 * <p>Contains the path to the exported file and a list of warning
 * messages for features that could not be represented in the target format.</p>
 *
 * @param outputPath the path to the exported file
 * @param warnings   warnings about features that could not be exported
 */
public record SessionExportResult(
        Path outputPath,
        List<String> warnings
) {
    public SessionExportResult {
        Objects.requireNonNull(outputPath, "outputPath must not be null");
        Objects.requireNonNull(warnings, "warnings must not be null");
        warnings = List.copyOf(warnings);
    }
}
