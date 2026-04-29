package com.benesquivelmusic.daw.core.audio.cache;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Persistent cache of rendered (frozen) track audio.
 *
 * <p>Stores rendered track buffers on disk under
 * {@code <root>/<projectUuid>/<hashPrefix>/<renderKey>.pcm} so they
 * survive across sessions: reopening a project with a frozen track
 * whose DSP state hash matches a cached render skips the re-render
 * entirely. The default {@link #defaultRoot() root} is
 * {@code ~/.daw/render-cache}.</p>
 *
 * <p>Each {@code .pcm} file begins with a fixed 32-byte header
 * (magic, version, channel count, sample rate, bit depth, frame
 * count) followed by interleaved 32-bit little-endian floats. The
 * raw layout enables random-access peek without parsing.</p>
 *
 * <p>Per-project byte quota with LRU eviction is enforced lazily on
 * {@link #store store}: after a write, if the per-project total
 * exceeds the {@link RenderCacheConfig#perProjectQuotaBytes() quota},
 * the oldest entries (smallest {@code lastModifiedTime}) are deleted
 * until the total fits. {@link #load load} touches the entry's
 * {@code lastModifiedTime} so accessed renders are kept fresh.</p>
 *
 * <p>This class is thread-safe for concurrent {@code load} / {@code
 * store} on different keys; concurrent writes to the same key are
 * resolved by the filesystem (last writer wins).</p>
 */
public final class RenderedTrackCache {

    /** Magic number written at byte 0 of every cache file: ASCII {@code "DPCM"}. */
    static final int MAGIC = 0x4D435044; // little-endian "DPCM"

    /** Cache file format version; bump if the header layout changes. */
    static final int FORMAT_VERSION = 1;

    /** Header size in bytes. */
    static final int HEADER_BYTES = 32;

    private final Path root;
    private final RenderCacheConfig config;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    /**
     * Creates a cache rooted at {@code root} with the given config.
     * The directory is created on demand by {@link #store store}.
     */
    public RenderedTrackCache(Path root, RenderCacheConfig config) {
        this.root = Objects.requireNonNull(root, "root must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    /**
     * Creates a cache at the default user location
     * ({@code ~/.daw/render-cache}) with default config.
     */
    public static RenderedTrackCache withDefaults() {
        return new RenderedTrackCache(defaultRoot(), RenderCacheConfig.defaults());
    }

    /** Returns the default cache root: {@code ~/.daw/render-cache}. */
    public static Path defaultRoot() {
        return Path.of(System.getProperty("user.home"), ".daw", "render-cache");
    }

    /** Returns the directory holding cache entries for {@code projectUuid}. */
    public Path projectDirectory(String projectUuid) {
        Objects.requireNonNull(projectUuid, "projectUuid must not be null");
        return root.resolve(sanitize(projectUuid));
    }

    private Path entryPath(String projectUuid, RenderKey key) {
        return projectDirectory(projectUuid)
                .resolve(key.hashPrefix())
                .resolve(key.toFileName());
    }

    /**
     * Returns {@code true} if a cache entry exists for the given
     * project UUID and render key.
     */
    public boolean contains(String projectUuid, RenderKey key) {
        Objects.requireNonNull(key, "key must not be null");
        return Files.isRegularFile(entryPath(projectUuid, key));
    }

    /**
     * Loads a previously stored render, or returns
     * {@link Optional#empty()} if there is no entry for the given
     * key. A successful load increments the hit counter and touches
     * the entry's {@code lastModifiedTime} so it stays fresh in the
     * LRU eviction order; a miss increments the miss counter.
     *
     * @throws IOException if the entry exists but is corrupt or
     *                     unreadable
     */
    public Optional<RenderedAudio> load(String projectUuid, RenderKey key) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        Path file = entryPath(projectUuid, key);
        if (!Files.isRegularFile(file)) {
            misses.incrementAndGet();
            return Optional.empty();
        }
        RenderedAudio audio = readFile(file, key);
        try {
            Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
        } catch (IOException ignored) {
            // Touching is best-effort; a failure does not invalidate
            // the audio we just successfully read.
        }
        hits.incrementAndGet();
        return Optional.of(audio);
    }

    /**
     * Stores rendered audio for the given key, replacing any
     * existing entry, then enforces the per-project quota by LRU
     * eviction.
     *
     * @param audio rendered audio as {@code [channel][frame]}; all
     *              channels must be the same length
     */
    public void store(String projectUuid, RenderKey key, float[][] audio) throws IOException {
        Objects.requireNonNull(audio, "audio must not be null");
        if (audio.length == 0) {
            throw new IllegalArgumentException("audio must have at least one channel");
        }
        int frames = audio[0].length;
        for (int c = 1; c < audio.length; c++) {
            if (audio[c].length != frames) {
                throw new IllegalArgumentException(
                        "all channels must have the same number of frames");
            }
        }
        Path file = entryPath(projectUuid, key);
        Files.createDirectories(file.getParent());
        writeFile(file, key, audio);
        enforceQuota(projectUuid);
    }

    /** Removes a single entry; returns {@code true} if a file was deleted. */
    public boolean remove(String projectUuid, RenderKey key) throws IOException {
        return Files.deleteIfExists(entryPath(projectUuid, key));
    }

    /** Recursively deletes the entire cache (every project). */
    public void clearAll() throws IOException {
        if (Files.isDirectory(root)) {
            deleteTree(root);
        }
    }

    /**
     * Recursively deletes the cache for one project; safe to call
     * if the directory does not exist.
     */
    public void clearProject(String projectUuid) throws IOException {
        Path dir = projectDirectory(projectUuid);
        if (Files.isDirectory(dir)) {
            deleteTree(dir);
        }
    }

    /** Returns a snapshot of cache statistics for display / logging. */
    public RenderCacheStats stats() throws IOException {
        Map<String, Long> perProject = new HashMap<>();
        long total = 0;
        if (Files.isDirectory(root)) {
            try (Stream<Path> projects = Files.list(root)) {
                for (Path p : (Iterable<Path>) projects::iterator) {
                    if (Files.isDirectory(p)) {
                        long size = directorySize(p);
                        perProject.put(p.getFileName().toString(), size);
                        total += size;
                    }
                }
            }
        }
        return new RenderCacheStats(total, perProject, hits.get(), misses.get());
    }

    /** Resets the in-memory hit / miss session counters. */
    public void resetSessionCounters() {
        hits.set(0);
        misses.set(0);
    }

    // ---- internals -----------------------------------------------------

    private static String sanitize(String projectUuid) {
        // Defence-in-depth: prevent path traversal if a caller hands
        // us a malformed UUID.
        if (projectUuid.isEmpty()
                || projectUuid.contains("/")
                || projectUuid.contains("\\")
                || projectUuid.contains("..")) {
            throw new IllegalArgumentException(
                    "projectUuid contains illegal characters: " + projectUuid);
        }
        return projectUuid;
    }

    private RenderedAudio readFile(Path file, RenderKey key) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            readFully(ch, header);
            header.flip();
            int magic = header.getInt();
            if (magic != MAGIC) {
                throw new IOException("Not a rendered-track cache file (bad magic): " + file);
            }
            int version = header.getInt();
            if (version != FORMAT_VERSION) {
                throw new IOException("Unsupported cache file version " + version + ": " + file);
            }
            int channels = header.getInt();
            int sampleRate = header.getInt();
            int bitDepth = header.getInt();
            long frames = header.getLong();
            // padding to 32 bytes — read but ignore
            header.getInt();

            if (channels <= 0 || frames < 0
                    || sampleRate != key.sessionSampleRate()
                    || bitDepth != key.bitDepth()) {
                throw new IOException("Cache header does not match key: " + file);
            }
            if (frames > Integer.MAX_VALUE) {
                throw new IOException("Frame count exceeds int range: " + frames);
            }
            int frameCount = (int) frames;
            float[][] audio = new float[channels][frameCount];
            if (frameCount > 0) {
                ByteBuffer payload = ByteBuffer
                        .allocate(channels * frameCount * Float.BYTES)
                        .order(ByteOrder.LITTLE_ENDIAN);
                readFully(ch, payload);
                payload.flip();
                for (int f = 0; f < frameCount; f++) {
                    for (int c = 0; c < channels; c++) {
                        audio[c][f] = payload.getFloat();
                    }
                }
            }
            return new RenderedAudio(audio, sampleRate, bitDepth);
        }
    }

    private void writeFile(Path file, RenderKey key, float[][] audio) throws IOException {
        int channels = audio.length;
        int frames = audio[0].length;
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(MAGIC);
            header.putInt(FORMAT_VERSION);
            header.putInt(channels);
            header.putInt(key.sessionSampleRate());
            header.putInt(key.bitDepth());
            header.putLong(frames);
            header.putInt(0); // reserved padding
            header.flip();
            writeFully(ch, header);

            if (frames > 0) {
                ByteBuffer payload = ByteBuffer
                        .allocate(channels * frames * Float.BYTES)
                        .order(ByteOrder.LITTLE_ENDIAN);
                for (int f = 0; f < frames; f++) {
                    for (int c = 0; c < channels; c++) {
                        payload.putFloat(audio[c][f]);
                    }
                }
                payload.flip();
                writeFully(ch, payload);
            }
        }
        try {
            Files.move(tmp, file,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void readFully(FileChannel ch, ByteBuffer dst) throws IOException {
        while (dst.hasRemaining()) {
            int n = ch.read(dst);
            if (n < 0) {
                throw new IOException("Unexpected end of file");
            }
        }
    }

    private static void writeFully(FileChannel ch, ByteBuffer src) throws IOException {
        while (src.hasRemaining()) {
            ch.write(src);
        }
    }

    private void enforceQuota(String projectUuid) throws IOException {
        Path dir = projectDirectory(projectUuid);
        if (!Files.isDirectory(dir)) {
            return;
        }
        long total = directorySize(dir);
        if (total <= config.perProjectQuotaBytes()) {
            return;
        }
        List<Path> entries = listEntries(dir);
        // Sort ascending by lastModifiedTime — oldest first.
        entries.sort(Comparator.comparing(p -> {
            try {
                return Files.getLastModifiedTime(p);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }));
        for (Path victim : entries) {
            if (total <= config.perProjectQuotaBytes()) {
                break;
            }
            long size;
            try {
                size = Files.size(victim);
            } catch (IOException e) {
                continue;
            }
            try {
                Files.deleteIfExists(victim);
                total -= size;
            } catch (IOException ignored) {
                // Best-effort eviction: skip stuck files.
            }
        }
    }

    private static List<Path> listEntries(Path projectDir) throws IOException {
        List<Path> result = new ArrayList<>();
        try (Stream<Path> shards = Files.list(projectDir)) {
            for (Path shard : (Iterable<Path>) shards::iterator) {
                if (Files.isDirectory(shard)) {
                    try (Stream<Path> files = Files.list(shard)) {
                        for (Path f : (Iterable<Path>) files::iterator) {
                            if (Files.isRegularFile(f)
                                    && f.getFileName().toString().endsWith(".pcm")) {
                                result.add(f);
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    private static long directorySize(Path dir) throws IOException {
        long[] total = {0};
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                try {
                    total[0] += Files.size(p);
                } catch (IOException ignored) {
                    // ignore — best-effort sizing
                }
            });
        }
        return total[0];
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            // Sort reverse so files are removed before their parents.
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** Small sanity check used by tests / diagnostics. */
    @SuppressWarnings("unused")
    private static String magicAscii() {
        byte[] b = new byte[4];
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(MAGIC);
        return new String(b, StandardCharsets.US_ASCII);
    }

    /**
     * Audio loaded from a cache entry plus the rate / bit-depth that
     * were embedded in the file header (always equal to the
     * {@link RenderKey} the entry was stored under).
     *
     * <p>{@code audio} is laid out as {@code [channel][frame]}.</p>
     */
    public record RenderedAudio(float[][] audio, int sampleRate, int bitDepth) {
        public RenderedAudio {
            Objects.requireNonNull(audio, "audio must not be null");
            // Defensive copy of the outer array so callers cannot
            // mutate the cache record after construction. Inner
            // float[] arrays are large and copying them on every
            // load would defeat the purpose of the cache.
            audio = Arrays.copyOf(audio, audio.length);
            if (sampleRate <= 0) {
                throw new IllegalArgumentException(
                        "sampleRate must be positive: " + sampleRate);
            }
            if (bitDepth <= 0) {
                throw new IllegalArgumentException(
                        "bitDepth must be positive: " + bitDepth);
            }
        }
    }
}
