package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.export.AtmosExportResult;
import com.benesquivelmusic.daw.core.export.AtmosExportWorkflow;
import com.benesquivelmusic.daw.core.spatial.objectbased.AtmosSessionConfig;
import com.benesquivelmusic.daw.core.spatial.objectbased.AudioObject;
import com.benesquivelmusic.daw.core.spatial.objectbased.BedChannel;
import com.benesquivelmusic.daw.sdk.export.AudioMetadata;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Controller that connects the {@link AtmosSessionConfigDialog} to the
 * {@link AtmosExportWorkflow} for performing ADM BWF exports.
 *
 * <p>This controller handles the lifecycle of an Atmos export:</p>
 * <ol>
 *   <li>Receives a validated {@link AtmosSessionConfig} from the dialog</li>
 *   <li>Collects audio buffers for each bed channel and audio object</li>
 *   <li>Delegates to {@link AtmosExportWorkflow} for validation and writing</li>
 *   <li>Reports the result back via a callback</li>
 * </ol>
 */
public final class AdmBwfExportController {

    /**
     * Callback interface for reporting export results.
     */
    @FunctionalInterface
    public interface ExportResultListener {

        /**
         * Called after the export completes (either successfully or with errors).
         *
         * @param result the export result
         */
        void onExportComplete(AtmosExportResult result);
    }

    /**
     * Provider interface for audio data. Implementations supply audio
     * buffers keyed by track ID.
     */
    @FunctionalInterface
    public interface AudioDataProvider {

        /**
         * Returns the audio buffer for the given track.
         *
         * @param trackId the track identifier
         * @return the audio samples, or an empty array if unavailable
         */
        float[] getAudioForTrack(String trackId);
    }

    private AudioDataProvider audioDataProvider;
    private ExportResultListener resultListener;
    private AudioMetadata metadata = AudioMetadata.EMPTY;
    private Path outputPath;

    /**
     * Sets the audio data provider.
     *
     * @param provider the audio data provider
     */
    public void setAudioDataProvider(AudioDataProvider provider) {
        this.audioDataProvider = Objects.requireNonNull(provider, "provider must not be null");
    }

    /**
     * Sets the export result listener.
     *
     * @param listener the result listener
     */
    public void setResultListener(ExportResultListener listener) {
        this.resultListener = listener;
    }

    /**
     * Sets the file-level metadata to embed in the exported file.
     *
     * @param metadata the audio metadata
     */
    public void setMetadata(AudioMetadata metadata) {
        this.metadata = Objects.requireNonNull(metadata, "metadata must not be null");
    }

    /**
     * Sets the output file path.
     *
     * @param outputPath the output path for the ADM BWF file
     */
    public void setOutputPath(Path outputPath) {
        this.outputPath = Objects.requireNonNull(outputPath, "outputPath must not be null");
    }

    /** Returns the current audio data provider. */
    public AudioDataProvider getAudioDataProvider() {
        return audioDataProvider;
    }

    /** Returns the current result listener. */
    public ExportResultListener getResultListener() {
        return resultListener;
    }

    /** Returns the current audio metadata. */
    public AudioMetadata getMetadata() {
        return metadata;
    }

    /** Returns the current output path. */
    public Path getOutputPath() {
        return outputPath;
    }

    /**
     * Performs the ADM BWF export using the given Atmos session configuration.
     *
     * <p>First validates the configuration. If valid, collects audio from
     * the {@link AudioDataProvider} and writes the ADM BWF file. The result
     * is reported to the {@link ExportResultListener} if one is set.</p>
     *
     * @param config the Atmos session configuration
     * @return the export result
     * @throws IOException if an I/O error occurs during export
     */
    public AtmosExportResult performExport(AtmosSessionConfig config) throws IOException {
        Objects.requireNonNull(config, "config must not be null");

        // Pre-flight validation
        AtmosExportResult validation = AtmosExportWorkflow.validate(config);
        if (!validation.isSuccess()) {
            notifyListener(validation);
            return validation;
        }

        if (audioDataProvider == null) {
            AtmosExportResult noProvider = AtmosExportResult.failure(
                    List.of("No audio data provider configured"));
            notifyListener(noProvider);
            return noProvider;
        }

        if (outputPath == null) {
            AtmosExportResult noPath = AtmosExportResult.failure(
                    List.of("No output path configured"));
            notifyListener(noPath);
            return noPath;
        }

        // Collect audio buffers
        List<float[]> bedAudio = new ArrayList<>();
        for (BedChannel bed : config.getBedChannels()) {
            bedAudio.add(audioDataProvider.getAudioForTrack(bed.trackId()));
        }

        List<float[]> objectAudio = new ArrayList<>();
        for (AudioObject obj : config.getAudioObjects()) {
            objectAudio.add(audioDataProvider.getAudioForTrack(obj.getTrackId()));
        }

        AtmosExportResult result = AtmosExportWorkflow.export(
                config, bedAudio, objectAudio, metadata, outputPath);

        notifyListener(result);
        return result;
    }

    private void notifyListener(AtmosExportResult result) {
        if (resultListener != null) {
            resultListener.onExportComplete(result);
        }
    }
}
