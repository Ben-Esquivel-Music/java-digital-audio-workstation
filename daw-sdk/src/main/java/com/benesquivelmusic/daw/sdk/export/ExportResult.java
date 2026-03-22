package com.benesquivelmusic.daw.sdk.export;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Result of a single audio export operation.
 *
 * @param config      the configuration used for this export
 * @param outputPath  the path to the written file
 * @param success     {@code true} if the export completed without errors
 * @param message     a human-readable summary or error description
 * @param durationMs  the wall-clock time of the export in milliseconds
 */
public record ExportResult(
        AudioExportConfig config,
        Path outputPath,
        boolean success,
        String message,
        long durationMs
) {

    public ExportResult {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(outputPath, "outputPath must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }
}
