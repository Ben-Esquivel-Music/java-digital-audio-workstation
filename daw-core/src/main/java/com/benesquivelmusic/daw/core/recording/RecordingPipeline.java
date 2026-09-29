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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Orchestrates the recording pipeline by connecting the {@link AudioEngine},
 * {@link Transport}, and a per-track {@link RecordingSession} streaming real
 * WAV segments to disk (Recording Reliability book §4.1, Appendix A; story 323).
 *
 * <p><strong>Topology (book §2.3 — one writer per byte).</strong></p>
 * <pre>
 *   audio callback ──► CaptureRing (SPSC, preallocated) ──► "capture-flush" thread
 *   (claim, copy,       one slot per block, header stamped   (CaptureFlushService: route, gate,
 *    stamp, publish)    with frame/beat/punch/loop)           TrackCapture → RecordingSession →
 *                                                             SegmentWriter → segment-NNN.wav + take.manifest)
 * </pre>
 * The callback writes ring slots only. The flush thread writes files, the
 * manifest, the RAM mirrors and the ring's read index. The caller thread
 * (FX in the app) calls {@link #start()} and {@link #stop()}; {@code stop()}
 * mutates {@link Track}s ({@code addClip}, {@code putTakeGroup}) only after
 * the flush thread has been joined.</p>
 *
 * <p>The pipeline supports:</p>
 * <ul>
 *   <li><strong>Count-in</strong> — An audible metronome click for a configurable
 *       number of bars before recording starts (see {@link CountInMode}).</li>
 *   <li><strong>Input monitoring</strong> — Routes the audio input through the
 *       track's mixer channel so the performer can hear themselves in real time
 *       (see {@link InputMonitoringMode}).</li>
 *   <li><strong>Punch-in/punch-out</strong> — Records only within a specified
 *       beat range on the timeline (see {@link PunchRange}), or sample-accurately
 *       within the transport's frame-based {@link PunchRegion}. The gating math
 *       runs on the flush thread from each block's header snapshot.</li>
 *   <li><strong>Loop-record</strong> — Each loop lap becomes a {@link Take} in a
 *       {@link TakeGroup} per armed track; wrap detection and lap finalisation
 *       run on the flush thread.</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <ol>
 *   <li>Call {@link #start()} to validate armed tracks, create the ring and the
 *       per-track sessions (files!), start the flush thread, wire the audio
 *       engine's recording callback, and start the transport. This is
 *       all-or-nothing: any failure rolls everything back — thread stopped,
 *       files and manifest deleted, recording flags cleared — and rethrows.</li>
 *   <li>Audio data flows from {@link AudioEngine#processBlock} through the
 *       recording callback into the ring, and from there through the flush
 *       thread into each track's session.</li>
 *   <li>Call {@link #stop()} to remove the callback, stop the transport, seal
 *       every segment and the manifest (bounded join), then create
 *       {@link AudioClip}s referencing <em>every</em> sealed segment of the
 *       take in manifest order.</li>
 * </ol>
 *
 * <p>The {@code outputDirectory} passed to the constructor is the take
 * directory (in the app: {@code <project>/audio/takes/<stamp>_take-NNNN},
 * allocated by {@link TakeDirectories}); segments land under
 * {@code <take>/<trackId>/}, the manifest at {@code <take>/take.manifest}.</p>
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
    private final Map<Track, TrackCapture> captures = new LinkedHashMap<>();
    private final Map<Track, AudioClip> recordedClips = new LinkedHashMap<>();
    private final Map<Track, Long> trackCompensationFrames = new LinkedHashMap<>();

    private CaptureRing ring;
    private CaptureFlushService flush;
    /** Armed tracks recording their graph instrument; ring source {@code i + 1} — preallocated for the callback. */
    private Track[] instrumentTracks = new Track[0];
    private boolean loopRecord;
    private volatile boolean active;
    private boolean allInputsMuted;
    private double recordingStartBeat;
    private long recordingStartFrame;

    private Duration maxSegmentDuration = RecordingSession.DEFAULT_MAX_SEGMENT_DURATION;
    private long maxSegmentBytes = RecordingSession.DEFAULT_MAX_SEGMENT_BYTES;
    private Duration forceCadence = SegmentWriter.DEFAULT_FORCE_CADENCE;
    private LongSupplier nanoClock = System::nanoTime;
    private Consumer<String> warningSink;
    private DiskHeadroomWatch diskHeadroomWatch;
    private int ringSlots;
    private SegmentWriter.ChannelOpener channelOpener = SegmentWriter.CREATE_NEW_CHANNEL;
    private TrackCapture.SessionFactory sessionFactory = this::newSession;
    /** Test seam ({@link #setRollbackFault}); run inside the start rollback. */
    private Runnable rollbackFault;

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
     * @param outputDirectory the take directory for recording segment files
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
     * @param outputDirectory the take directory for recording segment files
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
     * Starts the recording pipeline — all or nothing. In order: capture the
     * anchor position, flag the armed tracks, resolve latency compensation,
     * allocate the {@link CaptureRing}, snapshot each track's routing into a
     * {@link TrackCapture}, start the {@link CaptureFlushService} (sessions
     * open their first segment files, the initial manifest is written, the
     * flush thread starts), wire the recording callback, start the audio
     * engine, and transition the transport to recording. If any step fails
     * after {@code active} was set, everything is rolled back in reverse
     * order — callback removed, thread stopped, files and manifest deleted,
     * recording flags cleared, {@code active = false} — and the failure is
     * rethrown, carrying anything the rollback itself threw as suppressed.
     * The rollback reaches only the flush service and captures this start
     * created, never those of an earlier take of the same pipeline. Caller
     * thread.
     *
     * @throws IllegalStateException    if the pipeline is already active
     * @throws java.io.UncheckedIOException if the take directory, a segment or
     *                                  the manifest cannot be created
     * @throws IllegalArgumentException if the format's bit depth is not 16, 24 or 32
     */
    public void start() {
        if (active) {
            throw new IllegalStateException("Recording pipeline is already active");
        }
        active = true;
        try {
            doStart();
        } catch (RuntimeException | Error e) {
            rollbackFailedStart(e);
            throw e;
        }
    }

    private void doStart() {
        // Everything per-take is reset before anything is allocated, so a
        // failure further down can only ever roll back what THIS start
        // created — never the previous take's service, ring or files.
        flush = null;
        ring = null;
        instrumentTracks = new Track[0];
        recordedClips.clear();
        trackCompensationFrames.clear();
        captures.clear();

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
        recordingStartFrame = beatsToFrames(recordingStartBeat);

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

        // Ring sources: 0 = the device block; one more per armed track that
        // records its graph instrument (that engine buffer is only valid
        // inside the callback, so the callback copies it into the ring).
        // Source rows cover the stream width AND every armed routing's
        // highest input channel: a device may deliver more input channels
        // than the format's (output) width, and the callback used to read
        // them straight from the delivered buffer — the ring must not lose
        // them. This is the armed set's live requirement, not a captured
        // constant (book §9.9); story 326 owns the routing width proper.
        List<Track> instruments = new ArrayList<>();
        int sourceRows = format.channels();
        for (Track track : armedTracks) {
            InputRouting routing = track.getInputRouting();
            if (routing.isNone()) {
                instruments.add(track);
            } else {
                sourceRows = Math.max(sourceRows, routing.firstChannel() + routing.channelCount());
            }
        }
        instrumentTracks = instruments.toArray(new Track[0]);
        int slots = ringSlots > 0
                ? ringSlots
                : CaptureRing.slotCountFor(format.sampleRate(), format.bufferSize(),
                        CaptureRing.DEFAULT_HANDOFF_TOLERANCE);
        ring = new CaptureRing(format.bufferSize(), sourceRows, 1 + instrumentTracks.length, slots);

        // One TrackCapture per armed track: routing snapshot, routed scratch,
        // compensation, and an unstarted lane-0 session.
        double tempo = transport.getTempo();
        int instrumentIndex = 0;
        for (Track track : armedTracks) {
            InputRouting routing = track.getInputRouting();
            boolean instrument = routing.isNone();
            // Graph instruments are captured at their internal render timestamp,
            // before hardware output. Neither input nor output-cue latency has
            // elapsed in these buffers; physical capture retains round-trip alignment.
            long compensation = instrument && audioEngine.hasGraphInstrument(track)
                    ? 0L : resolvedCompensationFrames;
            trackCompensationFrames.put(track, compensation);
            int source = instrument ? 1 + instrumentIndex++ : -1;
            int routedChannels = instrument ? format.channels() : routing.channelCount();
            captures.put(track, new TrackCapture(track, routing, source, routedChannels,
                    format.bufferSize(), compensation, compensatedStartBeat(track),
                    format.sampleRate(), tempo, outputDirectory.resolve(track.getId()), sessionFactory));
        }

        DiskHeadroomWatch watch = diskHeadroomWatch != null
                ? diskHeadroomWatch
                : DiskHeadroomWatch.forDirectory(outputDirectory, warningSink);
        CaptureFlushService.TakeConfig config = new CaptureFlushService.TakeConfig(
                outputDirectory, format, tempo, recordingStartBeat, recordingStartFrame,
                punchRange, loopRecord, forceCadence, Instant.now());
        flush = new CaptureFlushService(ring, config, List.copyOf(captures.values()), watch,
                warningSink, nanoClock);
        flush.start();

        // Wire the recording callback on the audio engine
        audioEngine.setRecordingCallback(this::onAudioCaptured);

        // Start the audio engine if it is not already running
        audioEngine.start();

        // Transition transport to recording
        transport.record();
    }

    /**
     * Undoes a failed {@link #start()}. Every step runs even if an earlier
     * one throws; whatever the steps throw is attached to {@code cause} as
     * suppressed, so the caller sees the failure that ended the start, and
     * the pipeline always ends inactive. {@code ring} and
     * {@code instrumentTracks} are left as they are: a callback the audio
     * thread loaded before it was removed may still be running.
     */
    private void rollbackFailedStart(Throwable cause) {
        try {
            rollbackStep(cause, () -> audioEngine.setRecordingCallback(null));
            Runnable fault = rollbackFault;
            if (fault != null) {
                rollbackStep(cause, fault);
            }
            rollbackStep(cause, () -> {
                if (flush != null) {
                    // Created by this start. Kept reachable (dead) through
                    // getCaptureFlushService() so a caller can verify the
                    // thread is gone; the next start replaces it.
                    flush.abortStart();
                } else {
                    for (TrackCapture capture : captures.values()) {
                        capture.discardAllFiles();
                    }
                }
            });
            rollbackStep(cause, captures::clear);
            for (Track track : armedTracks) {
                rollbackStep(cause, () -> track.setRecording(false));
            }
        } finally {
            active = false;
        }
    }

    private static void rollbackStep(Throwable cause, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException | Error stepFailure) {
            if (stepFailure != cause) {
                cause.addSuppressed(stepFailure);
            }
        }
    }

    /**
     * The default session factory: every lane's session carries the
     * pipeline's segment limits, force cadence, clock and channel opener to
     * its writers.
     */
    private RecordingSession newSession(Track track, Path trackDirectory) {
        RecordingSession session = new RecordingSession(format, trackDirectory, maxSegmentDuration,
                maxSegmentBytes, forceCadence, nanoClock);
        session.setChannelOpener(channelOpener);
        return session;
    }

    /**
     * Stops the recording pipeline: removes the recording callback, clears
     * the recording flags, stops the transport, seals every segment and the
     * manifest on the flush thread (bounded join), then creates audio clips
     * on armed tracks referencing every sealed segment in manifest order.
     * Idempotent: a second call returns an empty list. Caller thread.
     *
     * <p>Removing the callback does not wait for the audio thread: a block
     * whose callback was already running when it was removed may be
     * published after the flush thread's final sweep. That one block stays
     * in the ring and is not part of the take — the loss at Stop is bounded
     * to the single block in flight (story 325 owns a drained-callback
     * handshake).</p>
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

        // Stop the transport (story 315: this returns the playhead to the
        // record-start anchor, per Transport.isReturnToStartOnStop()).
        transport.stop();

        // Seal on the flush thread: final sweep, every lane sealed (in
        // loop-record the in-flight lap becomes the last take), manifest
        // sealed. Returns after the bounded join.
        flush.stopAndSeal(TakeManifest.SealedBy.STOP);

        // The take ends the way it began: the mode the flush thread worked
        // from, not whatever the field holds now.
        boolean loopRecorded = flush.config().loopRecord();

        // Build clips on the caller thread using the captured start position
        List<AudioClip> clips = new ArrayList<>();

        for (Track track : armedTracks) {
            TrackCapture capture = captures.get(track);
            if (capture == null) {
                continue;
            }

            // In loop-record mode the takes were stacked on the flush thread.
            // Expose the active take's clip on the track lane and attach the
            // group to the track.
            if (loopRecorded) {
                TakeGroup group = capture.takeGroup();
                if (!group.isEmpty()) {
                    AudioClip activeClip = group.activeClip();
                    track.addClip(activeClip);
                    track.putTakeGroup(group);
                    recordedClips.put(track, activeClip);
                    clips.add(activeClip);
                }
                continue;
            }

            RecordingSession session = capture.session();
            if (session != null && session.getTotalSamplesRecorded() > 0) {
                double durationSeconds = session.getTotalSamplesRecorded() / format.sampleRate();
                double durationBeats = durationSeconds * (transport.getTempo() / 60.0);
                if (durationBeats <= 0) {
                    durationBeats = 0.01;
                }
                List<String> segmentPaths = capture.sealedSegmentPaths();

                AudioClip clip = new AudioClip(
                        "Recording — " + track.getName(),
                        compensatedStartBeat(track),
                        durationBeats,
                        segmentPaths.isEmpty() ? null : segmentPaths.getFirst());
                clip.setSourceSegmentPaths(segmentPaths);

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
     * Returns the recording session for the given track (its current lane
     * while recording, its last lane after {@link #stop()}), or {@code null}
     * before {@link #start()}.
     *
     * @param track the track
     * @return the recording session, or {@code null}
     */
    public RecordingSession getSession(Track track) {
        TrackCapture capture = captures.get(track);
        return capture == null ? null : capture.session();
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

    /**
     * Returns the frame position of {@link #getRecordingStartBeat()} at the
     * tempo current when {@link #start()} ran — the manifest's {@code start-frame}.
     */
    public long getRecordingStartFrame() {
        return recordingStartFrame;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Take directory, flush seams and limits
    // ─────────────────────────────────────────────────────────────────────

    /** Returns the take directory segments and the manifest are written under. */
    public Path getTakeDirectory() {
        return outputDirectory;
    }

    /** Returns the {@code take.manifest} path inside the take directory. */
    public Path getTakeManifestPath() {
        return TakeManifest.manifestPath(outputDirectory);
    }

    /**
     * Blocks until every block published to the ring before this call has
     * been applied by the flush thread, bounded by
     * {@link CaptureFlushService#DEFAULT_AWAIT_TIMEOUT}. Test/diagnostic
     * seam. Any thread but the flush thread.
     *
     * @throws IllegalStateException if the pipeline was never started, the
     *                               bound elapses, or the flush thread has
     *                               stopped with blocks unapplied
     */
    public void awaitFlushed() {
        awaitFlushed(CaptureFlushService.DEFAULT_AWAIT_TIMEOUT);
    }

    /**
     * Blocks until every block published before this call has been applied,
     * or {@code timeout} elapses.
     *
     * @see #awaitFlushed()
     */
    public void awaitFlushed(Duration timeout) {
        CaptureFlushService service = flush;
        if (service == null || (!active && !service.isSealed())) {
            throw new IllegalStateException("Recording pipeline has not been started");
        }
        service.awaitFlushed(timeout);
    }

    /**
     * Returns how many incoming blocks the callback dropped because the ring
     * was full, in the current take or in the last one until the next
     * {@link #start()}; {@code 0} while the pipeline holds no ring (see
     * {@link #getCaptureRing()}).
     */
    public long getOverflowCount() {
        CaptureRing current = ring;
        return current == null ? 0L : current.overflowCount();
    }

    /**
     * Returns how many delivered frames the callback had to cut off because
     * their block was longer than a ring slot ({@code format.bufferSize()}
     * frames), in the current take or in the last one until the next
     * {@link #start()}; {@code 0} while the pipeline holds no ring (see
     * {@link #getCaptureRing()}). Any thread.
     */
    public long getTruncatedFrames() {
        CaptureRing current = ring;
        return current == null ? 0L : current.truncatedFrames();
    }

    /**
     * Sets the segment rotation caps (defaults 30 min / 500 MB). Must be
     * called before {@link #start()}.
     *
     * @param maxSegmentDuration rotate when a segment holds this much audio; positive
     * @param maxSegmentBytes    rotate when a segment's data chunk reaches this size, and
     *                           before an append would take it past this size; positive and
     *                           at most {@link SegmentWriter#MAX_DATA_BYTES}
     * @throws IllegalArgumentException if a cap is out of range
     * @throws IllegalStateException    if the pipeline is recording
     */
    public void setSegmentLimits(Duration maxSegmentDuration, long maxSegmentBytes) {
        requireInactive("segment limits");
        Objects.requireNonNull(maxSegmentDuration, "maxSegmentDuration must not be null");
        if (maxSegmentDuration.isZero() || maxSegmentDuration.isNegative()) {
            throw new IllegalArgumentException("maxSegmentDuration must be positive: " + maxSegmentDuration);
        }
        long byteCap = RecordingSession.requireSegmentByteCap(maxSegmentBytes);
        this.maxSegmentDuration = maxSegmentDuration;
        this.maxSegmentBytes = byteCap;
    }

    /** Returns the segment duration cap. */
    public Duration getMaxSegmentDuration() {
        return maxSegmentDuration;
    }

    /** Returns the segment byte cap. */
    public long getMaxSegmentBytes() {
        return maxSegmentBytes;
    }

    /**
     * Sets the writers' force-to-storage cadence (default
     * {@link SegmentWriter#DEFAULT_FORCE_CADENCE}, 5 s). Must be called
     * before {@link #start()}.
     */
    public void setForceCadence(Duration forceCadence) {
        requireInactive("force cadence");
        Objects.requireNonNull(forceCadence, "forceCadence must not be null");
        if (forceCadence.isNegative()) {
            throw new IllegalArgumentException("forceCadence must not be negative: " + forceCadence);
        }
        this.forceCadence = forceCadence;
    }

    /** Returns the force cadence the sessions' writers use. */
    public Duration getForceCadence() {
        return forceCadence;
    }

    /**
     * Sets the monotonic clock the writers' force cadence, the headroom
     * watch and the manifest retry interval read (default
     * {@code System::nanoTime}). Test/diagnostic seam; must be called before
     * {@link #start()}.
     */
    public void setNanoClock(LongSupplier nanoClock) {
        requireInactive("nano clock");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock must not be null");
    }

    /**
     * Sets the sink for user-facing warnings (ring overflow, block
     * truncation, low disk, disk exhaustion, early seal, a segment that
     * could not be sealed, an unwritable manifest, a flush-loop failure, a
     * slow finalisation); {@code null} logs at WARNING. Called on the
     * {@code capture-flush} thread, and on the caller thread for the
     * join-timeout warning of a stop or of a failed start's rollback; a sink
     * that touches the UI must marshal itself. A sink that throws a
     * {@link RuntimeException} is logged and ignored; it can never end a
     * take. Story 339 injects the production notification seam. Must be
     * called before {@link #start()}.
     */
    public void setWarningSink(Consumer<String> warningSink) {
        requireInactive("warning sink");
        this.warningSink = warningSink;
    }

    /** Injects the disk-headroom watch the flush thread ticks (default: the take directory's file store). */
    void setDiskHeadroomWatch(DiskHeadroomWatch watch) {
        requireInactive("disk headroom watch");
        this.diskHeadroomWatch = watch;
    }

    /**
     * Requests a ring of at least {@code slots} slots (0 = the
     * format-derived default). A request the ring refuses — more than
     * {@link CaptureRing#MAX_SLOTS} — is accepted here and fails the next
     * {@link #start()} at the ring allocation.
     */
    void setRingSlots(int slots) {
        requireInactive("ring size");
        if (slots < 0) {
            throw new IllegalArgumentException("slots must not be negative: " + slots);
        }
        this.ringSlots = slots;
    }

    /** Replaces how per-lane sessions are created (start-failure tests). */
    void setSessionFactory(TrackCapture.SessionFactory factory) {
        requireInactive("session factory");
        this.sessionFactory = Objects.requireNonNull(factory, "factory must not be null");
    }

    /**
     * Replaces how every segment gets its channel in the sessions the
     * pipeline's own factory builds (test seam: a delegating channel makes
     * the writers' {@code force} calls observable). A factory installed
     * with {@link #setSessionFactory} builds its sessions itself and is not
     * affected. Must be called before {@link #start()}.
     *
     * @throws IllegalStateException if the pipeline is recording
     */
    void setChannelOpener(SegmentWriter.ChannelOpener opener) {
        requireInactive("channel opener");
        this.channelOpener = Objects.requireNonNull(opener, "opener must not be null");
    }

    /**
     * Fault seam (test-only): {@code fault} runs inside the rollback of a
     * failed {@link #start()}, right after the recording callback was
     * removed, so a test can make the rollback itself throw. {@code null}
     * removes it.
     */
    void setRollbackFault(Runnable fault) {
        this.rollbackFault = fault;
    }

    /**
     * Returns the flush service of the current take, or of the last take
     * until the next {@link #start()}; {@code null} before the first start
     * and after a start that failed before it created one.
     */
    CaptureFlushService getCaptureFlushService() {
        return flush;
    }

    /**
     * Returns the capture ring of the current take, or of the last take
     * until the next {@link #start()}; {@code null} before the first start
     * and after a start that failed before it allocated one.
     */
    CaptureRing getCaptureRing() {
        return ring;
    }

    private void requireInactive(String what) {
        if (active) {
            throw new IllegalStateException("cannot change the " + what + " while recording");
        }
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

    /**
     * The recording callback — audio thread. Does exactly one thing: hands
     * the block to the ring. Claim a slot (a {@code null} claim is a counted
     * overflow and an immediate return — the callback never blocks), copy
     * the device block and each armed graph-instrument buffer (only valid
     * inside this callback), stamp the header with the transport's beat
     * position, the derived start frame, the punch snapshot and the loop
     * flag, publish, and wake the flush thread. No allocation, no locks, no
     * collection iteration beyond the preallocated instrument-track array.
     * Routing, gating, wrap detection and every file write happen on the
     * flush thread from this header.
     *
     * <p><strong>Block length.</strong> The engine delivers
     * {@code numFrames} frames per call; this pipeline's ring slots hold
     * {@code format.bufferSize()} frames, the size it was constructed with.
     * Nothing guarantees {@code numFrames <= format.bufferSize()} — the
     * live stream's block size is the engine's, not the pipeline's — so a
     * longer block is not an error here: its first
     * {@code format.bufferSize()} frames are captured, and the excess is
     * stamped on the slot and counted on the ring
     * ({@link #getTruncatedFrames()}) for the flush thread to record in the
     * manifest and report. A shorter block is captured as delivered.</p>
     *
     * <p>A callback the audio thread loaded before {@link #stop()} or a
     * failed {@link #start()} removed it may still run once, and must not
     * fail: it returns at once when the pipeline holds no ring or no flush
     * service (a restart that failed early), and it never addresses a ring
     * source the slot does not have.</p>
     */
    private void onAudioCaptured(float[][] inputBuffer, int numFrames) {
        CaptureRing currentRing = ring;
        CaptureFlushService currentFlush = flush;
        if (currentRing == null || currentFlush == null) {
            return;
        }
        CaptureRing.Slot slot = currentRing.claim();
        if (slot == null) {
            return;
        }
        slot.setNumFrames(numFrames);
        int excess = numFrames - slot.slotFrames();
        if (excess > 0) {
            slot.setTruncatedFrames(excess);
            currentRing.noteTruncatedFrames(excess);
        }
        slot.copySource(0, inputBuffer, inputBuffer.length, numFrames);
        Track[] instruments = instrumentTracks;
        int instrumentSources = Math.min(instruments.length, slot.sourceCount() - 1);
        for (int i = 0; i < instrumentSources; i++) {
            float[][] instrument = audioEngine.graphInstrumentRecordingBuffer(instruments[i]);
            if (instrument == null) {
                slot.clearSource(i + 1);
            } else {
                slot.copySource(i + 1, instrument, instrument.length, numFrames);
            }
        }

        // The recording callback fires *before* advancePosition(), so
        // getPositionInBeats() still reflects this block's start.
        double beat = transport.getPositionInBeats();
        slot.setBeatPosition(beat);
        slot.setStartFrame(beatsToFrames(beat));
        PunchRegion punch = transport.isPunchEnabled() ? transport.getPunchRegion() : null;
        if (punch != null) {
            slot.setPunchEnabled(true);
            slot.setPunchStartFrames(punch.startFrames());
            slot.setPunchEndFrames(punch.endFrames());
        } else {
            slot.setPunchEnabled(false);
        }
        slot.setLoopEnabled(transport.isLoopEnabled());
        currentRing.publish();
        currentFlush.signal();
    }

    private long beatsToFrames(double beats) {
        double bpm = transport.getTempo();
        double seconds = beats * 60.0 / bpm;
        return Math.round(seconds * format.sampleRate());
    }

    /**
     * Returns the clip start beat after applying driver-round-trip
     * compensation. The take is shifted *earlier* on the timeline by
     * the source-specific compensation captured at session start so the recorded
     * wave aligns with the bar where the user played, not the (later)
     * sample position the DAW wrote it to. Negative results are clamped
     * to zero so {@link AudioClip} validation does not reject the clip
     * for early takes near the start of the timeline.
     */
    private double compensatedStartBeat(Track track) {
        long compensationFrames = trackCompensationFrames.getOrDefault(track, 0L);
        if (compensationFrames <= 0) {
            return recordingStartBeat;
        }
        double bpm = transport.getTempo();
        double compensationSeconds = compensationFrames / format.sampleRate();
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
     * {@link #start()}: the mode is part of the take's configuration, and a
     * change while recording is active is refused.
     *
     * @throws IllegalStateException if the pipeline is recording
     */
    public void setLoopRecord(boolean loopRecord) {
        requireInactive("loop-record mode");
        this.loopRecord = loopRecord;
    }

    /**
     * Returns an unmodifiable map of the {@link TakeGroup}s accumulated so
     * far during a loop-record session, keyed by armed track. The map is
     * populated as each loop lap wraps (on the flush thread; the values are
     * immutable snapshots); after {@link #stop()} it contains the final stacks.
     *
     * @return the per-track take groups (never {@code null})
     */
    public Map<Track, TakeGroup> getTakeGroups() {
        Map<Track, TakeGroup> groups = new LinkedHashMap<>();
        for (Map.Entry<Track, TrackCapture> entry : captures.entrySet()) {
            TakeGroup group = entry.getValue().takeGroup();
            if (!group.isEmpty()) {
                groups.put(entry.getKey(), group);
            }
        }
        return Collections.unmodifiableMap(groups);
    }
}
