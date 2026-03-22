package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.sdk.export.AudioExportConfig;
import com.benesquivelmusic.daw.sdk.export.AudioExportFormat;
import com.benesquivelmusic.daw.sdk.export.AudioExporter;
import com.benesquivelmusic.daw.sdk.export.ExportResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Default implementation of {@link AudioExporter} supporting WAV export
 * with automatic sample rate conversion and dithering.
 *
 * <p>The export pipeline for each configuration:</p>
 * <ol>
 *   <li>Sample rate conversion (if source ≠ target) via windowed sinc interpolation</li>
 *   <li>Bit-depth reduction with optional dithering (TPDF or noise-shaped)</li>
 *   <li>Format-specific encoding and file writing</li>
 * </ol>
 *
 * <p>Currently supports WAV output natively. FLAC, OGG, MP3, and AAC formats
 * are declared in the SDK for future implementation (e.g., via FFM API
 * integration with native codecs) and will throw {@link UnsupportedOperationException}
 * until implemented.</p>
 */
public final class DefaultAudioExporter implements AudioExporter {

    @Override
    public ExportResult export(float[][] audioData, int sourceSampleRate,
                               Path outputDir, String baseName,
                               AudioExportConfig config) throws IOException {
        Objects.requireNonNull(audioData, "audioData must not be null");
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(baseName, "baseName must not be null");
        Objects.requireNonNull(config, "config must not be null");

        if (audioData.length == 0) {
            throw new IllegalArgumentException("audioData must have at least one channel");
        }

        long startTime = System.currentTimeMillis();

        try {
            Files.createDirectories(outputDir);

            // Step 1: Sample rate conversion
            float[][] converted = convertSampleRate(audioData, sourceSampleRate,
                    config.sampleRate());

            // Step 2: Format-specific export (dithering is applied inside the exporter)
            Path outputPath = outputDir.resolve(baseName + "." + config.format().fileExtension());

            switch (config.format()) {
                case WAV -> WavExporter.write(converted, config.sampleRate(),
                        config.bitDepth(), config.ditherType(), config.metadata(), outputPath);
                case FLAC, OGG, MP3, AAC ->
                        throw new UnsupportedOperationException(
                                config.format() + " export is not yet implemented. "
                                        + "Future versions will use the FFM API (JEP 454) "
                                        + "for native codec integration.");
            }

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
    public List<ExportResult> exportBatch(float[][] audioData, int sourceSampleRate,
                                          Path outputDir, String baseName,
                                          List<AudioExportConfig> configs) throws IOException {
        Objects.requireNonNull(configs, "configs must not be null");

        var results = new ArrayList<ExportResult>(configs.size());
        for (var config : configs) {
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
