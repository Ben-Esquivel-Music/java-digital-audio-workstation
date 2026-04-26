package com.benesquivelmusic.daw.core.persistence.archive;

import java.nio.file.Path;

/**
 * Summary of a save-as-archive or consolidate-in-place operation.
 *
 * @param outputPath        the archive file (.dawz) or consolidated project root
 * @param uniqueAssetCount  number of distinct assets included (deduplicated by SHA-256)
 * @param totalAssetBytes   total bytes of asset payload (sum of unique asset sizes)
 * @param projectDocBytes   size of the embedded project document in bytes
 */
public record ProjectArchiveSummary(
        Path outputPath,
        int uniqueAssetCount,
        long totalAssetBytes,
        long projectDocBytes
) {
    /** Estimated archive size, useful for progress UI before writing begins. */
    public long estimatedTotalBytes() {
        return totalAssetBytes + projectDocBytes;
    }
}
