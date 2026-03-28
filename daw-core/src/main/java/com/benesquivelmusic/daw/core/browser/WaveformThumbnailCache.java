package com.benesquivelmusic.daw.core.browser;

import com.benesquivelmusic.daw.sdk.visualization.WaveformData;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * LRU cache for waveform thumbnail data keyed by file path.
 *
 * <p>Stores pre-computed {@link WaveformData} thumbnails for previously
 * browsed audio files, avoiding repeated file I/O and waveform generation.
 * When the cache exceeds its maximum capacity the oldest entries are evicted.</p>
 *
 * <p>This class is not thread-safe. External synchronization is required
 * if accessed from multiple threads.</p>
 */
public final class WaveformThumbnailCache {

    private static final int DEFAULT_MAX_ENTRIES = 256;

    private final Map<Path, WaveformData> cache;
    private final int maxEntries;

    /**
     * Creates a cache with the specified maximum number of entries.
     *
     * @param maxEntries the maximum number of thumbnails to cache
     */
    public WaveformThumbnailCache(int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive: " + maxEntries);
        }
        this.maxEntries = maxEntries;
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Path, WaveformData> eldest) {
                return size() > maxEntries;
            }
        };
    }

    /**
     * Creates a cache with the default maximum capacity (256 entries).
     */
    public WaveformThumbnailCache() {
        this(DEFAULT_MAX_ENTRIES);
    }

    /**
     * Retrieves a cached waveform thumbnail for the given file path.
     *
     * @param filePath the audio file path
     * @return the cached waveform data, or empty if not cached
     */
    public Optional<WaveformData> get(Path filePath) {
        Objects.requireNonNull(filePath, "filePath must not be null");
        WaveformData data = cache.get(filePath);
        return Optional.ofNullable(data);
    }

    /**
     * Stores a waveform thumbnail in the cache.
     *
     * @param filePath the audio file path
     * @param data     the waveform data to cache
     */
    public void put(Path filePath, WaveformData data) {
        Objects.requireNonNull(filePath, "filePath must not be null");
        Objects.requireNonNull(data, "data must not be null");
        cache.put(filePath, data);
    }

    /**
     * Returns whether the cache contains a thumbnail for the given file path.
     *
     * @param filePath the audio file path
     * @return {@code true} if the path is cached
     */
    public boolean contains(Path filePath) {
        Objects.requireNonNull(filePath, "filePath must not be null");
        return cache.containsKey(filePath);
    }

    /**
     * Returns the current number of cached thumbnails.
     *
     * @return the cache size
     */
    public int size() {
        return cache.size();
    }

    /**
     * Returns the maximum number of entries this cache can hold.
     *
     * @return the maximum capacity
     */
    public int maxEntries() {
        return maxEntries;
    }

    /**
     * Removes all cached thumbnails.
     */
    public void clear() {
        cache.clear();
    }
}
