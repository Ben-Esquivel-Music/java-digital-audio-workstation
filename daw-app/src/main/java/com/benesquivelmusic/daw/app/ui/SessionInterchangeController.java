package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.session.dawproject.DawProjectSessionExporter;
import com.benesquivelmusic.daw.core.session.dawproject.DawProjectSessionImporter;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.session.SessionData;
import com.benesquivelmusic.daw.sdk.session.SessionData.SessionClip;
import com.benesquivelmusic.daw.sdk.session.SessionData.SessionTrack;
import com.benesquivelmusic.daw.sdk.session.SessionExportResult;
import com.benesquivelmusic.daw.sdk.session.SessionImportResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bridges the internal {@link DawProject} model and the format-neutral
 * {@link SessionData} used by the DAWproject importer/exporter.
 *
 * <p>This controller is intentionally free of JavaFX dependencies so that
 * it can be unit-tested without a running UI toolkit.</p>
 */
public final class SessionInterchangeController {

    private final DawProjectSessionImporter importer;
    private final DawProjectSessionExporter exporter;

    /**
     * Creates a new controller with the default importer and exporter.
     */
    public SessionInterchangeController() {
        this(new DawProjectSessionImporter(), new DawProjectSessionExporter());
    }

    /**
     * Creates a new controller with the supplied importer and exporter
     * (useful for testing).
     *
     * @param importer the session importer
     * @param exporter the session exporter
     */
    SessionInterchangeController(DawProjectSessionImporter importer,
                                 DawProjectSessionExporter exporter) {
        this.importer = Objects.requireNonNull(importer, "importer must not be null");
        this.exporter = Objects.requireNonNull(exporter, "exporter must not be null");
    }

    // ── Export ───────────────────────────────────────────────────────────────

    /**
     * Converts a {@link DawProject} to a {@link SessionData} suitable for
     * serialization.
     *
     * @param project the project to convert
     * @return the session data representation
     */
    public SessionData buildSessionData(DawProject project) {
        Objects.requireNonNull(project, "project must not be null");

        Transport transport = project.getTransport();
        double sampleRate = project.getFormat().sampleRate();

        List<SessionTrack> sessionTracks = new ArrayList<>();
        for (Track track : project.getTracks()) {
            sessionTracks.add(convertTrack(track, project));
        }

        return new SessionData(
                project.getName(),
                transport.getTempo(),
                transport.getTimeSignatureNumerator(),
                transport.getTimeSignatureDenominator(),
                sampleRate,
                sessionTracks
        );
    }

    /**
     * Exports the given project to a DAWproject file.
     *
     * @param project   the project to export
     * @param outputDir the directory to write the exported file
     * @param baseName  the base filename (without extension)
     * @return the export result containing the output path and any warnings
     * @throws IOException if an I/O error occurs
     */
    public SessionExportResult exportSession(DawProject project, Path outputDir, String baseName)
            throws IOException {
        SessionData sessionData = buildSessionData(project);
        return exporter.exportSession(sessionData, outputDir, baseName);
    }

    // ── Import ──────────────────────────────────────────────────────────────

    /**
     * Imports a DAWproject file and returns the parsed result.
     *
     * @param file the DAWproject file to import
     * @return the import result containing session data and warnings
     * @throws IOException if an I/O error occurs
     */
    public SessionImportResult importSession(Path file) throws IOException {
        return importer.importSession(file);
    }

    /**
     * Applies imported {@link SessionData} to a {@link DawProject}, creating
     * tracks, setting mixer channel levels, and adding audio clips.
     *
     * <p>The project name and transport settings (tempo, time signature) are
     * updated from the session data. Each session track is mapped to a new
     * {@link Track} with corresponding mixer channel volume/pan/mute/solo.</p>
     *
     * @param sessionData the imported session data
     * @param project     the target project to populate
     */
    public void applySessionData(SessionData sessionData, DawProject project) {
        Objects.requireNonNull(sessionData, "sessionData must not be null");
        Objects.requireNonNull(project, "project must not be null");

        project.setName(sessionData.projectName());

        Transport transport = project.getTransport();
        double tempo = sessionData.tempo();
        if (tempo >= 20.0 && tempo <= 999.0) {
            transport.setTempo(tempo);
        }
        int tsNum = sessionData.timeSignatureNumerator();
        int tsDen = sessionData.timeSignatureDenominator();
        if (tsNum > 0 && tsDen > 0) {
            transport.setTimeSignature(tsNum, tsDen);
        }

        for (SessionTrack sessionTrack : sessionData.tracks()) {
            Track track = createTrackFromSession(sessionTrack, project);
            applyMixerSettings(sessionTrack, project, track);
            addClipsToTrack(sessionTrack, track);
        }
    }

    // ── Import summary ──────────────────────────────────────────────────────

    /**
     * Builds a human-readable summary of what was imported.
     *
     * @param result the import result
     * @return a multi-line summary string
     */
    public String buildImportSummary(SessionImportResult result) {
        Objects.requireNonNull(result, "result must not be null");

        SessionData data = result.sessionData();
        StringBuilder summary = new StringBuilder();
        summary.append("Project: ").append(data.projectName()).append("\n");
        summary.append("Tempo: ").append(data.tempo()).append(" BPM\n");
        summary.append("Time Signature: ")
                .append(data.timeSignatureNumerator())
                .append("/")
                .append(data.timeSignatureDenominator())
                .append("\n");
        summary.append("Sample Rate: ").append(data.sampleRate()).append(" Hz\n");
        summary.append("Tracks: ").append(data.tracks().size()).append("\n");

        int totalClips = 0;
        for (SessionTrack track : data.tracks()) {
            totalClips += track.clips().size();
        }
        summary.append("Clips: ").append(totalClips).append("\n");

        if (!result.warnings().isEmpty()) {
            summary.append("\nWarnings:\n");
            for (String warning : result.warnings()) {
                summary.append("  \u2022 ").append(warning).append("\n");
            }
        }

        return summary.toString();
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private SessionTrack convertTrack(Track track, DawProject project) {
        MixerChannel channel = project.getMixerChannelForTrack(track);
        double volume = channel != null ? channel.getVolume() : track.getVolume();
        double pan = channel != null ? channel.getPan() : track.getPan();
        boolean muted = channel != null ? channel.isMuted() : track.isMuted();
        boolean solo = channel != null ? channel.isSolo() : track.isSolo();

        List<SessionClip> sessionClips = new ArrayList<>();
        for (AudioClip clip : track.getClips()) {
            sessionClips.add(new SessionClip(
                    clip.getName(),
                    clip.getStartBeat(),
                    clip.getDurationBeats(),
                    clip.getSourceOffsetBeats(),
                    clip.getSourceFilePath(),
                    clip.getGainDb()
            ));
        }

        return new SessionTrack(
                track.getName(),
                track.getType().name(),
                volume,
                pan,
                muted,
                solo,
                sessionClips
        );
    }

    private Track createTrackFromSession(SessionTrack sessionTrack, DawProject project) {
        TrackType trackType = mapSessionType(sessionTrack.type());
        Track track = new Track(sessionTrack.name(), trackType);
        project.addTrack(track);
        return track;
    }

    private void applyMixerSettings(SessionTrack sessionTrack, DawProject project, Track track) {
        MixerChannel channel = project.getMixerChannelForTrack(track);
        if (channel != null) {
            double volume = Math.max(0.0, Math.min(1.0, sessionTrack.volume()));
            double pan = Math.max(-1.0, Math.min(1.0, sessionTrack.pan()));
            channel.setVolume(volume);
            channel.setPan(pan);
            channel.setMuted(sessionTrack.muted());
            channel.setSolo(sessionTrack.solo());
        }
    }

    private void addClipsToTrack(SessionTrack sessionTrack, Track track) {
        for (SessionClip sessionClip : sessionTrack.clips()) {
            double duration = sessionClip.durationBeats();
            if (duration <= 0) {
                duration = 1.0;
            }
            double startBeat = Math.max(0.0, sessionClip.startBeat());
            AudioClip clip = new AudioClip(
                    sessionClip.name(),
                    startBeat,
                    duration,
                    sessionClip.sourceFilePath()
            );
            clip.setSourceOffsetBeats(sessionClip.sourceOffsetBeats());
            clip.setGainDb(sessionClip.gainDb());
            track.addClip(clip);
        }
    }

    private TrackType mapSessionType(String type) {
        if (type == null) {
            return TrackType.AUDIO;
        }
        return switch (type.toUpperCase()) {
            case "MIDI" -> TrackType.MIDI;
            case "AUX" -> TrackType.AUX;
            case "MASTER" -> TrackType.MASTER;
            default -> TrackType.AUDIO;
        };
    }
}
