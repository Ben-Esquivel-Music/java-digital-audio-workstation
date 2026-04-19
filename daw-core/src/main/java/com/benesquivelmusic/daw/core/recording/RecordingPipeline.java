package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.transport.PunchRegion;

import java.nio.file.Path;
import java.util.*;

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
    private final Map<Track, float[][]> routedInputBuffers = new LinkedHashMap<>();
    private boolean active;
    private double recordingStartBeat;
    /** Absolute sample-frame position aligned with {@link Transport}. Used for
     *  sample-accurate gating against {@link Transport#getPunchRegion()}. */
    private long currentFrame;
    /** Previous callback's {@code currentFrame} at entry — enables detecting
     *  the block containing the punch-in boundary for crossfade ramp-up and
     *  supporting auto-punch re-entry after a transport rewind / loop. */
    private boolean wasInsidePunchRegion;

    /** Duration, in seconds, of the cosine crossfade applied at the
     *  punch-in and punch-out boundaries to avoid clicks. */
    private static final double PUNCH_CROSSFADE_SECONDS = 0.005;

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

        // Capture the recording start position before any transport changes.
        // When the transport has an enabled (frame-based) punch region, prefer
        // its start position so that recorded clips are anchored at the
        // punch-in point even when playback began earlier (e.g. pre-roll).
        PunchRegion transportPunch = transport.isPunchEnabled()
                ? transport.getPunchRegion()
                : null;
        if (transportPunch != null) {
            double bpm = transport.getTempo();
            double startSeconds = transportPunch.startFrames() / format.sampleRate();
            recordingStartBeat = startSeconds * (bpm / 60.0);
        } else if (punchRange != null) {
            recordingStartBeat = punchRange.punchInBeat();
        } else {
            recordingStartBeat = transport.getPositionInBeats();
        }

        // Initialize the frame counter from the transport's current beat
        // position so that sample-accurate punch gating stays aligned with
        // the transport. The transport may start earlier than the punch-in
        // (e.g. pre-roll) — the counter advances monotonically per audio
        // callback until it reaches the punch region.
        currentFrame = beatsToFrames(transport.getPositionInBeats());
        wasInsidePunchRegion = false;

        // Set recording indicator on armed tracks
        for (Track track : armedTracks) {
            track.setRecording(true);
        }

        // Create and start a recording session per armed track, and
        // pre-allocate per-track routed input buffers for extraction
        int bufferFrames = format.bufferSize();
        for (Track track : armedTracks) {
            Path trackDir = outputDirectory.resolve(track.getId());
            RecordingSession session = new RecordingSession(format, trackDir);
            session.start();
            sessions.put(track, session);

            InputRouting routing = track.getInputRouting();
            int routedChannels = routing.isNone() ? format.channels() : routing.channelCount();
            routedInputBuffers.put(track, new float[routedChannels][bufferFrames]);
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
        routedInputBuffers.clear();
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
        // Derive the block position from the transport's current beat position
        // so that punch gating stays aligned after loop/rewind/seek. The
        // recording callback fires *before* advancePosition(), so
        // getPositionInBeats() still reflects this block's start.
        long blockStart = beatsToFrames(transport.getPositionInBeats());
        long blockEnd = blockStart + numFrames;
        // Update the cached frame counter for consistency with the transport.
        currentFrame = blockEnd;

        // Prefer the frame-based transport punch region (sample-accurate) when
        // enabled; fall back to the legacy beat-based PunchRange.
        PunchRegion transportPunch = transport.isPunchEnabled()
                ? transport.getPunchRegion()
                : null;

        if (transportPunch != null) {
            captureWithTransportPunch(inputBuffer, numFrames, blockStart, blockEnd, transportPunch);
            return;
        }

        // Legacy beat-based gating (backward-compatible with existing callers).
        if (punchRange != null) {
            double currentBeat = transport.getPositionInBeats();
            if (!punchRange.contains(currentBeat)) {
                return;
            }
        }

        recordToSessions(inputBuffer, 0, numFrames, null, 0, 0);
    }

    /**
     * Captures input with sample-accurate slicing against {@code punch}.
     *
     * <p>Only frames that fall in {@code [punch.startFrames, punch.endFrames)}
     * are forwarded to the recording sessions. A 5&nbsp;ms cosine crossfade
     * ramp is applied on the blocks that straddle the punch-in and punch-out
     * boundaries to eliminate clicks.</p>
     *
     * <p>Auto-punch: gating is re-evaluated per block, so if the transport
     * rewinds or loops back into the region while {@link #active} remains
     * {@code true}, recording automatically resumes for each new pass.</p>
     */
    private void captureWithTransportPunch(float[][] inputBuffer, int numFrames,
                                           long blockStart, long blockEnd,
                                           PunchRegion punch) {
        long sliceStart = Math.max(blockStart, punch.startFrames());
        long sliceEnd = Math.min(blockEnd, punch.endFrames());
        if (sliceEnd <= sliceStart) {
            wasInsidePunchRegion = false;
            return;
        }

        int offset = (int) (sliceStart - blockStart);
        int sliceFrames = (int) (sliceEnd - sliceStart);
        int fadeFrames = Math.max(1, (int) Math.round(PUNCH_CROSSFADE_SECONDS * format.sampleRate()));
        fadeFrames = Math.min(fadeFrames, sliceFrames);

        // Punch-in crossfade: ramp-in when this block contains startFrames.
        // Also applies on auto-punch re-entry (wasInsidePunchRegion was false).
        int fadeInFrames = (blockStart <= punch.startFrames() && punch.startFrames() < blockEnd
                || !wasInsidePunchRegion)
                ? Math.min(fadeFrames, (int) (sliceEnd - sliceStart))
                : 0;

        // Punch-out crossfade: ramp-out when this block contains endFrames.
        int fadeOutFrames = (blockStart < punch.endFrames() && punch.endFrames() <= blockEnd)
                ? Math.min(fadeFrames, (int) (sliceEnd - sliceStart))
                : 0;

        recordToSessions(inputBuffer, offset, sliceFrames, punch,
                fadeInFrames, fadeOutFrames);

        wasInsidePunchRegion = (blockEnd < punch.endFrames());
    }

    /**
     * Routes {@code sliceFrames} starting at {@code offset} in {@code inputBuffer}
     * to each armed track's recording session, optionally applying a cosine
     * fade-in at the start of the slice and/or fade-out at the end.
     */
    private void recordToSessions(float[][] inputBuffer, int offset, int sliceFrames,
                                  PunchRegion punch,
                                  int fadeInFrames, int fadeOutFrames) {
        for (Track track : armedTracks) {
            RecordingSession session = sessions.get(track);
            if (session == null) {
                continue;
            }

            InputRouting routing = track.getInputRouting();
            if (routing.isNone()) {
                continue;
            }

            float[][] routed = routedInputBuffers.get(track);
            int firstCh = routing.firstChannel();
            int chCount = routing.channelCount();
            for (int ch = 0; ch < chCount; ch++) {
                int srcCh = firstCh + ch;
                if (srcCh < inputBuffer.length && ch < routed.length) {
                    System.arraycopy(inputBuffer[srcCh], offset, routed[ch], 0, sliceFrames);
                }
            }

            if (punch != null && (fadeInFrames > 0 || fadeOutFrames > 0)) {
                applyCosineFades(routed, chCount, sliceFrames, fadeInFrames, fadeOutFrames);
            }

            session.recordAudioData(routed, sliceFrames);
        }
    }

    /**
     * Applies an equal-power cosine fade-in at the start of the slice and/or
     * a cosine fade-out at the end. The ramp rises (or falls) from 0 to 1
     * along {@code 0.5 * (1 - cos(pi * t))} for {@code t} in {@code [0, 1]},
     * giving a click-free transition at the punch boundary.
     */
    private static void applyCosineFades(float[][] buffer, int channels,
                                         int sliceFrames,
                                         int fadeInFrames, int fadeOutFrames) {
        if (fadeInFrames > 0) {
            for (int i = 0; i < fadeInFrames; i++) {
                double t = (double) i / fadeInFrames;
                float gain = (float) (0.5 * (1.0 - Math.cos(Math.PI * t)));
                for (int ch = 0; ch < channels && ch < buffer.length; ch++) {
                    buffer[ch][i] *= gain;
                }
            }
        }
        if (fadeOutFrames > 0) {
            int start = sliceFrames - fadeOutFrames;
            for (int i = 0; i < fadeOutFrames; i++) {
                double t = (double) i / fadeOutFrames;
                float gain = (float) (0.5 * (1.0 + Math.cos(Math.PI * t)));
                for (int ch = 0; ch < channels && ch < buffer.length; ch++) {
                    buffer[ch][start + i] *= gain;
                }
            }
        }
    }

    private long beatsToFrames(double beats) {
        double bpm = transport.getTempo();
        double seconds = beats * 60.0 / bpm;
        return Math.round(seconds * format.sampleRate());
    }
}
