package com.benesquivelmusic.daw.core.audio.cache;

import java.util.Map;
import java.util.Objects;

/**
 * Snapshot of {@link RenderedTrackCache} statistics, suitable for
 * display in {@code RenderCacheStatsDialog} or for logging.
 *
 * @param totalSizeBytes      total bytes used across every project
 *                            directory
 * @param perProjectSizeBytes per-project size in bytes (project UUID
 *                            string → byte count)
 * @param sessionHits         number of cache hits since the cache
 *                            instance was created
 * @param sessionMisses       number of cache misses since the cache
 *                            instance was created
 */
public record RenderCacheStats(
        long totalSizeBytes,
        Map<String, Long> perProjectSizeBytes,
        long sessionHits,
        long sessionMisses) {

    public RenderCacheStats {
        if (totalSizeBytes < 0) {
            throw new IllegalArgumentException(
                    "totalSizeBytes must be non-negative: " + totalSizeBytes);
        }
        if (sessionHits < 0) {
            throw new IllegalArgumentException(
                    "sessionHits must be non-negative: " + sessionHits);
        }
        if (sessionMisses < 0) {
            throw new IllegalArgumentException(
                    "sessionMisses must be non-negative: " + sessionMisses);
        }
        Objects.requireNonNull(perProjectSizeBytes, "perProjectSizeBytes must not be null");
        perProjectSizeBytes = Map.copyOf(perProjectSizeBytes);
    }

    /**
     * Returns the session hit rate as a fraction in {@code [0, 1]},
     * or {@code 0.0} if the cache has not yet been queried.
     */
    public double hitRate() {
        long total = sessionHits + sessionMisses;
        return total == 0 ? 0.0 : (double) sessionHits / (double) total;
    }
}
