package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.core.audio.RenderPipeline;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.export.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Exports individual tracks (stems) from a project as separate audio files.
 *
 * <p>Stems are rendered through {@link RenderPipeline#renderOffline} — the
 * same unified render pipeline that drives live audio playback. For each
 * selected track, the project's {@link Mixer} is reconfigured so that only
 * that track's channel contributes to the master mix (all other channels
 * are temporarily muted), then a fresh {@link com.benesquivelmusic.daw.core.transport.Transport}
 * is positioned at beat 0 and the pipeline renders the entire project
 * length into the stem buffer. The resulting audio includes the channel's
 * insert effects, volume, and pan exactly as the user would hear them
 * when soloing the track during live playback (the WYHIWYG guarantee from
 * story 102 — "Playback-Export Parity: Unified Render Pipeline for Live
 * and Offline Processing").</p>
 *
 * <p>The project's master effects chain is intentionally bypassed during
 * stem rendering so that each stem represents the channel's contribution
 * to the bus, not the post-mastering signal — this preserves the
 * historical stem-export contract.</p>
 *
 * <p>Mute state and master volume / mute on the project's mixer are saved
 * before rendering and restored once the export completes (or fails) by
 * the {@link OfflineStemRenderer} helper.</p>
 */
public final class StemExporter {

    private final AudioExporter exporter;

    /**
     * Creates a stem exporter backed by the default audio exporter.
     */
    public StemExporter() {
        this.exporter = new DefaultAudioExporter();
    }

    /**
     * Creates a stem exporter with a custom audio exporter (for testing).
     *
     * @param exporter the audio exporter to delegate to
     */
    StemExporter(AudioExporter exporter) {
        this.exporter = Objects.requireNonNull(exporter, "exporter must not be null");
    }

    /**
     * Exports selected tracks from the project as individual stem files by
     * delegating per-track rendering to {@link RenderPipeline#renderOffline}
     * via {@link OfflineStemRenderer}.
     *
     * @param project   the DAW project containing the tracks and mixer
     * @param config    the stem export configuration
     * @param outputDir the directory to write stem files to
     * @param totalProjectBeats the total project length in beats (all stems
     *                          are rendered to this length for alignment)
     * @param listener  receives progress updates across the entire batch
     * @return the result of the stem export operation
     * @throws IOException if an I/O error occurs while writing
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if {@code totalProjectBeats} is not
     *                                  positive, or if any track index is out
     *                                  of range
     */
    public StemExportResult exportStems(
            DawProject project,
            StemExportConfig config,
            Path outputDir,
            double totalProjectBeats,
            ExportProgressListener listener) throws IOException {

        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        if (totalProjectBeats <= 0) {
            throw new IllegalArgumentException(
                    "totalProjectBeats must be positive: " + totalProjectBeats);
        }

        List<Track> allTracks = project.getTracks();
        List<Integer> indices = config.trackIndices();
        for (int index : indices) {
            if (index < 0 || index >= allTracks.size()) {
                throw new IllegalArgumentException(
                        "track index out of range: " + index);
            }
        }

        long startTime = System.currentTimeMillis();
        int sampleRate = (int) project.getFormat().sampleRate();
        double tempo = project.getTransport().getTempo();
        int totalFrames = TrackBouncer.beatsToFrames(totalProjectBeats, sampleRate, tempo);
        AudioExportConfig audioConfig = config.audioExportConfig();

        List<ExportResult> results = new ArrayList<>();
        int total = indices.size();

        if (total == 0) {
            listener.onProgress(1.0, "Stem export complete");
            return new StemExportResult(results, System.currentTimeMillis() - startTime);
        }

        try (OfflineStemRenderer renderer = new OfflineStemRenderer(project, totalFrames)) {
            for (int i = 0; i < total; i++) {
                int trackIndex = indices.get(i);
                Track track = allTracks.get(trackIndex);
                double progressBase = (double) i / total;
                double progressStep = 1.0 / total;

                listener.onProgress(progressBase,
                        "Exporting stem " + (i + 1) + "/" + total + ": " + track.getName());

                float[][] stemBuffer = renderer.render(
                        track, project.getMixerChannelForTrack(track));

                String fileName = generateFileName(
                        config.namingConvention(), config.projectName(),
                        track.getName(), i + 1, total);

                ExportProgressListener trackListener = (progress, stage) ->
                        listener.onProgress(progressBase + progress * progressStep, stage);

                ExportResult result = exporter.export(
                        stemBuffer, sampleRate, outputDir, fileName,
                        audioConfig, trackListener);
                results.add(result);
            }
        }

        listener.onProgress(1.0, "Stem export complete");
        long totalDuration = System.currentTimeMillis() - startTime;
        return new StemExportResult(results, totalDuration);
    }

    /**
     * Generates the stem filename based on the naming convention.
     *
     * @param convention  the naming convention
     * @param projectName the project name
     * @param trackName   the track name
     * @param number      the 1-based track number
     * @param total       the total number of tracks being exported
     * @return the filename (without extension)
     */
    static String generateFileName(StemNamingConvention convention,
                                   String projectName, String trackName,
                                   int number, int total) {
        String sanitizedTrack = sanitize(trackName);
        String sanitizedProject = sanitize(projectName);
        int padWidth = String.valueOf(total).length();

        return switch (convention) {
            case TRACK_NAME -> sanitizedTrack;
            case PROJECT_PREFIX -> sanitizedProject + "_" + sanitizedTrack;
            case NUMBERED -> String.format("%0" + padWidth + "d_%s", number, sanitizedTrack);
        };
    }

    /**
     * Sanitizes a name for use as a filename by replacing characters
     * that are not alphanumeric, hyphens, underscores, or spaces with
     * underscores, then trimming.
     */
    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9\\-_ ]", "_").trim();
    }
}
