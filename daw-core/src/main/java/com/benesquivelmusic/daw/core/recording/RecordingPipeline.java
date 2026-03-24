package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Orchestrates the recording pipeline by connecting the {@link AudioEngine},
 * {@link Transport}, and {@link RecordingSession} for each armed track.
 *
 * <p>Usage:</p>
 * <ol>
 *   <li>Call {@link #start()} to validate armed tracks, create recording sessions,
 *       wire the audio engine's recording callback, and start the transport.</li>
 *   <li>Audio data flows from {@link AudioEngine#processBlock} through the
 *       recording callback into each track's {@link RecordingSession}.</li>
 *   <li>Call {@link #stop()} to finalize all sessions, create {@link AudioClip}
 *       instances on each armed track, and clean up the callback.</li>
 * </ol>
 */
public final class RecordingPipeline {

    private final AudioEngine audioEngine;
    private final Transport transport;
    private final AudioFormat format;
    private final Path outputDirectory;
    private final List<Track> armedTracks;
    private final Map<Track, RecordingSession> sessions = new LinkedHashMap<>();
    private final Map<Track, AudioClip> recordedClips = new LinkedHashMap<>();
    private boolean active;

    /**
     * Creates a new recording pipeline.
     *
     * @param audioEngine     the audio engine providing input audio
     * @param transport       the transport controlling playback/recording state
     * @param format          the audio format for recording sessions
     * @param outputDirectory the directory for recording segment files
     * @param armedTracks     the tracks armed for recording (must not be empty)
     */
    public RecordingPipeline(AudioEngine audioEngine, Transport transport,
                             AudioFormat format, Path outputDirectory,
                             List<Track> armedTracks) {
        this.audioEngine = Objects.requireNonNull(audioEngine, "audioEngine must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.format = Objects.requireNonNull(format, "format must not be null");
        this.outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory must not be null");
        Objects.requireNonNull(armedTracks, "armedTracks must not be null");
        if (armedTracks.isEmpty()) {
            throw new IllegalArgumentException("At least one track must be armed for recording");
        }
        this.armedTracks = List.copyOf(armedTracks);
    }

    /**
     * Starts the recording pipeline: creates sessions, starts the audio engine,
     * wires the recording callback, and transitions the transport to recording.
     *
     * @throws IllegalStateException if the pipeline is already active
     */
    public void start() {
        if (active) {
            throw new IllegalStateException("Recording pipeline is already active");
        }
        active = true;

        // Create and start a recording session per armed track
        for (Track track : armedTracks) {
            Path trackDir = outputDirectory.resolve(track.getId());
            RecordingSession session = new RecordingSession(format, trackDir);
            session.start();
            sessions.put(track, session);
        }

        // Wire the recording callback on the audio engine
        audioEngine.setRecordingCallback(this::onAudioCaptured);

        // Start the audio engine if it is not already running
        audioEngine.start();

        // Transition transport to recording
        transport.record();
    }

    /**
     * Stops the recording pipeline: finalizes all sessions, creates audio clips
     * on armed tracks, removes the recording callback, and stops the transport.
     *
     * @return the list of {@link AudioClip}s created on armed tracks
     */
    public List<AudioClip> stop() {
        if (!active) {
            return Collections.emptyList();
        }
        active = false;

        // Remove the recording callback
        audioEngine.setRecordingCallback(null);

        // Stop the transport
        transport.stop();

        // Finalize sessions and create clips
        List<AudioClip> clips = new ArrayList<>();
        double positionInBeats = transport.getPositionInBeats();

        for (Track track : armedTracks) {
            RecordingSession session = sessions.get(track);
            if (session != null && session.isActive()) {
                session.stop();
            }
            if (session != null && session.getTotalSamplesRecorded() > 0) {
                double durationSeconds = session.getTotalSamplesRecorded() / format.sampleRate();
                double durationBeats = durationSeconds * (transport.getTempo() / 60.0);
                if (durationBeats <= 0) {
                    durationBeats = 0.01;
                }
                String segmentPath = session.getSegments().isEmpty()
                        ? null
                        : session.getSegments().getFirst().filePath().toString();

                AudioClip clip = new AudioClip(
                        "Recording — " + track.getName(),
                        positionInBeats,
                        durationBeats,
                        segmentPath);
                track.addClip(clip);
                recordedClips.put(track, clip);
                clips.add(clip);
            }
        }

        sessions.clear();
        return Collections.unmodifiableList(clips);
    }

    /**
     * Returns whether the pipeline is currently active (recording).
     *
     * @return {@code true} if recording is in progress
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Returns the recording session for the given track, or {@code null}
     * if no session exists.
     *
     * @param track the track
     * @return the recording session, or {@code null}
     */
    public RecordingSession getSession(Track track) {
        return sessions.get(track);
    }

    /**
     * Returns the list of armed tracks involved in this recording.
     *
     * @return the armed tracks
     */
    public List<Track> getArmedTracks() {
        return armedTracks;
    }

    /**
     * Returns an unmodifiable map of tracks to their recorded clips,
     * populated after {@link #stop()} is called.
     *
     * @return the map of recorded clips
     */
    public Map<Track, AudioClip> getRecordedClips() {
        return Collections.unmodifiableMap(recordedClips);
    }

    /**
     * Finds all armed tracks in the given list.
     *
     * @param tracks the tracks to search
     * @return a list of tracks that are armed for recording
     */
    public static List<Track> findArmedTracks(List<Track> tracks) {
        Objects.requireNonNull(tracks, "tracks must not be null");
        List<Track> armed = new ArrayList<>();
        for (Track track : tracks) {
            if (track.isArmed()) {
                armed.add(track);
            }
        }
        return armed;
    }

    private void onAudioCaptured(float[][] inputBuffer, int numFrames) {
        int bytesPerSample = format.bitDepth() / 8;
        int channels = format.channels();
        long byteSize = (long) numFrames * channels * bytesPerSample;

        for (RecordingSession session : sessions.values()) {
            session.recordSamples(numFrames, byteSize);
        }
    }
}
