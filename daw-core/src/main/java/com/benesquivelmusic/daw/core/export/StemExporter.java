package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.export.AudioExportConfig;
import com.benesquivelmusic.daw.sdk.export.ExportProgressListener;
import com.benesquivelmusic.daw.sdk.export.ExportResult;
import com.benesquivelmusic.daw.sdk.export.StemExportConfig;
import com.benesquivelmusic.daw.sdk.export.StemExportResult;
import com.benesquivelmusic.daw.sdk.export.StemNamingConvention;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Exports individual tracks (stems) from a project as separate audio files.
 *
 * <p>Each selected track is bounced via {@link TrackBouncer}, processed through
 * its {@link MixerChannel} (applying volume, pan, and insert effects), padded
 * to the project duration for alignment, and written to a separate file using
 * the configured audio format.</p>
 *
 * <p>Progress is reported across the entire batch: each track contributes an
 * equal fraction of the total progress from 0.0 to 1.0.</p>
 */
public final class StemExporter {

    private final DefaultAudioExporter exporter;

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
    StemExporter(DefaultAudioExporter exporter) {
        this.exporter = Objects.requireNonNull(exporter, "exporter must not be null");
    }

    /**
     * Exports selected tracks from the project as individual stem files.
     *
     * <p>Each track is bounced to audio, processed through its mixer channel
     * (volume, pan, insert effects), padded to the project duration, and
     * written to a file in the output directory. All stems share the same
     * audio format configuration.</p>
     *
     * @param project   the DAW project containing the tracks and mixer
     * @param config    the stem export configuration
     * @param outputDir the directory to write stem files to
     * @param totalProjectBeats the total project length in beats (all stems
     *                          are padded to this length for alignment)
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
        int channels = project.getFormat().channels();
        double tempo = project.getTransport().getTempo();
        int totalFrames = TrackBouncer.beatsToFrames(totalProjectBeats, sampleRate, tempo);
        AudioExportConfig audioConfig = config.audioExportConfig();

        List<ExportResult> results = new ArrayList<>();
        int total = indices.size();

        for (int i = 0; i < total; i++) {
            int trackIndex = indices.get(i);
            Track track = allTracks.get(trackIndex);
            double progressBase = (double) i / total;
            double progressStep = 1.0 / total;

            listener.onProgress(progressBase,
                    "Exporting stem " + (i + 1) + "/" + total + ": " + track.getName());

            // Step 1: Bounce the track's clips into a raw audio buffer
            float[][] bounced = TrackBouncer.bounce(track, sampleRate, tempo, channels);

            // Step 2: Pad or create a buffer matching the project duration
            float[][] stemBuffer = new float[channels][totalFrames];
            if (bounced != null) {
                for (int ch = 0; ch < channels; ch++) {
                    int srcChannel = Math.min(ch, bounced.length - 1);
                    int copyLength = Math.min(bounced[srcChannel].length, totalFrames);
                    System.arraycopy(bounced[srcChannel], 0, stemBuffer[ch], 0, copyLength);
                }
            }

            // Step 3: Process through mixer channel (volume, pan, insert effects)
            MixerChannel mixerChannel = project.getMixerChannelForTrack(track);
            if (mixerChannel != null) {
                applyMixerChannel(stemBuffer, mixerChannel, totalFrames, channels);
            }

            // Step 4: Generate filename and export
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

        listener.onProgress(1.0, "Stem export complete");
        long totalDuration = System.currentTimeMillis() - startTime;
        return new StemExportResult(results, totalDuration);
    }

    /**
     * Applies mixer channel processing to the audio buffer: insert effects,
     * volume, and pan (constant-power pan law for stereo).
     */
    static void applyMixerChannel(float[][] buffer, MixerChannel channel,
                                   int numFrames, int channels) {
        // Apply insert effects
        if (!channel.getEffectsChain().isEmpty()
                && !channel.getEffectsChain().isBypassed()) {
            float[][] effectOutput = new float[channels][numFrames];
            channel.getEffectsChain().process(buffer, effectOutput, numFrames);
            for (int ch = 0; ch < channels; ch++) {
                System.arraycopy(effectOutput[ch], 0, buffer[ch], 0, numFrames);
            }
        }

        // Apply volume and pan
        double volume = channel.getVolume();
        double pan = channel.getPan();

        if (channels >= 2) {
            // Constant-power pan law (matches Mixer.mixDown)
            double angle = (pan + 1.0) * 0.25 * Math.PI;
            float leftGain = (float) (Math.cos(angle) * volume);
            float rightGain = (float) (Math.sin(angle) * volume);

            for (int f = 0; f < numFrames; f++) {
                buffer[0][f] *= leftGain;
            }
            for (int f = 0; f < numFrames; f++) {
                buffer[1][f] *= rightGain;
            }
            // Additional channels: apply volume only (no pan)
            for (int ch = 2; ch < channels; ch++) {
                for (int f = 0; f < numFrames; f++) {
                    buffer[ch][f] *= (float) volume;
                }
            }
        } else {
            // Mono: apply volume only
            for (int f = 0; f < numFrames; f++) {
                buffer[0][f] *= (float) volume;
            }
        }
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
