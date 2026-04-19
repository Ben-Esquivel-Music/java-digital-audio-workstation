package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.audio.SampleRateConverter;
import com.benesquivelmusic.daw.sdk.audio.SampleRateConverter.QualityTier;
import com.benesquivelmusic.daw.sdk.audio.SourceRateMetadata;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Process-wide cache of sample-rate-converted clip buffers.
 *
 * <p>The render pipeline calls {@link #get(String, SourceRateMetadata,
 * int, QualityTier, java.util.function.Supplier)} before emitting a
 * clip into the graph. If the native rate already matches the target
 * rate no conversion is performed and the raw buffer is returned via
 * the supplier. Otherwise, the cache checks its map of
 * {@code (clipId, targetRate, qualityTier)} entries and either returns
 * the previously converted buffer or invokes the supplied
 * {@link SampleRateConverter} once and memoizes the result.</p>
 *
 * <p>The cache is <b>not</b> real-time safe — conversion happens on a
 * background worker and the resulting buffer is inserted atomically —
 * but lookup on the audio thread is lock-free (a concurrent hash-map
 * read).</p>
 *
 * <p>Callers must invoke {@link #invalidateForClip(String)} when a clip
 * is edited or deleted and {@link #invalidateAll()} when the session
 * sample rate changes, so stale conversions are discarded.</p>
 */
public final class SampleRateConversionCache {

    /** Cache key: (clipId, targetRate, qualityTier). */
    private record Key(String clipId, int targetRateHz, QualityTier tier) {
        Key {
            Objects.requireNonNull(clipId, "clipId must not be null");
            Objects.requireNonNull(tier, "tier must not be null");
            if (targetRateHz <= 0) {
                throw new IllegalArgumentException(
                        "targetRateHz must be positive: " + targetRateHz);
            }
        }
    }

    private final ConcurrentMap<Key, float[][]> entries = new ConcurrentHashMap<>();

    /**
     * Returns a buffer at {@code sessionRateHz}, converting via
     * {@code converter} if the clip's native rate differs.
     *
     * <p>If {@code metadata} is {@code null} (legacy / native-rate
     * clips), the buffer supplier is returned verbatim — the engine
     * makes no assumption and performs no SRC.</p>
     *
     * @param clipId         the unique clip identifier
     * @param metadata       the clip's native rate metadata, or
     *                       {@code null} to skip SRC
     * @param sessionRateHz  the target session rate in Hz (positive)
     * @param tier           the quality tier to use
     * @param nativeSupplier supplier of the clip's raw native-rate
     *                       buffer ({@code [channel][sample]})
     * @return the buffer at {@code sessionRateHz}
     */
    public float[][] get(String clipId,
                         SourceRateMetadata metadata,
                         int sessionRateHz,
                         QualityTier tier,
                         java.util.function.Supplier<float[][]> nativeSupplier) {
        Objects.requireNonNull(clipId, "clipId must not be null");
        Objects.requireNonNull(tier, "tier must not be null");
        Objects.requireNonNull(nativeSupplier, "nativeSupplier must not be null");
        if (sessionRateHz <= 0) {
            throw new IllegalArgumentException(
                    "sessionRateHz must be positive: " + sessionRateHz);
        }
        if (metadata == null || !metadata.requiresConversion(sessionRateHz)) {
            return nativeSupplier.get();
        }
        Key key = new Key(clipId, sessionRateHz, tier);
        return entries.computeIfAbsent(key, k -> {
            float[][] native_ = nativeSupplier.get();
            Objects.requireNonNull(native_, "native buffer must not be null");
            SampleRateConverter converter = SampleRateConverter.of(tier);
            return converter.process(native_, metadata.nativeRateHz(), sessionRateHz);
        });
    }

    /**
     * Drops every cached conversion for a single clip (call when the
     * clip's audio data changes, it is trimmed, or it is deleted).
     *
     * @param clipId the clip identifier
     */
    public void invalidateForClip(String clipId) {
        Objects.requireNonNull(clipId, "clipId must not be null");
        entries.keySet().removeIf(k -> k.clipId().equals(clipId));
    }

    /**
     * Drops every cached conversion — call when the session sample
     * rate changes.
     */
    public void invalidateAll() {
        entries.clear();
    }

    /** Returns the current number of cached entries (for diagnostics). */
    public int size() {
        return entries.size();
    }
}
