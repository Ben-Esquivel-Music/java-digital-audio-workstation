package com.benesquivelmusic.daw.core.browser;

import com.benesquivelmusic.daw.core.analysis.WaveformGenerator;
import com.benesquivelmusic.daw.core.audioimport.WavFileReader;
import com.benesquivelmusic.daw.sdk.visualization.WaveformData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service for loading audio file metadata and generating waveform thumbnails
 * for the browser panel sample preview feature.
 *
 * <p>Provides methods to read metadata from audio files (currently WAV) and
 * generate compact waveform overviews suitable for thumbnail display. Results
 * are cached using a {@link WaveformThumbnailCache} to avoid repeated I/O
 * for previously browsed directories.</p>
 */
public final class SamplePreviewService {

    private static final Logger LOG = Logger.getLogger(SamplePreviewService.class.getName());

    /** Default number of display columns for thumbnails. */
    static final int DEFAULT_THUMBNAIL_COLUMNS = 64;

    private final WaveformThumbnailCache thumbnailCache;

    /**
     * Creates a new preview service with the given thumbnail cache.
     *
     * @param thumbnailCache the cache for waveform thumbnails
     */
    public SamplePreviewService(WaveformThumbnailCache thumbnailCache) {
        this.thumbnailCache = Objects.requireNonNull(thumbnailCache, "thumbnailCache must not be null");
    }

    /**
     * Creates a new preview service with a default thumbnail cache.
     */
    public SamplePreviewService() {
        this(new WaveformThumbnailCache());
    }

    /**
     * Loads metadata for the given audio file.
     *
     * <p>Currently supports WAV files only. Returns empty for unsupported
     * formats or if the file cannot be read.</p>
     *
     * @param filePath the path to the audio file
     * @return the sample metadata, or empty if unavailable
     */
    public Optional<SampleMetadata> loadMetadata(Path filePath) {
        Objects.requireNonNull(filePath, "filePath must not be null");
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            return Optional.empty();
        }

        try {
            long fileSize = Files.size(filePath);
            WavFileReader.WavReadResult result = WavFileReader.read(filePath);
            SampleMetadata metadata = new SampleMetadata(
                    filePath,
                    result.durationSeconds(),
                    result.sampleRate(),
                    result.channels(),
                    result.bitDepth(),
                    fileSize);
            return Optional.of(metadata);
        } catch (IOException | IllegalArgumentException e) {
            LOG.log(Level.FINE, "Failed to load metadata for: " + filePath, e);
            return Optional.empty();
        }
    }

    /**
     * Generates or retrieves a cached waveform thumbnail for the given audio file.
     *
     * <p>If the thumbnail is already cached, it is returned immediately.
     * Otherwise, the file is read, a waveform overview is generated with
     * {@value #DEFAULT_THUMBNAIL_COLUMNS} columns, and the result is cached.</p>
     *
     * @param filePath the path to the audio file
     * @return the waveform data, or empty if unavailable
     */
    public Optional<WaveformData> loadThumbnail(Path filePath) {
        return loadThumbnail(filePath, DEFAULT_THUMBNAIL_COLUMNS);
    }

    /**
     * Generates or retrieves a cached waveform thumbnail with a specific column count.
     *
     * @param filePath the path to the audio file
     * @param columns  the number of display columns for the thumbnail
     * @return the waveform data, or empty if unavailable
     */
    public Optional<WaveformData> loadThumbnail(Path filePath, int columns) {
        Objects.requireNonNull(filePath, "filePath must not be null");
        if (columns <= 0) {
            throw new IllegalArgumentException("columns must be positive: " + columns);
        }

        Optional<WaveformData> cached = thumbnailCache.get(filePath);
        if (cached.isPresent()) {
            return cached;
        }

        try {
            WavFileReader.WavReadResult result = WavFileReader.read(filePath);
            float[] mono = mixToMono(result.audioData(), result.channels());
            WaveformData waveformData = WaveformGenerator.generate(mono, columns);
            thumbnailCache.put(filePath, waveformData);
            return Optional.of(waveformData);
        } catch (IOException | IllegalArgumentException e) {
            LOG.log(Level.FINE, "Failed to generate thumbnail for: " + filePath, e);
            return Optional.empty();
        }
    }

    /**
     * Returns the thumbnail cache used by this service.
     *
     * @return the waveform thumbnail cache
     */
    public WaveformThumbnailCache getThumbnailCache() {
        return thumbnailCache;
    }

    /**
     * Mixes multi-channel audio data down to mono by averaging all channels.
     */
    private static float[] mixToMono(float[][] audioData, int channels) {
        if (channels == 1) {
            return audioData[0];
        }
        int numFrames = audioData[0].length;
        float[] mono = new float[numFrames];
        for (int i = 0; i < numFrames; i++) {
            float sum = 0.0f;
            for (int ch = 0; ch < channels; ch++) {
                sum += audioData[ch][i];
            }
            mono[i] = sum / channels;
        }
        return mono;
    }
}
