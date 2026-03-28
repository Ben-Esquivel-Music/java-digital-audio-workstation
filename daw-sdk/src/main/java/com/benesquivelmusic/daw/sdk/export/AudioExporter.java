package com.benesquivelmusic.daw.sdk.export;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Interface for exporting audio data to files in various formats.
 *
 * <p>Implementations handle sample rate conversion, bit-depth reduction
 * with dithering, format encoding, and metadata embedding. The
 * {@link #exportBatch(float[][], int, Path, String, List)} method
 * supports rendering to multiple format targets in a single operation.</p>
 */
public interface AudioExporter {

    /**
     * Exports audio data to a single file.
     *
     * @param audioData       the audio data as {@code [channel][sample]} with
     *                        values in the range {@code [-1.0f, 1.0f]}
     * @param sourceSampleRate the sample rate of the input audio data in Hz
     * @param outputDir       the directory to write the output file to
     * @param baseName        the base filename (without extension)
     * @param config          the export configuration
     * @return the result of the export operation
     * @throws IOException if an I/O error occurs while writing
     */
    ExportResult export(float[][] audioData, int sourceSampleRate,
                        Path outputDir, String baseName,
                        AudioExportConfig config) throws IOException;

    /**
     * Exports audio data to multiple files in a single batch operation.
     *
     * <p>Each configuration in the list produces a separate output file.
     * All outputs are derived from the same source audio data.</p>
     *
     * @param audioData        the audio data as {@code [channel][sample]}
     * @param sourceSampleRate the sample rate of the input audio data in Hz
     * @param outputDir        the directory to write the output files to
     * @param baseName         the base filename (without extension)
     * @param configs          the list of export configurations
     * @return a list of results, one per configuration
     * @throws IOException if an I/O error occurs while writing
     */
    List<ExportResult> exportBatch(float[][] audioData, int sourceSampleRate,
                                   Path outputDir, String baseName,
                                   List<AudioExportConfig> configs) throws IOException;

    /**
     * Exports audio data to a single file with progress reporting.
     *
     * <p>The default implementation delegates to
     * {@link #export(float[][], int, Path, String, AudioExportConfig)}
     * and ignores the progress listener.</p>
     *
     * @param audioData        the audio data as {@code [channel][sample]}
     * @param sourceSampleRate the sample rate of the input audio data in Hz
     * @param outputDir        the directory to write the output file to
     * @param baseName         the base filename (without extension)
     * @param config           the export configuration
     * @param listener         receives progress updates during export
     * @return the result of the export operation
     * @throws IOException if an I/O error occurs while writing
     */
    default ExportResult export(float[][] audioData, int sourceSampleRate,
                                Path outputDir, String baseName,
                                AudioExportConfig config,
                                ExportProgressListener listener) throws IOException {
        return export(audioData, sourceSampleRate, outputDir, baseName, config);
    }

    /**
     * Exports a time range of audio data to a single file with progress reporting.
     *
     * <p>The default implementation extracts the range from the full audio data
     * and delegates to
     * {@link #export(float[][], int, Path, String, AudioExportConfig, ExportProgressListener)}.</p>
     *
     * @param audioData        the full audio data as {@code [channel][sample]}
     * @param sourceSampleRate the sample rate of the input audio data in Hz
     * @param outputDir        the directory to write the output file to
     * @param baseName         the base filename (without extension)
     * @param config           the export configuration
     * @param range            the time range to export
     * @param listener         receives progress updates during export
     * @return the result of the export operation
     * @throws IOException if an I/O error occurs while writing
     */
    default ExportResult export(float[][] audioData, int sourceSampleRate,
                                Path outputDir, String baseName,
                                AudioExportConfig config,
                                ExportRange range,
                                ExportProgressListener listener) throws IOException {
        float[][] rangeData = range.extractRange(audioData, sourceSampleRate);
        return export(rangeData, sourceSampleRate, outputDir, baseName, config, listener);
    }
}
