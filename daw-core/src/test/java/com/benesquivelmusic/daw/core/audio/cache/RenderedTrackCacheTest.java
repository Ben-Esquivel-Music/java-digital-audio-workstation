package com.benesquivelmusic.daw.core.audio.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RenderedTrackCacheTest {

    private static final String PROJECT = "550e8400-e29b-41d4-a716-446655440000";

    @Test
    void storeThenLoadReturnsIdenticalAudio(@TempDir Path tmp) throws IOException {
        RenderedTrackCache cache = new RenderedTrackCache(tmp, RenderCacheConfig.defaults());
        RenderKey key = key("aa");
        float[][] audio = {
                {0.0f, 0.5f, -0.5f, 1.0f},
                {0.1f, 0.2f, 0.3f, 0.4f}
        };

        cache.store(PROJECT, key, audio);

        Optional<RenderedTrackCache.RenderedAudio> loaded = cache.load(PROJECT, key);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().audio()).isDeepEqualTo(audio);
        assertThat(loaded.get().sampleRate()).isEqualTo(48_000);
        assertThat(loaded.get().bitDepth()).isEqualTo(32);
    }

    @Test
    void identicalInputsProduceCacheHit(@TempDir Path tmp) throws IOException {
        RenderedTrackCache cache = new RenderedTrackCache(tmp, RenderCacheConfig.defaults());
        RenderKey key = key("bb");
        float[][] audio = {{1.0f, 2.0f, 3.0f}};

        cache.store(PROJECT, key, audio);

        // Second freeze with the same key — same hash → hit.
        assertThat(cache.contains(PROJECT, key)).isTrue();
        assertThat(cache.load(PROJECT, key)).isPresent();
    }

    @Test
    void changingDspParameterProducesMiss(@TempDir Path tmp) throws IOException {
        RenderedTrackCache cache = new RenderedTrackCache(tmp, RenderCacheConfig.defaults());

        // Two different DSP states → two different hashes.
        String hashA = new TrackDspHasher().addParameter("gain", 0.5).digestHex();
        String hashB = new TrackDspHasher().addParameter("gain", 0.6).digestHex();
        assertThat(hashA).isNotEqualTo(hashB);

        RenderKey keyA = new RenderKey(hashA, 48_000, 32);
        RenderKey keyB = new RenderKey(hashB, 48_000, 32);
        cache.store(PROJECT, keyA, new float[][]{{1.0f}});

        assertThat(cache.load(PROJECT, keyA)).isPresent();
        assertThat(cache.load(PROJECT, keyB)).isEmpty();
    }

    @Test
    void cacheSurvivesAcrossNewInstances(@TempDir Path tmp) throws IOException {
        RenderKey key = key("cc");
        float[][] audio = {{0.25f, -0.25f}, {0.5f, -0.5f}};

        new RenderedTrackCache(tmp, RenderCacheConfig.defaults())
                .store(PROJECT, key, audio);

        // Simulate a process restart by constructing a fresh cache
        // pointing at the same root.
        RenderedTrackCache reopened =
                new RenderedTrackCache(tmp, RenderCacheConfig.defaults());
        Optional<RenderedTrackCache.RenderedAudio> loaded = reopened.load(PROJECT, key);

        assertThat(loaded).isPresent();
        assertThat(loaded.get().audio()).isDeepEqualTo(audio);
    }

    @Test
    void lruEvictionTrimsOldestWhenQuotaExceeded(@TempDir Path tmp) throws IOException {
        // Each entry is 32-byte header + 1 channel * 4 frames * 4 bytes = 48 bytes.
        // Set quota to 100 bytes — only two of three entries fit.
        RenderedTrackCache cache = new RenderedTrackCache(tmp, new RenderCacheConfig(100));

        RenderKey k1 = key("aa");
        RenderKey k2 = key("bb");
        RenderKey k3 = key("cc");
        float[][] audio = {{0.0f, 0.0f, 0.0f, 0.0f}};

        cache.store(PROJECT, k1, audio);
        // Make k1 the oldest by stamping its mtime in the past.
        Path p1 = tmp.resolve(PROJECT).resolve(k1.hashPrefix()).resolve(k1.toFileName());
        Files.setLastModifiedTime(p1, FileTime.from(Instant.now().minusSeconds(3600)));

        cache.store(PROJECT, k2, audio);
        Path p2 = tmp.resolve(PROJECT).resolve(k2.hashPrefix()).resolve(k2.toFileName());
        Files.setLastModifiedTime(p2, FileTime.from(Instant.now().minusSeconds(60)));

        cache.store(PROJECT, k3, audio);

        // Oldest (k1) should be evicted.
        assertThat(cache.contains(PROJECT, k1)).isFalse();
        // Newer entries should survive.
        assertThat(cache.contains(PROJECT, k3)).isTrue();
    }

    @Test
    void loadIncrementsHitsAndStatsTrackPerProjectSize(@TempDir Path tmp) throws IOException {
        RenderedTrackCache cache = new RenderedTrackCache(tmp, RenderCacheConfig.defaults());
        RenderKey key = key("dd");
        cache.store(PROJECT, key, new float[][]{{0.5f, 0.5f}});

        cache.load(PROJECT, key);
        cache.load(PROJECT, new RenderKey("ee".repeat(32), 48_000, 32));

        RenderCacheStats stats = cache.stats();
        assertThat(stats.sessionHits()).isEqualTo(1);
        assertThat(stats.sessionMisses()).isEqualTo(1);
        assertThat(stats.hitRate()).isEqualTo(0.5);
        assertThat(stats.perProjectSizeBytes()).containsKey(PROJECT);
        assertThat(stats.perProjectSizeBytes().get(PROJECT)).isPositive();
        assertThat(stats.totalSizeBytes()).isEqualTo(stats.perProjectSizeBytes().get(PROJECT));
    }

    @Test
    void clearProjectRemovesAllEntries(@TempDir Path tmp) throws IOException {
        RenderedTrackCache cache = new RenderedTrackCache(tmp, RenderCacheConfig.defaults());
        cache.store(PROJECT, key("aa"), new float[][]{{0.0f}});
        cache.store(PROJECT, key("bb"), new float[][]{{0.0f}});

        cache.clearProject(PROJECT);

        assertThat(cache.stats().perProjectSizeBytes()).doesNotContainKey(PROJECT);
    }

    @Test
    void rejectsPathTraversalProjectUuid(@TempDir Path tmp) {
        RenderedTrackCache cache = new RenderedTrackCache(tmp, RenderCacheConfig.defaults());
        assertThatThrownBy(() -> cache.contains("../etc", key("aa")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void differentProjectsAreIsolated(@TempDir Path tmp) throws IOException {
        RenderedTrackCache cache = new RenderedTrackCache(tmp, RenderCacheConfig.defaults());
        RenderKey shared = key("ff");

        String otherProject = UUID.randomUUID().toString();
        cache.store(PROJECT, shared, new float[][]{{1.0f}});

        assertThat(cache.contains(otherProject, shared)).isFalse();
    }

    private static RenderKey key(String shardPrefix) {
        // Build a 64-char hash that begins with the chosen 2-char shard prefix.
        String hash = shardPrefix + "0".repeat(RenderKey.HASH_LENGTH - shardPrefix.length());
        return new RenderKey(hash, 48_000, 32);
    }
}
