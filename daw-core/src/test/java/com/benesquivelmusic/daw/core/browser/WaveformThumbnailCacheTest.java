package com.benesquivelmusic.daw.core.browser;

import com.benesquivelmusic.daw.sdk.visualization.WaveformData;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WaveformThumbnailCacheTest {

    @Test
    void shouldStartEmpty() {
        WaveformThumbnailCache cache = new WaveformThumbnailCache();
        assertThat(cache.size()).isZero();
    }

    @Test
    void shouldStoreAndRetrieveEntry() {
        WaveformThumbnailCache cache = new WaveformThumbnailCache();
        Path path = Path.of("/audio/kick.wav");
        WaveformData data = createDummyWaveformData(10);

        cache.put(path, data);

        assertThat(cache.contains(path)).isTrue();
        assertThat(cache.get(path)).isPresent();
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void shouldReturnEmptyForMissingEntry() {
        WaveformThumbnailCache cache = new WaveformThumbnailCache();
        assertThat(cache.get(Path.of("/nonexistent.wav"))).isEmpty();
    }

    @Test
    void shouldContainReturnFalseForMissingEntry() {
        WaveformThumbnailCache cache = new WaveformThumbnailCache();
        assertThat(cache.contains(Path.of("/nonexistent.wav"))).isFalse();
    }

    @Test
    void shouldEvictOldestWhenCapacityExceeded() {
        WaveformThumbnailCache cache = new WaveformThumbnailCache(2);
        Path path1 = Path.of("/audio/a.wav");
        Path path2 = Path.of("/audio/b.wav");
        Path path3 = Path.of("/audio/c.wav");
        WaveformData data = createDummyWaveformData(10);

        cache.put(path1, data);
        cache.put(path2, data);
        cache.put(path3, data);

        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.contains(path1)).isFalse();
        assertThat(cache.contains(path2)).isTrue();
        assertThat(cache.contains(path3)).isTrue();
    }

    @Test
    void shouldClearAllEntries() {
        WaveformThumbnailCache cache = new WaveformThumbnailCache();
        cache.put(Path.of("/a.wav"), createDummyWaveformData(10));
        cache.put(Path.of("/b.wav"), createDummyWaveformData(10));

        cache.clear();

        assertThat(cache.size()).isZero();
    }

    @Test
    void shouldReportMaxEntries() {
        WaveformThumbnailCache cache = new WaveformThumbnailCache(128);
        assertThat(cache.maxEntries()).isEqualTo(128);
    }

    @Test
    void shouldUseDefaultMaxEntries() {
        WaveformThumbnailCache cache = new WaveformThumbnailCache();
        assertThat(cache.maxEntries()).isEqualTo(256);
    }

    @Test
    void shouldRejectZeroMaxEntries() {
        assertThatThrownBy(() -> new WaveformThumbnailCache(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeMaxEntries() {
        assertThatThrownBy(() -> new WaveformThumbnailCache(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullPathOnGet() {
        WaveformThumbnailCache cache = new WaveformThumbnailCache();
        assertThatThrownBy(() -> cache.get(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullPathOnPut() {
        WaveformThumbnailCache cache = new WaveformThumbnailCache();
        assertThatThrownBy(() -> cache.put(null, createDummyWaveformData(10)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullDataOnPut() {
        WaveformThumbnailCache cache = new WaveformThumbnailCache();
        assertThatThrownBy(() -> cache.put(Path.of("/a.wav"), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullPathOnContains() {
        WaveformThumbnailCache cache = new WaveformThumbnailCache();
        assertThatThrownBy(() -> cache.contains(null))
                .isInstanceOf(NullPointerException.class);
    }

    private static WaveformData createDummyWaveformData(int columns) {
        float[] min = new float[columns];
        float[] max = new float[columns];
        float[] rms = new float[columns];
        return new WaveformData(min, max, rms, columns);
    }
}
