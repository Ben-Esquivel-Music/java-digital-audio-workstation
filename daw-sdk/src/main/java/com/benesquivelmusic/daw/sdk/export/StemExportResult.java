package com.benesquivelmusic.daw.sdk.export;

import java.util.List;
import java.util.Objects;

/**
 * Result of a batch stem export operation.
 *
 * <p>Contains the individual {@link ExportResult} for each exported stem
 * and summary information about the overall operation.</p>
 *
 * @param trackResults the per-track export results (one per exported stem)
 * @param totalDurationMs the wall-clock time for the entire batch in milliseconds
 */
public record StemExportResult(
        List<ExportResult> trackResults,
        long totalDurationMs
) {

    public StemExportResult {
        Objects.requireNonNull(trackResults, "trackResults must not be null");
        trackResults = List.copyOf(trackResults);
    }

    /**
     * Returns {@code true} if all individual stem exports succeeded.
     *
     * @return whether every stem was exported successfully
     */
    public boolean allSucceeded() {
        for (ExportResult result : trackResults) {
            if (!result.success()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the number of stems that were successfully exported.
     *
     * @return the count of successful exports
     */
    public int successCount() {
        int count = 0;
        for (ExportResult result : trackResults) {
            if (result.success()) {
                count++;
            }
        }
        return count;
    }
}
