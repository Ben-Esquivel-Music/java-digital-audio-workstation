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
 * <p>The pipeline supports:</p>
 * <ul>
 *   <li><strong>Count-in</strong> — An audible metronome click for a configurable
 *       number of bars before recording starts (see {@link CountInMode}).</li>
 *   <li><strong>Input monitoring</strong> — Routes the audio input through the
 *       track's mixer channel so the performer can hear themselves in real time
 *       (see {@link InputMonitoringMode}).</li>
 *   <li><strong>Punch-in/punch-out</strong> — Records only within a specified
 *       beat range on the timeline (see {@link PunchRange}).</li>
 * </ul>
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
    private final CountInMode countInMode;
    private final InputMonitoringMode monitoringMode;
    private final PunchRange punchRange;
    private final Map<Track, RecordingSession> sessions = new LinkedHashMap<>();
    private final Map<Track, AudioClip> recordedClips = new LinkedHashMap<>();
    private boolean active;
    private double recordingStartBeat;

    /**
     * Creates a new recording pipeline with default settings (no count-in,
     * monitoring off, no punch range).
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
        this(audioEngine, transport, format, outputDirectory, armedTracks,
                CountInMode.OFF, InputMonitoringMode.OFF, null);
    }

    /**
     * Creates a new recording pipeline with full configuration.
     *
     * @param audioEngine     the audio engine providing input audio
     * @param transport       the transport controlling playback/recording state
     * @param format          the audio format for recording sessions
     * @param outputDirectory the directory for recording segment files
     * @param armedTracks     the tracks armed for recording (must not be empty)
     * @param countInMode     the count-in mode (number of bars before recording)
     * @param monitoringMode  the input monitoring mode
     * @param punchRange      the punch-in/punch-out range, or {@code null} for no punch recording
     */
    public RecordingPipeline(AudioEngine audioEngine, Transport transport,
                             AudioFormat format, Path outputDirectory,
                             List<Track> armedTracks,
                             CountInMode countInMode,
                             InputMonitoringMode monitoringMode,
                             PunchRange punchRange) {
        this.audioEngine = Objects.requireNonNull(audioEngine, "audioEngine must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.format = Objects.requireNonNull(format, "format must not be null");
        this.outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory must not be null");
        Objects.requireNonNull(armedTracks, "armedTracks must not be null");
        if (armedTracks.isEmpty()) {
            throw new IllegalArgumentException("At least one track must be armed for recording");
        }
        this.armedTracks = List.copyOf(armedTracks);
        this.countInMode = Objects.requireNonNull(countInMode, "countInMode must not be null");
        this.monitoringMode = Objects.requireNonNull(monitoringMode, "monitoringMode must not be null");
        this.punchRange = punchRange;
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

        // Capture the recording start position before any transport changes
        recordingStartBeat = punchRange != null
                ? punchRange.punchInBeat()
                : transport.getPositionInBeats();

        // Set recording indicator on armed tracks
        for (Track track : armedTracks) {
            track.setRecording(true);
        }

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

        // Clear recording indicator on armed tracks
        for (Track track : armedTracks) {
            track.setRecording(false);
        }

        // Stop the transport (this resets positionInBeats to 0)
        transport.stop();

        // Finalize sessions and create clips using the captured start position
        List<AudioClip> clips = new ArrayList<>();

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
                        recordingStartBeat,
                        durationBeats,
                        segmentPath);

                // Attach the captured audio data to the clip for playback
                float[][] capturedAudio = session.getCapturedAudio();
                if (capturedAudio != null) {
                    clip.setAudioData(capturedAudio);
                }

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
     * Returns the count-in mode configured for this pipeline.
     *
     * @return the count-in mode
     */
    public CountInMode getCountInMode() {
        return countInMode;
    }

    /**
     * Returns the input monitoring mode configured for this pipeline.
     *
     * @return the monitoring mode
     */
    public InputMonitoringMode getMonitoringMode() {
        return monitoringMode;
    }

    /**
     * Returns the punch range configured for this pipeline, or {@code null}
     * if no punch recording is active.
     *
     * @return the punch range, or {@code null}
     */
    public PunchRange getPunchRange() {
        return punchRange;
    }

    /**
     * Returns the beat position at which recording started.
     *
     * <p>This is captured when {@link #start()} is called and used to
     * position recorded clips on the timeline.</p>
     *
     * @return the recording start beat position
     */
    public double getRecordingStartBeat() {
        return recordingStartBeat;
    }

    /**
     * Generates count-in click audio for this pipeline's configuration.
     *
     * <p>Returns audio data containing metronome clicks for the configured
     * number of count-in bars. Returns an empty buffer if count-in is
     * {@link CountInMode#OFF}.</p>
     *
     * @return audio data as {@code [channel][sample]} in [-1.0, 1.0]
     */
    public float[][] generateCountInAudio() {
        Metronome metronome = new Metronome(format.sampleRate(), format.channels());
        return metronome.generateCountIn(
                countInMode,
                transport.getTempo(),
                transport.getTimeSignatureNumerator());
    }

    /**
     * Returns whether input monitoring should be active, given the current
     * monitoring mode and transport state.
     *
     * @return {@code true} if input monitoring is active
     */
    public boolean isInputMonitoringActive() {
        return switch (monitoringMode) {
            case OFF -> false;
            case ALWAYS -> true;
            case AUTO -> active;
        };
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
        // When punch recording is active, only record within the punch range
        if (punchRange != null) {
            double currentBeat = transport.getPositionInBeats();
            if (!punchRange.contains(currentBeat)) {
                return;
            }
        }

        for (RecordingSession session : sessions.values()) {
            session.recordAudioData(inputBuffer, numFrames);
        }
    }
}
