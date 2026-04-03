package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.sdk.export.AudioExportConfig;
import com.benesquivelmusic.daw.sdk.export.AudioExportFormat;
import com.benesquivelmusic.daw.sdk.export.AudioExporter;
import com.benesquivelmusic.daw.sdk.export.ExportProgressListener;
import com.benesquivelmusic.daw.sdk.export.ExportRange;
import com.benesquivelmusic.daw.sdk.export.ExportResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Default implementation of {@link AudioExporter} supporting WAV and FLAC export
 * with automatic sample rate conversion and dithering.
 *
 * <p>The export pipeline for each configuration:</p>
 * <ol>
 *   <li>Time range extraction (if not exporting the full project)</li>
 *   <li>Sample rate conversion (if source ≠ target) via windowed sinc interpolation</li>
 *   <li>Bit-depth reduction with optional dithering (TPDF or noise-shaped)</li>
 *   <li>Format-specific encoding and file writing</li>
 * </ol>
 *
 * <p>Supports WAV and FLAC output natively. OGG Vorbis, MP3, and AAC
 * formats are encoded via FFM API (JEP 454) bindings to native codec
 * libraries (libvorbisenc, libmp3lame, libfdk-aac). If a native library
 * is not installed, the export returns a failure result with an
 * installation hint.</p>
 */
public final class DefaultAudioExporter implements AudioExporter {

    @Override
    public ExportResult export(float[][] audioData, int sourceSampleRate,
                               Path outputDir, String baseName,
                               AudioExportConfig config) throws IOException {
        return export(audioData, sourceSampleRate, outputDir, baseName, config,
                ExportProgressListener.NONE);
    }

    @Override
    public ExportResult export(float[][] audioData, int sourceSampleRate,
                               Path outputDir, String baseName,
                               AudioExportConfig config,
                               ExportProgressListener listener) throws IOException {
        Objects.requireNonNull(audioData, "audioData must not be null");
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(baseName, "baseName must not be null");
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(listener, "listener must not be null");

        if (audioData.length == 0) {
            throw new IllegalArgumentException("audioData must have at least one channel");
        }
        if (baseName.isEmpty()) {
            throw new IllegalArgumentException("baseName must not be empty");
        }

        long startTime = System.currentTimeMillis();

        try {
            Files.createDirectories(outputDir);

            listener.onProgress(0.1, "Sample rate conversion");

            // Step 1: Sample rate conversion
            float[][] converted = convertSampleRate(audioData, sourceSampleRate,
                    config.sampleRate());

            listener.onProgress(0.5, "Encoding " + config.format().fileExtension().toUpperCase());

            // Step 2: Format-specific export (dithering is applied inside the exporter)
            Path outputPath = outputDir.resolve(baseName + "." + config.format().fileExtension());
            // Prevent path traversal: ensure the resolved path stays within outputDir
            if (!outputPath.normalize().startsWith(outputDir.toAbsolutePath().normalize())) {
                throw new IllegalArgumentException(
                        "baseName must not escape the output directory: " + baseName);
            }

            switch (config.format()) {
                case WAV -> WavExporter.write(converted, config.sampleRate(),
                        config.bitDepth(), config.ditherType(), config.metadata(), outputPath);
                case FLAC -> FlacExporter.write(converted, config.sampleRate(),
                        config.bitDepth(), config.ditherType(), outputPath);
                case OGG -> OggVorbisExporter.write(converted, config.sampleRate(),
                        config.bitDepth(), config.ditherType(), config.metadata(),
                        config.quality(), outputPath);
                case MP3 -> Mp3Exporter.write(converted, config.sampleRate(),
                        config.bitDepth(), config.ditherType(), config.metadata(),
                        config.quality(), outputPath);
                case AAC -> AacExporter.write(converted, config.sampleRate(),
                        config.bitDepth(), config.ditherType(), config.metadata(),
                        config.quality(), outputPath);
            }

            listener.onProgress(1.0, "Complete");

            long elapsed = System.currentTimeMillis() - startTime;
            return new ExportResult(config, outputPath, true,
                    "Export completed successfully", elapsed);

        } catch (UnsupportedOperationException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            return new ExportResult(config,
                    outputDir.resolve(baseName + "." + config.format().fileExtension()),
                    false, e.getMessage(), elapsed);
        }
    }

    @Override
    public ExportResult export(float[][] audioData, int sourceSampleRate,
                               Path outputDir, String baseName,
                               AudioExportConfig config,
                               ExportRange range,
                               ExportProgressListener listener) throws IOException {
        Objects.requireNonNull(range, "range must not be null");
        float[][] rangeData = range.extractRange(audioData, sourceSampleRate);
        return export(rangeData, sourceSampleRate, outputDir, baseName, config, listener);
    }

    @Override
    public List<ExportResult> exportBatch(float[][] audioData, int sourceSampleRate,
                                          Path outputDir, String baseName,
                                          List<AudioExportConfig> configs) throws IOException {
        Objects.requireNonNull(configs, "configs must not be null");

        ArrayList<ExportResult> results = new ArrayList<ExportResult>(configs.size());
        for (AudioExportConfig config : configs) {
            results.add(export(audioData, sourceSampleRate, outputDir, baseName, config));
        }
        return List.copyOf(results);
    }

    /**
     * Converts all channels from the source sample rate to the target sample rate.
     * Returns the original data (by reference) if no conversion is needed.
     */
    private static float[][] convertSampleRate(float[][] audioData,
                                               int sourceSampleRate,
                                               int targetSampleRate) {
        if (sourceSampleRate == targetSampleRate) {
            return audioData;
        }

        float[][] result = new float[audioData.length][];
        for (int ch = 0; ch < audioData.length; ch++) {
            result[ch] = SampleRateConverter.convert(audioData[ch], sourceSampleRate,
                    targetSampleRate);
        }
        return result;
    }
}
