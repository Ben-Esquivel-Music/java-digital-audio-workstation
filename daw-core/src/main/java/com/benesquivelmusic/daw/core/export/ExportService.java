package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.core.analysis.LoudnessMeter;
import com.benesquivelmusic.daw.sdk.export.*;
import com.benesquivelmusic.daw.sdk.visualization.ExportValidationResult;
import com.benesquivelmusic.daw.sdk.visualization.LoudnessTarget;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * High-level service orchestrating the complete audio export workflow.
 *
 * <p>The export pipeline:</p>
 * <ol>
 *   <li>Extract the requested time range from the source audio</li>
 *   <li>Apply loudness normalization to the target LUFS (if configured)</li>
 *   <li>Delegate to {@link DefaultAudioExporter} for sample rate conversion,
 *       dithering, and format encoding</li>
 *   <li>Validate the result against the loudness target and produce a report</li>
 * </ol>
 *
 * <p>This service ties together the SDK types ({@link AudioExportConfig},
 * {@link ExportPreset}, {@link ExportRange}, {@link LoudnessTarget}) and the
 * core export/analysis infrastructure to provide a single entry point for
 * the export UI.</p>
 */
public final class ExportService {

    private final DefaultAudioExporter exporter;

    /**
     * Creates an export service backed by the default audio exporter.
     */
    public ExportService() {
        this.exporter = new DefaultAudioExporter();
    }

    /**
     * Exports audio with the full pipeline: range extraction, loudness normalization,
     * format export, and validation.
     *
     * @param audioData        the full audio data as {@code [channel][sample]}
     * @param sourceSampleRate the sample rate of the input audio data in Hz
     * @param outputDir        the directory to write the output file to
     * @param baseName         the base filename (without extension)
     * @param config           the export configuration
     * @param range            the time range to export (use {@link ExportRange#FULL} for entire project)
     * @param loudnessTarget   the loudness target for normalization and validation (may be {@code null}
     *                         to skip normalization)
     * @param listener         receives progress updates during export
     * @return the export result including loudness validation information
     * @throws IOException if an I/O error occurs while writing
     */
    public ExportServiceResult exportWithValidation(
            float[][] audioData, int sourceSampleRate,
            Path outputDir, String baseName,
            AudioExportConfig config,
            ExportRange range,
            LoudnessTarget loudnessTarget,
            ExportProgressListener listener) throws IOException {

        Objects.requireNonNull(audioData, "audioData must not be null");
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(baseName, "baseName must not be null");
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(range, "range must not be null");
        Objects.requireNonNull(listener, "listener must not be null");

        listener.onProgress(0.0, "Preparing export");

        // Step 1: Extract time range
        listener.onProgress(0.05, "Extracting time range");
        float[][] rangeData = range.extractRange(audioData, sourceSampleRate);

        // Step 2: Loudness normalization (if target is set)
        double gainAppliedDb = 0.0;
        if (loudnessTarget != null && rangeData.length > 0 && rangeData[0].length > 0) {
            listener.onProgress(0.15, "Measuring loudness");
            // Work on a copy to avoid modifying the original data
            float[][] normalizedData = copyAudio(rangeData);
            listener.onProgress(0.25, "Normalizing to " + loudnessTarget.displayName()
                    + " (" + loudnessTarget.targetIntegratedLufs() + " LUFS)");
            gainAppliedDb = LoudnessNormalizer.normalize(normalizedData, sourceSampleRate,
                    loudnessTarget);
            rangeData = normalizedData;
        }

        // Step 3: Export (SRC + dithering + encoding)
        listener.onProgress(0.35, "Exporting " + config.format().fileExtension().toUpperCase());
        ExportProgressListener wrappedListener = (progress, stage) ->
                listener.onProgress(0.35 + progress * 0.5, stage);
        ExportResult exportResult = exporter.export(rangeData, sourceSampleRate,
                outputDir, baseName, config, wrappedListener);

        // Step 4: Validate loudness (if target is set and export succeeded)
        ExportValidationResult validationResult = null;
        if (loudnessTarget != null && exportResult.success()
                && rangeData.length > 0 && rangeData[0].length > 0) {
            listener.onProgress(0.90, "Validating loudness");
            LoudnessMeter meter = new LoudnessMeter(sourceSampleRate, 4096);
            float[] left = rangeData[0];
            float[] right = (rangeData.length >= 2) ? rangeData[1] : rangeData[0];
            int numSamples = left.length;
            int offset = 0;
            while (offset < numSamples) {
                int blockSize = Math.min(4096, numSamples - offset);
                float[] leftBlock = new float[blockSize];
                float[] rightBlock = new float[blockSize];
                System.arraycopy(left, offset, leftBlock, 0, blockSize);
                System.arraycopy(right, offset, rightBlock, 0, blockSize);
                meter.process(leftBlock, rightBlock, blockSize);
                offset += blockSize;
            }
            validationResult = meter.validateForExport(loudnessTarget);
        }

        listener.onProgress(1.0, "Complete");

        return new ExportServiceResult(exportResult, validationResult, gainAppliedDb);
    }

    /**
     * Convenience method using a preset and default settings.
     *
     * @param audioData        the full audio data
     * @param sourceSampleRate the sample rate of the input audio data in Hz
     * @param outputDir        the directory to write the output file to
     * @param baseName         the base filename (without extension)
     * @param preset           the export preset
     * @param listener         receives progress updates during export
     * @return the export result
     * @throws IOException if an I/O error occurs while writing
     */
    public ExportServiceResult exportWithPreset(
            float[][] audioData, int sourceSampleRate,
            Path outputDir, String baseName,
            ExportPreset preset,
            ExportProgressListener listener) throws IOException {
        Objects.requireNonNull(preset, "preset must not be null");
        return exportWithValidation(audioData, sourceSampleRate, outputDir, baseName,
                preset.config(), ExportRange.FULL, null, listener);
    }

    /**
     * Returns the list of built-in export presets.
     *
     * @return an unmodifiable list of presets
     */
    public static List<ExportPreset> getBuiltInPresets() {
        return List.of(
                ExportPreset.CD,
                ExportPreset.STREAMING,
                ExportPreset.HI_RES,
                ExportPreset.VINYL,
                ExportPreset.SPOTIFY,
                ExportPreset.APPLE_MUSIC
        );
    }

    /**
     * Returns the list of supported loudness targets.
     *
     * @return an unmodifiable list of loudness targets
     */
    public static List<LoudnessTarget> getSupportedLoudnessTargets() {
        return List.of(LoudnessTarget.values());
    }

    /**
     * Returns the available sample rates.
     *
     * @return an unmodifiable list of sample rates in Hz
     */
    public static List<Integer> getSupportedSampleRates() {
        return List.of(44_100, 48_000, 88_200, 96_000);
    }

    /**
     * Returns the available bit depths.
     *
     * @return an unmodifiable list of bit depths
     */
    public static List<Integer> getSupportedBitDepths() {
        return List.of(16, 24, 32);
    }

    private static float[][] copyAudio(float[][] source) {
        float[][] copy = new float[source.length][];
        for (int ch = 0; ch < source.length; ch++) {
            copy[ch] = source[ch].clone();
        }
        return copy;
    }

    /**
     * Result of a full export pipeline including validation.
     *
     * @param exportResult     the result of the format export operation
     * @param validationResult the loudness validation result (may be {@code null}
     *                         if no loudness target was specified)
     * @param gainAppliedDb    the loudness normalization gain applied in dB
     *                         (0.0 if no normalization was performed)
     */
    public record ExportServiceResult(
            ExportResult exportResult,
            ExportValidationResult validationResult,
            double gainAppliedDb
    ) {
        public ExportServiceResult {
            Objects.requireNonNull(exportResult, "exportResult must not be null");
        }
    }
}
