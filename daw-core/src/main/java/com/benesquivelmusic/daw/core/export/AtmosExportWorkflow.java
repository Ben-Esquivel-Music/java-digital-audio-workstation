package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.core.spatial.objectbased.AtmosSessionConfig;
import com.benesquivelmusic.daw.sdk.export.AudioMetadata;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLayout;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Orchestrates the ADM BWF export workflow: validates the Atmos session
 * configuration, generates warnings for potential issues, and writes the
 * ADM BWF file using {@link AdmBwfExporter}.
 *
 * <p>Typical usage:</p>
 * <ol>
 *   <li>Populate an {@link AtmosSessionConfig} with bed channels and audio objects</li>
 *   <li>Call {@link #validate(AtmosSessionConfig)} to check for errors and warnings</li>
 *   <li>If the result has no errors, call
 *       {@link #export(AtmosSessionConfig, List, List, AudioMetadata, Path)}
 *       to write the ADM BWF file</li>
 * </ol>
 */
public final class AtmosExportWorkflow {

    private AtmosExportWorkflow() {
        // utility class
    }

    /**
     * Validates the session configuration and returns any errors or warnings
     * without writing a file.
     *
     * @param config the Atmos session configuration
     * @return the validation result
     */
    public static AtmosExportResult validate(AtmosSessionConfig config) {
        Objects.requireNonNull(config, "config must not be null");

        List<String> errors = config.validate();
        List<String> warnings = buildWarnings(config);

        if (!errors.isEmpty()) {
            return AtmosExportResult.failure(errors, warnings);
        }
        return AtmosExportResult.success(warnings);
    }

    /**
     * Validates and exports the Atmos session as an ADM BWF file.
     *
     * <p>Validation is always performed before writing. If validation fails,
     * no file is written and the returned result contains the errors.</p>
     *
     * @param config      the Atmos session configuration
     * @param bedAudio    audio buffers for bed channels ({@code [bed][sample]})
     * @param objectAudio audio buffers for audio objects ({@code [object][sample]})
     * @param metadata    file-level metadata to embed
     * @param outputPath  the output file path
     * @return the export result
     * @throws IOException if an I/O error occurs during writing
     */
    public static AtmosExportResult export(AtmosSessionConfig config,
                                           List<float[]> bedAudio,
                                           List<float[]> objectAudio,
                                           AudioMetadata metadata,
                                           Path outputPath) throws IOException {
        return export(config, bedAudio, objectAudio, List.of(), metadata, outputPath);
    }

    /**
     * Validates and exports the Atmos session, embedding optional per-object
     * time-stamped trajectories as multiple {@code audioBlockFormat} entries
     * (story 172).
     *
     * @param config       the Atmos session configuration
     * @param bedAudio     audio buffers for bed channels ({@code [bed][sample]})
     * @param objectAudio  audio buffers for audio objects ({@code [object][sample]})
     * @param trajectories per-object trajectories, parallel to
     *                     {@code config.getAudioObjects()}; may be empty for
     *                     a static export
     * @param metadata     file-level metadata to embed
     * @param outputPath   the output file path
     * @return the export result
     * @throws IOException if an I/O error occurs during writing
     */
    public static AtmosExportResult export(AtmosSessionConfig config,
                                           List<float[]> bedAudio,
                                           List<float[]> objectAudio,
                                           List<ObjectTrajectory> trajectories,
                                           AudioMetadata metadata,
                                           Path outputPath) throws IOException {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(bedAudio, "bedAudio must not be null");
        Objects.requireNonNull(objectAudio, "objectAudio must not be null");
        Objects.requireNonNull(trajectories, "trajectories must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
        Objects.requireNonNull(outputPath, "outputPath must not be null");

        AtmosExportResult validation = validate(config);
        if (!validation.isSuccess()) {
            return validation;
        }

        AdmBwfExporter.export(
                config.getBedChannels(),
                bedAudio,
                config.getAudioObjects(),
                objectAudio,
                trajectories,
                config.getLayout(),
                config.getSampleRate(),
                config.getBitDepth(),
                metadata,
                outputPath);

        return AtmosExportResult.success(validation.getWarnings());
    }

    /**
     * Builds informational warnings for the session configuration.
     *
     * @param config the Atmos session configuration
     * @return a list of warning messages (may be empty)
     */
    private static List<String> buildWarnings(AtmosSessionConfig config) {
        List<String> warnings = new ArrayList<>();

        if (config.getBedChannels().isEmpty() && config.getAudioObjects().isEmpty()) {
            warnings.add("Session contains no bed channels or audio objects");
        }

        SpeakerLayout layout = config.getLayout();
        int bedCount = config.getBedChannels().size();
        if (bedCount > 0 && bedCount < layout.channelCount()) {
            warnings.add("Only %d of %d bed channels are assigned for layout '%s'"
                    .formatted(bedCount, layout.channelCount(), layout.name()));
        }

        int remaining = AtmosSessionConfig.maxObjectsForBedCount(config.getBedChannels().size());
        int objectCount = config.getAudioObjects().size();
        if (objectCount > 0 && remaining - objectCount <= 10 && remaining - objectCount > 0) {
            warnings.add("Approaching Atmos object limit: %d of %d objects used"
                    .formatted(objectCount, remaining));
        }

        return warnings;
    }
}
