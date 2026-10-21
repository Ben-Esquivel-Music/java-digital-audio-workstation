package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.audio.SampleRateConverter.QualityTier;
import com.benesquivelmusic.daw.sdk.audio.SourceRateMetadata;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SampleRateConversionCacheTest {

    private static float[][] makeBuffer(int frames) {
        float[][] buf = new float[1][frames];
        for (int i = 0; i < frames; i++) {
            buf[0][i] = (float) Math.sin(2.0 * Math.PI * 440.0 * i / 44_100.0);
        }
        return buf;
    }

    @Test
    void bypassesConversionWhenMetadataNull() {
        SampleRateConversionCache cache = new SampleRateConversionCache();
        float[][] raw = makeBuffer(100);
        AtomicInteger calls = new AtomicInteger();

        float[][] out = cache.get("clip-1", null, 48_000, QualityTier.HIGH, () -> {
            calls.incrementAndGet();
            return raw;
        });

        assertThat(out).isSameAs(raw);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(cache.size()).isZero();
    }

    @Test
    void bypassesConversionWhenRatesMatch() {
        SampleRateConversionCache cache = new SampleRateConversionCache();
        float[][] raw = makeBuffer(100);
        SourceRateMetadata meta = SourceRateMetadata.of(48_000, 1);

        float[][] out = cache.get("clip-1", meta, 48_000, QualityTier.HIGH, () -> raw);
        assertThat(out).isSameAs(raw);
        assertThat(cache.size()).isZero();
    }

    @Test
    void convertsWhenRatesDifferAndMemoizes() {
        SampleRateConversionCache cache = new SampleRateConversionCache();
        float[][] raw = makeBuffer(4_410);  // 100 ms @ 44.1kHz
        SourceRateMetadata meta = SourceRateMetadata.of(44_100, 1);
        AtomicInteger calls = new AtomicInteger();

        float[][] first  = cache.get("clip-1", meta, 48_000, QualityTier.MEDIUM,
                () -> { calls.incrementAndGet(); return raw; });
        float[][] second = cache.get("clip-1", meta, 48_000, QualityTier.MEDIUM,
                () -> { calls.incrementAndGet(); return raw; });

        // The supplier is consulted on every lookup — the entry is served only
        // while the clip still holds the array it converted — but converts once.
        assertThat(calls.get()).isEqualTo(2);
        assertThat(first).isSameAs(second);            // cached buffer returned
        assertThat(first[0].length).isEqualTo(4_800);  // 100 ms @ 48 kHz
        assertThat(first).isNotSameAs(raw);
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void differentTiersProduceDifferentEntries() {
        SampleRateConversionCache cache = new SampleRateConversionCache();
        float[][] raw = makeBuffer(200);
        SourceRateMetadata meta = SourceRateMetadata.of(44_100, 1);

        cache.get("clip-1", meta, 48_000, QualityTier.LOW,    () -> raw);
        cache.get("clip-1", meta, 48_000, QualityTier.MEDIUM, () -> raw);
        cache.get("clip-1", meta, 48_000, QualityTier.HIGH,   () -> raw);

        assertThat(cache.size()).isEqualTo(3);
    }

    @Test
    void differentTargetRatesProduceDifferentEntries() {
        SampleRateConversionCache cache = new SampleRateConversionCache();
        float[][] raw = makeBuffer(200);
        SourceRateMetadata meta = SourceRateMetadata.of(44_100, 1);

        cache.get("clip-1", meta, 48_000, QualityTier.MEDIUM, () -> raw);
        cache.get("clip-1", meta, 96_000, QualityTier.MEDIUM, () -> raw);

        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    void invalidateForClipDropsOnlyThatClip() {
        SampleRateConversionCache cache = new SampleRateConversionCache();
        float[][] raw = makeBuffer(200);
        SourceRateMetadata meta = SourceRateMetadata.of(44_100, 1);

        cache.get("clip-A", meta, 48_000, QualityTier.MEDIUM, () -> raw);
        cache.get("clip-B", meta, 48_000, QualityTier.MEDIUM, () -> raw);
        assertThat(cache.size()).isEqualTo(2);

        cache.invalidateForClip("clip-A");
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void invalidateAllClearsEverything() {
        SampleRateConversionCache cache = new SampleRateConversionCache();
        float[][] raw = makeBuffer(200);
        SourceRateMetadata meta = SourceRateMetadata.of(44_100, 1);

        cache.get("clip-A", meta, 48_000, QualityTier.MEDIUM, () -> raw);
        cache.get("clip-B", meta, 96_000, QualityTier.HIGH,   () -> raw);
        assertThat(cache.size()).isEqualTo(2);

        cache.invalidateAll();
        assertThat(cache.size()).isZero();
    }

    @Test
    void sessionRateChangeInvalidatesAndCausesReConversion() {
        // Models the "cache invalidation when session rate changes"
        // acceptance criterion from the issue.
        SampleRateConversionCache cache = new SampleRateConversionCache();
        float[][] raw = makeBuffer(200);
        SourceRateMetadata meta = SourceRateMetadata.of(44_100, 1);
        AtomicInteger calls = new AtomicInteger();

        cache.get("clip-1", meta, 48_000, QualityTier.MEDIUM,
                () -> { calls.incrementAndGet(); return raw; });
        cache.invalidateAll();  // session rate changed
        cache.get("clip-1", meta, 48_000, QualityTier.MEDIUM,
                () -> { calls.incrementAndGet(); return raw; });

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void aConversionOfTheOldArrayStoredAfterTheInvalidationIsNeverServedForTheNewArray() {
        SampleRateConversionCache cache = new SampleRateConversionCache();
        SourceRateMetadata meta = SourceRateMetadata.of(44_100, 1);
        float[][] oldAudio = makeBuffer(4_410);
        float[][] newAudio = new float[1][4_410];
        Arrays.fill(newAudio[0], 0.5f);
        float[][] newConverted = new SampleRateConversionCache()
                .get("elsewhere", meta, 48_000, QualityTier.MEDIUM, () -> newAudio);
        float[][] oldConverted = cache.get("clip-1", meta, 48_000, QualityTier.MEDIUM, () -> oldAudio);

        // A relink publishes the new array and invalidates the clip, but a
        // render block that had read the old array before the publish misses
        // the cache afterwards and stores a conversion of the OLD array.
        cache.invalidateForClip("clip-1");
        float[][] lateOldConversion = cache.get("clip-1", meta, 48_000, QualityTier.MEDIUM, () -> oldAudio);
        // Every later block reads the new array.
        float[][] served = cache.get("clip-1", meta, 48_000, QualityTier.MEDIUM, () -> newAudio);

        assertThat(lateOldConversion).as("fixture: the late block converted the old audio")
                .isNotSameAs(oldConverted);
        assertThat(served).as("never the old audio's conversion").isNotSameAs(lateOldConversion);
        assertThat(Arrays.deepEquals(served, newConverted)).as("a conversion of the new array").isTrue();
        assertThat(Arrays.deepEquals(served, oldConverted)).isFalse();
        assertThat(cache.get("clip-1", meta, 48_000, QualityTier.MEDIUM, () -> newAudio))
                .as("and from then on the cached conversion of the new array").isSameAs(served);
        assertThat(cache.size()).as("the entry was replaced, not added").isEqualTo(1);
    }

    @Test
    void rejectsNullArguments() {
        SampleRateConversionCache cache = new SampleRateConversionCache();
        assertThatThrownBy(() -> cache.get(null, null, 48_000, QualityTier.LOW, () -> new float[1][1]))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> cache.get("c", null, 48_000, null, () -> new float[1][1]))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> cache.get("c", null, 48_000, QualityTier.LOW, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNonPositiveSessionRate() {
        SampleRateConversionCache cache = new SampleRateConversionCache();
        assertThatThrownBy(() -> cache.get("c", null, 0, QualityTier.LOW, () -> new float[1][1]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
