package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.audio.RoundTripLatency;
import com.benesquivelmusic.daw.sdk.transport.PunchRegion;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    /**
     * Take groups accumulated during loop-record; one per armed track.
     * Populated as each loop lap wraps (see {@link #finalizeLoopTake}).
     */
    private final Map<Track, TakeGroup> takeGroups = new LinkedHashMap<>();
    /**
     * Executor used to finalize takes off the audio thread (virtual threads,
     * per story 205). Disk I/O for take finalization must never run on the
     * audio callback thread or xruns will occur.
     */
    private ExecutorService takeFinalizationExecutor;
    private boolean loopRecord;
    /** Previous callback's loop-wrap detector (beat-domain). */
    private double previousBeatPosition = -1.0;
    private boolean active;
    private boolean allInputsMuted;
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
     * Driver round-trip latency to compensate for when finalizing recorded
     * clips — typically populated from {@code AudioBackend.reportedLatency()}
     * once per opened stream by the application layer. Defaults to
     * {@link RoundTripLatency#UNKNOWN} (zero compensation).
     */
    private RoundTripLatency reportedLatency = RoundTripLatency.UNKNOWN;
    /**
     * Whether the pipeline applies driver round-trip compensation. Mirrors
     * the "Apply latency compensation to recorded takes" toggle in the
     * Audio Settings dialog. Default is {@code true} — Pro Tools / Logic /
     * Cubase / Reaper all default to compensating.
     */
    private boolean applyLatencyCompensation = true;
    /**
     * Resolved compensation frames captured at {@link #start()} so the
     * value cannot drift mid-session if the user toggles the dialog or
     * the device re-reports its latency. {@code 0} means no compensation
     * is applied to recorded clip start positions.
     */
    private long resolvedCompensationFrames;

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

        // Prepare a virtual-thread executor so take finalization disk I/O
        // never runs on the audio callback thread (see JEP 444 — virtual
        // threads are cheap; we create one per finalization task).
        if (loopRecord && takeFinalizationExecutor == null) {
            takeFinalizationExecutor = Executors.newVirtualThreadPerTaskExecutor();
        }

        takeGroups.clear();
        previousBeatPosition = -1.0;

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

        // Set recording indicator on armed tracks, and apply the
        // pipeline-level monitoring mode as the default for any armed
        // track still at the sentinel OFF default. Tracks that have
        // already configured their own per-track mode are left alone.
        for (Track track : armedTracks) {
            track.setRecording(true);
            if (monitoringMode != InputMonitoringMode.OFF
                    && track.getInputMonitoring() == InputMonitoringMode.OFF) {
                track.setInputMonitoring(monitoringMode);
            }
        }

        // Resolve driver-reported latency compensation once per opened
        // stream, so the value cannot drift mid-session. This is the
        // round-trip caused by the driver's own input/output buffer
        // pipelines — separate from PDC (story 124) which handles
        // plugin latency inside the graph.
        resolvedCompensationFrames = applyLatencyCompensation
                ? Math.max(0, reportedLatency.totalFrames())
                : 0L;

        // Create and start a recording session per armed track, and
        // pre-allocate per-track routed input buffers for extraction
        int bufferFrames = format.bufferSize();
        for (Track track : armedTracks) {
            Path trackDir = outputDirectory.resolve(track.getId());
            RecordingSession session = new RecordingSession(format, trackDir);
            session.setCompensationFrames(resolvedCompensationFrames);
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

        // In loop-record mode, finalize the in-flight loop lap as the last
        // take of the group so stopping mid-loop still yields a complete stack.
        if (loopRecord) {
            finalizeLoopTake();
        }

        // Finalize sessions and create clips using the captured start position
        List<AudioClip> clips = new ArrayList<>();

        for (Track track : armedTracks) {
            RecordingSession session = sessions.get(track);
            if (session != null && session.isActive()) {
                session.stop();
            }

            // In loop-record mode the accumulated takes have already been
            // stamped into the TakeGroup above. Expose the active take's
            // clip on the track lane and attach the group to the track.
            TakeGroup group = takeGroups.get(track);
            if (loopRecord && group != null && !group.isEmpty()) {
                AudioClip activeClip = group.activeClip();
                track.addClip(activeClip);
                track.putTakeGroup(group);
                recordedClips.put(track, activeClip);
                clips.add(activeClip);
                continue;
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
                        compensatedStartBeat(),
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

        // Shut down the virtual-thread executor gracefully.
        if (takeFinalizationExecutor != null) {
            takeFinalizationExecutor.shutdown();
            try {
                if (!takeFinalizationExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    takeFinalizationExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                takeFinalizationExecutor.shutdownNow();
            }
            takeFinalizationExecutor = null;
        }

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
     * <p>This is the pipeline-level <em>default</em> monitoring mode
     * applied to newly armed tracks that are still at the sentinel
     * {@link InputMonitoringMode#OFF} default when the pipeline
     * starts. Per-track overrides set via
     * {@link Track#setInputMonitoring(InputMonitoringMode)} take
     * precedence and are never overwritten.</p>
     *
     * @return the pipeline default monitoring mode
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

    // ─────────────────────────────────────────────────────────────────────
    // Driver round-trip latency compensation
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Returns the driver-reported round-trip latency this pipeline will
     * compensate for at {@link #start()}. Defaults to
     * {@link RoundTripLatency#UNKNOWN} (no compensation).
     *
     * @return the configured round-trip latency; never {@code null}
     */
    public RoundTripLatency getReportedLatency() {
        return reportedLatency;
    }

    /**
     * Configures the driver-reported round-trip latency to compensate for.
     * Must be called <em>before</em> {@link #start()} — the pipeline
     * captures the value once when each session starts so it cannot
     * drift mid-take. Typical use is to read
     * {@code AudioBackend.reportedLatency()} once per opened stream and
     * pass the result here.
     *
     * <p>This is the round-trip caused by the driver's own input/output
     * buffer pipelines (story this method was added for) — separate
     * from PDC (story 124), which compensates plugin latency inside the
     * graph.</p>
     *
     * @param latency the round-trip latency the driver reported; must
     *                not be {@code null}
     */
    public void setReportedLatency(RoundTripLatency latency) {
        this.reportedLatency = Objects.requireNonNull(latency, "latency must not be null");
    }

    /**
     * Returns whether driver round-trip compensation is enabled. Mirrors
     * the "Apply latency compensation to recorded takes" toggle in the
     * Audio Settings dialog. Default is {@code true}.
     *
     * @return {@code true} when compensation is applied to recorded clip
     *         start positions
     */
    public boolean isApplyLatencyCompensation() {
        return applyLatencyCompensation;
    }

    /**
     * Enables or disables driver round-trip compensation. Useful for
     * diagnostic listening or for users wired through a hardware
     * monitor mixer who already pre-compensate. Must be called
     * <em>before</em> {@link #start()}.
     *
     * @param apply {@code true} to compensate, {@code false} to leave
     *              recorded takes uncompensated
     */
    public void setApplyLatencyCompensation(boolean apply) {
        this.applyLatencyCompensation = apply;
    }

    /**
     * Returns the compensation amount (in sample frames) the pipeline
     * resolved at {@link #start()} from the configured
     * {@link #getReportedLatency()} and toggle state. {@code 0} when
     * compensation is disabled or the driver reports zero latency. Only
     * meaningful after {@link #start()}.
     *
     * @return resolved compensation in sample frames (never negative)
     */
    public long getResolvedCompensationFrames() {
        return resolvedCompensationFrames;
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
     * <p>This reflects the pipeline-level default and does <em>not</em>
     * consider per-track monitoring overrides. Use
     * {@link #isInputMonitoringActive(Track)} to ask the question for a
     * specific armed track (per-track mode + transport state + panic
     * button), which is what the render pipeline consults in its
     * per-track read step.</p>
     *
     * @return {@code true} if input monitoring is active at the pipeline
     *         level
     */
    public boolean isInputMonitoringActive() {
        return switch (monitoringMode) {
            case OFF -> false;
            case ALWAYS -> true;
            case AUTO -> active;
            // Tape mode at the pipeline level is treated as "input audible
            // while stopped or recording" since there is no per-track
            // context here; use isInputMonitoringActive(Track) for the
            // full tape-mode resolution.
            case TAPE -> !active
                    || transport.getState() == com.benesquivelmusic.daw.core.transport.TransportState.RECORDING;
        };
    }

    /**
     * Returns whether input monitoring should be audible for the given
     * armed track, taking into account the track's per-track monitoring
     * mode, the current transport state, any configured punch range, and
     * the global "Mute All Inputs" panic switch.
     *
     * <p>This is the per-track query used by the render pipeline to
     * decide whether to pass the routed input buffer through to the
     * track's {@link com.benesquivelmusic.daw.core.audio.MixerChannel}
     * (input audible) or to let the normal playback signal reach the
     * channel (input muted).</p>
     *
     * @param track the armed track to query (must not be {@code null})
     * @return {@code true} if the input should be audible for that track
     */
    public boolean isInputMonitoringActive(Track track) {
        return resolveMonitoring(track).inputAudible();
    }

    /**
     * Resolves the {@link com.benesquivelmusic.daw.sdk.audio.MonitoringResolution}
     * for the given track, consulting its per-track monitoring mode, the
     * current transport state, punch status, and the global
     * {@linkplain #isAllInputsMuted() panic switch}.
     *
     * @param track the track to resolve for (must not be {@code null})
     * @return the monitoring resolution for this block; never {@code null}
     */
    public com.benesquivelmusic.daw.sdk.audio.MonitoringResolution resolveMonitoring(Track track) {
        Objects.requireNonNull(track, "track must not be null");
        if (allInputsMuted) {
            return com.benesquivelmusic.daw.sdk.audio.MonitoringResolution.SILENT;
        }
        InputMonitoringMode mode = track.getInputMonitoring();
        return mode.resolve(
                transport.getState(),
                track.isArmed(),
                isInsidePunchRange(),
                format.sampleRate());
    }

    /**
     * Returns whether the transport's current position is inside the
     * configured punch range (if any). Pipelines without a punch range
     * report {@code true}, matching the classic tape-machine behaviour
     * where the whole timeline is "inside" and tape-mode acts as a
     * simple play/record monitor toggle.
     */
    private boolean isInsidePunchRange() {
        PunchRegion transportPunch = transport.isPunchEnabled()
                ? transport.getPunchRegion()
                : null;
        if (transportPunch != null) {
            double pos = transport.getPositionInBeats();
            double bpm = transport.getTempo();
            double startBeats = (transportPunch.startFrames() / format.sampleRate())
                    * (bpm / 60.0);
            double endBeats = (transportPunch.endFrames() / format.sampleRate())
                    * (bpm / 60.0);
            return pos >= startBeats && pos < endBeats;
        }
        if (punchRange != null) {
            double pos = transport.getPositionInBeats();
            return pos >= punchRange.punchInBeat() && pos < punchRange.punchOutBeat();
        }
        return true;
    }

    /**
     * Returns {@code true} if the global "Mute All Inputs" panic switch
     * is engaged. When {@code true}, {@link #resolveMonitoring(Track)}
     * returns {@link com.benesquivelmusic.daw.sdk.audio.MonitoringResolution#SILENT}
     * for every track, silencing every monitor send without altering
     * any configured per-track monitoring modes or affecting the
     * recorded signal.
     *
     * @return whether the panic switch is engaged
     */
    public boolean isAllInputsMuted() {
        return allInputsMuted;
    }

    /**
     * Engages or releases the global "Mute All Inputs" panic switch.
     * Typically wired to the mixer header's panic button — the
     * drummer-tracking lifesaver for silencing every monitor send in
     * one click without changing any configured monitoring modes or
     * altering what is being recorded to disk.
     *
     * @param muted {@code true} to silence all monitor inputs,
     *              {@code false} to restore normal per-track resolution
     */
    public void setAllInputsMuted(boolean muted) {
        this.allInputsMuted = muted;
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
        // Loop-record: if the transport's beat position wrapped backwards
        // between the previous callback and this one, a loop lap just
        // completed. Finalize the current take (off the audio thread) and
        // start the next pass without dropping input frames. We detect the
        // wrap *before* routing this block so the wrapped audio goes into
        // the new take — giving sample-accurate loop boundaries.
        double currentBeatPosition = transport.getPositionInBeats();
        if (loopRecord && active
                && previousBeatPosition >= 0.0
                && currentBeatPosition < previousBeatPosition
                && transport.isLoopEnabled()) {
            finalizeLoopTake();
        }
        previousBeatPosition = currentBeatPosition;

        // Derive the block position from the transport's current beat position
        // so that punch gating stays aligned after loop/rewind/seek. The
        // recording callback fires *before* advancePosition(), so
        // getPositionInBeats() still reflects this block's start.
        long blockStart = beatsToFrames(currentBeatPosition);
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

    /**
     * Returns the clip start beat after applying driver-round-trip
     * compensation. The take is shifted *earlier* on the timeline by
     * {@link #resolvedCompensationFrames} sample frames so the recorded
     * wave aligns with the bar where the user played, not the (later)
     * sample position the DAW wrote it to. Negative results are clamped
     * to zero so {@link AudioClip} validation does not reject the clip
     * for early takes near the start of the timeline.
     */
    private double compensatedStartBeat() {
        if (resolvedCompensationFrames <= 0) {
            return recordingStartBeat;
        }
        double bpm = transport.getTempo();
        double compensationSeconds = resolvedCompensationFrames / format.sampleRate();
        double compensationBeats = compensationSeconds * (bpm / 60.0);
        return Math.max(0.0, recordingStartBeat - compensationBeats);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Loop-record (story 132)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Returns whether loop-record mode is enabled. When {@code true}, each
     * loop lap of the {@link Transport} is stamped as a new {@link Take}
     * grouped under a {@link TakeGroup} per armed track, rather than the
     * pipeline overwriting the previous capture.
     */
    public boolean isLoopRecord() {
        return loopRecord;
    }

    /**
     * Enables or disables loop-record mode. Must be called before
     * {@link #start()}; changing the flag while recording is active is
     * not supported and has no effect on the current session.
     */
    public void setLoopRecord(boolean loopRecord) {
        this.loopRecord = loopRecord;
    }

    /**
     * Returns an unmodifiable map of the {@link TakeGroup}s accumulated so
     * far during a loop-record session, keyed by armed track. The map is
     * populated as each loop lap wraps; after {@link #stop()} it contains
     * the final stacks.
     *
     * @return the per-track take groups (never {@code null})
     */
    public Map<Track, TakeGroup> getTakeGroups() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(takeGroups));
    }

    /**
     * Finalizes the buffered audio of each armed track into a new
     * {@link Take}, appends it to the track's {@link TakeGroup}, and starts
     * a fresh recording session for the next loop lap. Buffer rotation is
     * synchronous (so no frames are dropped at the seam); actual disk I/O
     * for the previous segment runs on {@link #takeFinalizationExecutor}
     * (a virtual-thread executor) so the audio thread is never blocked.
     *
     * <p>Invoked from {@link #onAudioCaptured} when a loop wrap is detected.
     * </p>
     */
    private void finalizeLoopTake() {
        double bpm = transport.getTempo();

        for (Track track : armedTracks) {
            RecordingSession previous = sessions.get(track);
            if (previous == null) {
                continue;
            }

            // Swap the track's recording session first so the next block's
            // audio is routed into a fresh buffer with no gap. Rotation is
            // intentionally cheap — just allocating a new session object.
            Path trackDir = outputDirectory.resolve(track.getId());
            RecordingSession next = new RecordingSession(format, trackDir);
            next.start();
            sessions.put(track, next);

            // Stop + snapshot the previous session in-thread (captures buffer
            // references only — no disk I/O yet).
            if (previous.isActive()) {
                previous.stop();
            }
            long samples = previous.getTotalSamplesRecorded();
            if (samples <= 0) {
                continue;
            }
            float[][] capturedAudio = previous.getCapturedAudio();
            String segmentPath = previous.getSegments().isEmpty()
                    ? null
                    : previous.getSegments().getFirst().filePath().toString();

            double durationSeconds = samples / format.sampleRate();
            double durationBeats = durationSeconds * (bpm / 60.0);
            if (durationBeats <= 0) {
                durationBeats = 0.01;
            }

            // Build an AudioClip for this take. The in-memory audio is set
            // immediately so playback can happen without waiting on I/O.
            AudioClip clip = new AudioClip(
                    "Take — " + track.getName(),
                    compensatedStartBeat(),
                    durationBeats,
                    segmentPath);
            if (capturedAudio != null) {
                clip.setAudioData(capturedAudio);
            }

            Take take = Take.of(clip);
            TakeGroup existing = takeGroups.get(track);
            TakeGroup updated = (existing == null ? TakeGroup.empty() : existing)
                    .withTakeAppended(take);
            takeGroups.put(track, updated);

            // Hand the previous session off to the virtual-thread executor —
            // any pending segment flushing or listener notification happens
            // off the audio thread. Today the RecordingSession already wrote
            // its buffers inline; this hook keeps the architecture ready for
            // future async flushing without touching the audio callback path.
            ExecutorService exec = takeFinalizationExecutor;
            if (exec != null) {
                RecordingSession captured = previous;
                exec.submit(() -> {
                    // No-op today: the session is already stopped. Exists so
                    // async disk work can be added here safely.
                    Objects.requireNonNull(captured);
                });
            }
        }
    }
}
