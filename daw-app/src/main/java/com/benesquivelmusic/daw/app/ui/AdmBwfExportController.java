package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.export.AtmosExportResult;
import com.benesquivelmusic.daw.core.export.AtmosExportWorkflow;
import com.benesquivelmusic.daw.core.export.ObjectTrajectory;
import com.benesquivelmusic.daw.core.export.ObjectTrajectoryBuilder;
import com.benesquivelmusic.daw.core.automation.AutomationData;
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

    /**
     * Provider interface for per-object automation data. Implementations
     * supply the {@link AutomationData} container holding the
     * {@code ObjectParameterTarget} lanes for a given object track. When
     * present, the export emits time-stamped {@code audioBlockFormat}
     * entries (story 172).
     *
     * <p>If this provider is not set or returns {@code null} for a given
     * track, the export falls back to a single static block format derived
     * from the object's metadata.</p>
     */
    @FunctionalInterface
    public interface ObjectAutomationProvider {

        /**
         * Returns the automation data for the given object track, or
         * {@code null} if the track has no automation lanes.
         *
         * @param trackId the object's track identifier
         * @return the automation data, or {@code null}
         */
        AutomationData getAutomationForTrack(String trackId);
    }

    private AudioDataProvider audioDataProvider;
    private ExportResultListener resultListener;
    private AudioMetadata metadata = AudioMetadata.EMPTY;
    private Path outputPath;
    private ObjectAutomationProvider objectAutomationProvider;
    private double tempoBpm = 120.0;

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
     * Sets a provider that supplies per-object automation data. When set,
     * the exporter walks each object's {@code ObjectParameterTarget} lanes
     * to emit time-stamped {@code audioBlockFormat} positions (story 172).
     *
     * @param provider the provider, or {@code null} to clear
     */
    public void setObjectAutomationProvider(ObjectAutomationProvider provider) {
        this.objectAutomationProvider = provider;
    }

    /** Returns the current object-automation provider, or {@code null}. */
    public ObjectAutomationProvider getObjectAutomationProvider() {
        return objectAutomationProvider;
    }

    /**
     * Sets the tempo (in BPM) used to translate beat-timed automation points
     * into seconds for the exported {@code rtime}/{@code duration}
     * attributes. Defaults to 120 BPM.
     *
     * @param tempoBpm the project tempo (must be {@code > 0})
     */
    public void setTempoBpm(double tempoBpm) {
        if (tempoBpm <= 0.0) {
            throw new IllegalArgumentException("tempoBpm must be > 0: " + tempoBpm);
        }
        this.tempoBpm = tempoBpm;
    }

    /** Returns the configured tempo in BPM. */
    public double getTempoBpm() {
        return tempoBpm;
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

        // Build per-object trajectories from automation lanes (story 172).
        // When no automation provider is set, the list is empty and the
        // exporter falls back to static metadata for every object.
        List<ObjectTrajectory> trajectories = buildTrajectories(config, objectAudio);

        AtmosExportResult result = AtmosExportWorkflow.export(
                config, bedAudio, objectAudio, trajectories, metadata, outputPath);

        notifyListener(result);
        return result;
    }

    private List<ObjectTrajectory> buildTrajectories(AtmosSessionConfig config,
                                                     List<float[]> objectAudio) {
        if (objectAutomationProvider == null) {
            return List.of();
        }
        List<ObjectTrajectory> trajectories = new ArrayList<>();
        int sampleRate = config.getSampleRate();
        for (int i = 0; i < config.getAudioObjects().size(); i++) {
            AudioObject obj = config.getAudioObjects().get(i);
            AutomationData data = objectAutomationProvider.getAutomationForTrack(
                    obj.getTrackId());
            if (data == null) {
                trajectories.add(ObjectTrajectory.empty());
                continue;
            }
            float[] audio = (i < objectAudio.size()) ? objectAudio.get(i) : null;
            int frames = (audio != null) ? audio.length : 0;
            double durationSeconds = (frames > 0)
                    ? (double) frames / sampleRate
                    : 1.0; // fall back to a 1-second window when no audio
            trajectories.add(ObjectTrajectoryBuilder.build(
                    data, obj.getTrackId(), obj.getMetadata(),
                    tempoBpm, durationSeconds));
        }
        return trajectories;
    }

    private void notifyListener(AtmosExportResult result) {
        if (resultListener != null) {
            resultListener.onExportComplete(result);
        }
    }
}
