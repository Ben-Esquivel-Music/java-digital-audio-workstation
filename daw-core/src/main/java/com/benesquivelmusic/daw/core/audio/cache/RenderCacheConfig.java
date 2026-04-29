package com.benesquivelmusic.daw.core.audio.cache;

/**
 * Configuration for the {@link RenderedTrackCache}.
 *
 * @param perProjectQuotaBytes maximum cache bytes per project before
 *                             LRU eviction trims the oldest entries;
 *                             must be positive
 */
public record RenderCacheConfig(long perProjectQuotaBytes) {

    /** Default 5 GiB per-project quota (story 206). */
    public static final long DEFAULT_PER_PROJECT_QUOTA_BYTES = 5L * 1024L * 1024L * 1024L;

    public RenderCacheConfig {
        if (perProjectQuotaBytes <= 0) {
            throw new IllegalArgumentException(
                    "perProjectQuotaBytes must be positive: " + perProjectQuotaBytes);
        }
    }

    /** Returns the default configuration (5 GiB per project). */
    public static RenderCacheConfig defaults() {
        return new RenderCacheConfig(DEFAULT_PER_PROJECT_QUOTA_BYTES);
    }
}
