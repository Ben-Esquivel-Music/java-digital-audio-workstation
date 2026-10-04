package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;
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
 * The callback writes ring slots only. The flush thread creates, writes and
 * deletes the take's segment files and its manifest, and writes the
 * sessions' staging blocks and the ring's read index; no thread keeps a copy
 * of the captured audio in memory. The caller thread (FX in the app) runs
 * the take's lifecycle — {@link #prepare()}, {@link #beginCapture()} or
 * {@link #cancelStart()}, then {@link #requestStop()} and
 * {@link #completeStop()} — and none of those touches storage or waits for
 * the flush thread: what that thread does is reported through signals a
 * holder cannot complete (the take's readiness, the thread's termination,
 * {@link #earlySeal()}). {@code completeStop()} reads what the flush thread
 * wrote, and adds clips and take groups to {@link Track}s ({@code addClip},
 * {@code putTakeGroup}), only once the flush thread has terminated; until
 * then the pipeline stays
 * {@linkplain #isFinalizationPending() finalization pending}. When the flush
 * thread seals the take on its own — disk exhaustion, a write failure — it
 * completes {@link #earlySeal()} and does nothing more: the callback stays
 * installed, the transport keeps its state and every later block is
 * discarded until the caller's {@code requestStop()}.</p>
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
 *   <li>Call {@link #prepare()} to read the take's anchor, flag the armed
 *       tracks, create the ring and the per-track captures, and start the
 *       flush thread, which creates the take's files — the take directory,
 *       each armed track's first segment, the initial manifest — and then
 *       completes the readiness stage {@code prepare()} returned. Nothing is
 *       captured yet: capture must not begin before the files exist, because
 *       by default the ring holds only the blocks that cover
 *       {@link CaptureRing#DEFAULT_HANDOFF_TOLERANCE} (250 ms) of audio,
 *       rounded up to a power-of-two slot count of at least
 *       {@link CaptureRing#MIN_SLOTS} (with 256-frame blocks at 48 kHz: 47
 *       blocks, rounded up to 64 slots, about 341 ms), and a slow disk would
 *       overflow it while the files were still being created.</li>
 *   <li>Once readiness has completed normally, call {@link #beginCapture()}
 *       to wire the audio engine's recording callback, start the engine and
 *       put the transport in recording. Otherwise — readiness failed, or the
 *       user cancelled meanwhile — call {@link #cancelStart()}: the flush
 *       thread deletes the segment and manifest files it created for the
 *       take, and each track directory it created, if that leaves it empty
 *       (best-effort: what an I/O error keeps from being deleted is left and
 *       the error logged);
 *       the take directory — even one it had to create — is left for its
 *       caller. A start is all-or-nothing: a {@code beginCapture()} that
 *       fails is rolled back the same way, and rethrows. (If a throwable
 *       that escaped that thread's drain loop had already sealed the take
 *       early and ended the thread, either discard deletes nothing and that
 *       take stays as it was sealed: {@link #cancelStart()}.) Either way the
 *       next {@link #prepare()} of the same pipeline is refused until that
 *       thread has marked itself terminated
 *       ({@link CaptureFlushService#isTerminated()}; {@link #termination()}
 *       completes right after).</li>
 *   <li>Audio data flows from {@link AudioEngine#processBlock} through the
 *       recording callback into the ring, and from there through the flush
 *       thread into each track's session.</li>
 *   <li>Call {@link #requestStop()} to remove the callback, clear the
 *       recording flags, stop the transport and ask the flush thread to seal
 *       every segment and the manifest; it returns the flush thread's
 *       termination signal at once. Once that signal has completed, call
 *       {@link #completeStop()}, which creates {@link AudioClip}s
 *       referencing <em>every</em> sealed segment of the take in manifest
 *       order.</li>
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
    /** Rate of the project timeline's frame-based punch coordinates. */
    private final double projectSampleRate;
    private final Path outputDirectory;
    private final List<Track> armedTracks;
    private final CountInMode countInMode;
    private final InputMonitoringMode monitoringMode;
    private final PunchRange punchRange;
    private final Map<Track, TrackCapture> captures = new LinkedHashMap<>();
    private final Map<Track, AudioClip> recordedClips = new LinkedHashMap<>();
    private final Map<Track, Long> trackCompensationFrames = new LinkedHashMap<>();

    /**
     * The current take's ring and flush service, or the last take's until
     * the next {@link #prepare()}. Written on the caller thread; volatile
     * for the getters any thread may call. The audio thread reads neither:
     * each take's {@link CaptureCallback} carries its own.
     */
    private volatile CaptureRing ring;
    private volatile CaptureFlushService flush;
    /**
     * Armed tracks recording their graph instrument; ring source
     * {@code i + 1}. Caller thread only: {@link #beginCapture()} hands the
     * take's {@link CaptureCallback} a copy.
     */
    private Track[] instrumentTracks = new Track[0];
    /**
     * The tempo read when {@link #prepare()} ran — the one the take's anchor
     * frame is computed at, and the one the take's {@link CaptureCallback}
     * converts each block's beat position to a start frame with.
     */
    private double takeTempoBpm;
    private boolean loopRecord;
    /**
     * A take is being prepared: set by {@link #prepare()}, cleared by
     * {@link #beginCapture()}, {@link #cancelStart()} and the rollback of a
     * failed start.
     */
    private volatile boolean preparing;
    private volatile boolean active;
    /**
     * A {@link #requestStop()} has begun and {@link #completeStop()} has not
     * yet returned the take's clips: set before the stop's one-shot side
     * effects, cleared when the clips are built.
     */
    private volatile boolean finalizationPending;
    /**
     * The tempo the take's clips are built at: read by the
     * {@link #requestStop()} that began the finalisation, so the
     * {@link #completeStop()} that completes it builds the clips of the take
     * as it was stopped, even if the tempo has changed since.
     */
    private double clipTempoBpm;
    /** The lookup of {@link #completeStop()}: no clip has audio that was read back. */
    private static final Function<List<String>, float[][]> NO_RECORDED_AUDIO = segmentPaths -> null;
    private boolean allInputsMuted;
    private double recordingStartBeat;
    private long recordingStartFrame;
    /**
     * The transport's position when {@link #prepare()} read the anchor;
     * {@link #beginCapture()} puts the transport back there if it moved while
     * the take was being prepared and the transport is not rolling — stopped
     * or paused — when capture begins.
     */
    private double preparedPositionBeats;

    private Duration maxSegmentDuration = RecordingSession.DEFAULT_MAX_SEGMENT_DURATION;
    private long maxSegmentBytes = RecordingSession.DEFAULT_MAX_SEGMENT_BYTES;
    private Duration forceCadence = SegmentWriter.DEFAULT_FORCE_CADENCE;
    private LongSupplier nanoClock = System::nanoTime;
    private Consumer<String> warningSink;
    /** Where the lanes' peak snapshots go; {@code null} until one is set — no snapshot is built then. */
    private Consumer<CapturePeakSnapshot> peakSnapshotSink;
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
     * Resolved compensation frames captured at {@link #prepare()} so the
     * value cannot drift mid-session if the user toggles the dialog or
     * the device re-reports its latency. {@code 0} means no compensation
     * is applied to recorded clip start positions.
     */
    private long resolvedCompensationFrames;

    /**
     * Creates a new recording pipeline with default settings (no count-in,
     * monitoring off, no punch range).
     * The project's punch coordinates are assumed to use the stream rate;
     * use the overload accepting {@code projectSampleRate} when they differ.
     *
     * @param audioEngine     the audio engine providing input audio
     * @param transport       the transport controlling playback/recording state
     * @param format          the format the engine is streaming — the stream's,
     *                        not the project's, where the two differ. The
     *                        take is built from it: each ring slot holds its
     *                        {@code bufferSize} frames, so a delivered block
     *                        that is longer is truncated to that (counted,
     *                        and recorded in the manifest); the segment
     *                        headers, the rate each clip declares, the clip
     *                        lengths and every frame position are computed
     *                        at its sample rate
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
     * The project's punch coordinates are assumed to use the stream rate;
     * use the overload accepting {@code projectSampleRate} when they differ.
     *
     * @param audioEngine     the audio engine providing input audio
     * @param transport       the transport controlling playback/recording state
     * @param format          the format the engine is streaming — the stream's,
     *                        not the project's, where the two differ. The
     *                        take is built from it: each ring slot holds its
     *                        {@code bufferSize} frames, so a delivered block
     *                        that is longer is truncated to that (counted,
     *                        and recorded in the manifest); the segment
     *                        headers, the rate each clip declares, the clip
     *                        lengths and every frame position are computed
     *                        at its sample rate
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
        this(audioEngine, transport, format,
                Objects.requireNonNull(format, "format must not be null").sampleRate(),
                outputDirectory, armedTracks, countInMode, monitoringMode, punchRange);
    }

    /**
     * Creates a pipeline with default settings and an explicit project rate.
     *
     * @param audioEngine     the audio engine providing input audio
     * @param transport       the transport controlling playback/recording state
     * @param format          the live stream format used for ring slots, segments and clip metadata
     * @param projectSampleRate the project timeline rate at which transport punch frames are expressed
     * @param outputDirectory the take directory for recording segment files
     * @param armedTracks     the tracks armed for recording (must not be empty)
     */
    public RecordingPipeline(AudioEngine audioEngine, Transport transport,
                             AudioFormat format, double projectSampleRate, Path outputDirectory,
                             List<Track> armedTracks) {
        this(audioEngine, transport, format, projectSampleRate, outputDirectory, armedTracks,
                CountInMode.OFF, InputMonitoringMode.OFF, null);
    }

    /**
     * Creates a pipeline with full configuration and an explicit project rate.
     * The take's frame positions use the stream's rate; transport punch bounds
     * are converted from the project's rate when preparing the anchor and
     * stamping each block, so live punch edits keep their timeline times.
     *
     * @param audioEngine     the audio engine providing input audio
     * @param transport       the transport controlling playback/recording state
     * @param format          the live stream format used for ring slots, segments and clip metadata
     * @param projectSampleRate the project timeline rate at which transport punch frames are expressed;
     *                          finite and positive
     * @param outputDirectory the take directory for recording segment files
     * @param armedTracks     the tracks armed for recording (must not be empty)
     * @param countInMode     the count-in mode
     * @param monitoringMode  the input monitoring mode
     * @param punchRange      the beat-based punch range, or {@code null}
     */
    public RecordingPipeline(AudioEngine audioEngine, Transport transport,
                             AudioFormat format, double projectSampleRate, Path outputDirectory,
                             List<Track> armedTracks,
                             CountInMode countInMode,
                             InputMonitoringMode monitoringMode,
                             PunchRange punchRange) {
        this.audioEngine = Objects.requireNonNull(audioEngine, "audioEngine must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.format = Objects.requireNonNull(format, "format must not be null");
        if (!(projectSampleRate > 0) || !Double.isFinite(projectSampleRate)) {
            throw new IllegalArgumentException("projectSampleRate must be finite and positive: " + projectSampleRate);
        }
        this.projectSampleRate = projectSampleRate;
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
     * Prepares a take — the first half of a start. Caller thread (FX in the
     * app); no storage I/O and no waiting. In order: capture the anchor
     * position, flag the armed tracks, resolve latency compensation,
     * allocate the {@link CaptureRing}, snapshot each track's routing into a
     * {@link TrackCapture} (with an unstarted lane-0 session), and construct
     * and start the take's {@link CaptureFlushService}. The
     * {@code capture-flush} thread then creates the take's files on its own —
     * the take directory, each armed track's {@code segment-000.wav.part},
     * the initial manifest — and the returned stage reports the outcome.
     * Meanwhile the pipeline is {@linkplain #isPreparing() preparing}: the
     * recording callback is not installed, and neither the engine nor the
     * transport has been touched.
     *
     * <p>The caller then does one of two things: once the stage has
     * completed normally, {@link #beginCapture()}; otherwise — the stage
     * failed, or the start is abandoned before capture began —
     * {@link #cancelStart()}.</p>
     *
     * <p><strong>The anchor.</strong> The take's anchor beat and frame are
     * read here, because the flush thread writes them into the initial
     * manifest and each capture carries them: the transport's punch region's
     * start when it has an enabled one, else this pipeline's punch range's
     * punch-in, else the transport's position. The transport's position is
     * remembered as well: when the transport is stopped or paused at
     * {@link #beginCapture()}, that call puts the transport back there if it
     * has moved, so a seek while the take is being prepared cannot separate
     * the take from where recording begins. A transport that is rolling at
     * {@code beginCapture()} is not put back, so an anchor read here from
     * the transport's position then differs from where capture begins by
     * about the time the preparation took (see {@code beginCapture()}).</p>
     *
     * <p>A failure here — a ring size the ring refuses, a session factory
     * that throws, a flush thread that cannot be started — rolls back what
     * this call did: the take's flush service, if one was created, is asked
     * to discard the take ({@link CaptureFlushService#requestAbort()},
     * without waiting; a flush thread that could not be started created
     * nothing, and its service is terminated already), the recording flags
     * are cleared and the pipeline is idle again; the failure is rethrown,
     * carrying anything the rollback threw as suppressed. A file that cannot
     * be created is not thrown here: the stage completes exceptionally with
     * it, once the flush thread has deleted the segment and manifest files
     * it created for the take, and each track directory it created, if that
     * leaves it empty (best-effort: what an I/O error keeps from being
     * deleted is left and the error logged); the take directory — even one
     * it had to create — is left for its caller. The rollback reaches only
     * what this call created, except that, when the take's first manifest
     * write fails before its rename lands, it also deletes whatever is at
     * {@code take.manifest} — such as a manifest an earlier take left in the
     * take directory — and at {@code take.manifest.tmp}, an empty directory
     * included (story 350).</p>
     *
     * <p>Refused, touching nothing, while a take is being prepared, while one
     * is recording, and while the previous take's
     * {@linkplain #isFinalizationPending() finalisation is pending}: the reset
     * at the top of a prepare would drop the captures, ring and flush service
     * that {@link #completeStop()} builds that take's clips from, while the
     * flush thread may still be writing them. Refused the same way after a
     * {@link #cancelStart()}, or after a {@link #beginCapture()} that failed
     * and was rolled back, until that take's {@code capture-flush} thread
     * has marked itself terminated ({@link CaptureFlushService#isTerminated()};
     * {@link #termination()} completes right after): that thread may
     * still be creating or deleting that take's files in the same take
     * directory. A second thread there could collide with them — a
     * {@code CREATE_NEW} of the same
     * {@code segment-000.wav.part} — or lose the new take's
     * {@code take.manifest} to the old thread's deletion (book §2.3: one
     * writer per byte). A failed {@code prepare()} leaves no such thread.</p>
     *
     * @return the take's readiness ({@link CaptureFlushService#readiness()}):
     *         it completes normally on the {@code capture-flush} thread once
     *         every file exists and the thread is draining; or exceptionally,
     *         only once that thread has rolled back this take's files —
     *         deleting the segment and manifest files this take created, and
     *         each track directory it created, if that leaves it empty
     *         (best-effort: what an I/O error keeps from being deleted is
     *         left and the error logged); the take directory is left for
     *         the caller — and has terminated, with a
     *         {@link java.io.UncheckedIOException} (the take directory, a
     *         track directory, a segment or the manifest could not be
     *         created), an {@link IllegalArgumentException} (a bit depth other
     *         than 16, 24 or 32), another unchecked throwable as thrown, or a
     *         {@link java.util.concurrent.CancellationException} (the
     *         initialisation saw the start cancelled; a cancel it no longer
     *         sees leaves the stage completed normally and the take is
     *         discarded all the same). A holder cannot complete it. A
     *         dependent registered with a non-async method runs on the
     *         {@code capture-flush} thread, or on the registering thread if
     *         the stage has completed already: one that touches FX state only
     *         posts to the FX thread
     * @throws IllegalStateException if a take is being prepared, the pipeline is
     *                               active, its previous take's finalisation
     *                               is pending, or the flush thread of a take
     *                               that was cancelled, or whose failed
     *                               {@code beginCapture()} was rolled back,
     *                               has not terminated yet
     */
    public CompletionStage<Void> prepare() {
        if (finalizationPending) {
            throw new IllegalStateException("Recording pipeline is still finalising its previous take under "
                    + outputDirectory + "; call completeStop() once that take's finalisation has completed");
        }
        if (active) {
            throw new IllegalStateException("Recording pipeline is already active");
        }
        if (preparing) {
            throw new IllegalStateException("Recording pipeline is already preparing a take under " + outputDirectory);
        }
        CaptureFlushService previous = flush;
        if (previous != null && !previous.isTerminated()) {
            throw new IllegalStateException("The capture-flush thread of the previous take under " + outputDirectory
                    + " has not terminated yet: it was asked to discard that take's files; prepare again once"
                    + " termination() has completed");
        }
        preparing = true;
        try {
            doPrepare();
        } catch (RuntimeException | Error e) {
            releaseFailedStart(e);
            throw e;
        }
        return flush.readiness();
    }

    private void doPrepare() {
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
        // The transport's own position is kept for beginCapture(), which
        // restores it if it moved while the take was being prepared — only
        // over a transport that is stopped or paused then; a rolling one is
        // left where it has rolled to.
        // The tempo is read once: the anchor frame, the captures, the take's
        // configuration and the callback's start frames all use this value.
        double tempo = transport.getTempo();
        takeTempoBpm = tempo;
        preparedPositionBeats = transport.getPositionInBeats();
        PunchRegion transportPunch = transport.isPunchEnabled()
                ? transport.getPunchRegion()
                : null;
        if (transportPunch != null) {
            double startSeconds = transportPunch.startFrames() / projectSampleRate;
            recordingStartBeat = startSeconds * (tempo / 60.0);
            double punchFrameScale = format.sampleRate() / projectSampleRate;
            recordingStartFrame = punchFrameScale == 1.0 ? transportPunch.startFrames()
                    : Math.round(transportPunch.startFrames() * punchFrameScale);
        } else if (punchRange != null) {
            recordingStartBeat = punchRange.punchInBeat();
            recordingStartFrame = beatsToFrames(recordingStartBeat, tempo);
        } else {
            recordingStartBeat = preparedPositionBeats;
            recordingStartFrame = beatsToFrames(recordingStartBeat, tempo);
        }

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
                    format.bufferSize(), compensation, compensatedStartBeat(track, tempo),
                    format.sampleRate(), tempo, outputDirectory.resolve(track.getId()), sessionFactory));
        }

        DiskHeadroomWatch watch = diskHeadroomWatch != null
                ? diskHeadroomWatch
                : DiskHeadroomWatch.forDirectory(outputDirectory, warningSink);
        CaptureFlushService.TakeConfig config = new CaptureFlushService.TakeConfig(
                outputDirectory, format, tempo, recordingStartBeat, recordingStartFrame,
                punchRange, loopRecord, forceCadence, Instant.now());
        flush = new CaptureFlushService(ring, config, List.copyOf(captures.values()), watch,
                warningSink, peakSnapshotSink, nanoClock);
        // Starts the thread only: the take's files are its first act.
        flush.start();
    }

    /**
     * Begins capture — the second half of a start, once the stage
     * {@link #prepare()} returned has completed normally. Caller thread (FX
     * in the app); no storage I/O, and it never waits for the flush thread
     * (what the engine start and the transport do is theirs). In order: if
     * the transport is not rolling, put it back at the position
     * {@code prepare()} read, if it has moved since — only the transport's
     * position: a take anchored at a punch region or range keeps that
     * anchor — then create the take's {@link CaptureCallback} and wire it as
     * the engine's recording callback, start the audio engine if it is not
     * running, and transition the transport to recording. The pipeline is
     * then {@linkplain #isActive() active}.
     *
     * <p>The restore is a {@link Transport#setPositionInBeats(double)}, made
     * only on a transport that is stopped or paused — Record from idle —
     * where it is stored at once, before the callback is wired, so the first
     * block captured carries the anchor. A transport that is rolling
     * (playing or recording) is never sought here: on one a real-time clock
     * drives, {@code Transport} would defer the seek to the clock's next
     * block boundary, after {@link Transport#record()} had anchored the
     * record start at the position the transport had rolled to, and playback
     * would jump back by the time the take took to prepare. So a take
     * anchored at the transport's position and started over a rolling
     * transport — Record pressed during playback — keeps the anchor
     * {@code prepare()} read, while capture begins here, where the transport
     * has rolled to: its clips are placed at, and its manifest anchored to, a
     * position about the preparation time (from {@code prepare()} to this
     * call) earlier than the transport's when the first block was captured,
     * and the stop {@link #requestStop()} makes returns the playhead, with
     * {@link Transport#isReturnToStartOnStop()} set, to where
     * {@code Transport.record()} anchored it, not to the take's anchor. The
     * synchronous start this two-step start replaced had the same kind of
     * difference, sized mostly by the file creation it ran on the caller
     * thread between reading the anchor and {@code Transport.record()}.
     * Story 328's transport-clocked capture-start gate owns the fix.</p>
     *
     * <p>All or nothing: if a step fails, the take's producer gate is closed
     * ({@link CaptureRing#closeProducer()} — a callback entering afterwards
     * claims nothing; an in-flight callback follows {@link CaptureCallback}'s
     * final gate read), the callback is removed, a
     * transport that the failed step left recording is stopped, the take's
     * flush service is asked to discard the take
     * ({@link CaptureFlushService#requestAbort()} — without waiting: the
     * {@code capture-flush} thread deletes the segment and manifest files it
     * created for the take, and each track directory it created, if that
     * leaves it empty (best-effort: what an I/O error keeps from being
     * deleted is left and the error logged), and then terminates, unless a
     * throwable that escaped its drain loop had already sealed the take
     * early and ended it, when nothing is deleted ({@link #termination()});
     * the take directory is left for the caller), the recording flags are
     * cleared, the pipeline is idle again, and the failure is rethrown,
     * carrying anything the rollback threw as suppressed; the next
     * {@link #prepare()} is refused until that thread has marked itself
     * terminated ({@link CaptureFlushService#isTerminated()};
     * {@link #termination()} completes right after). An engine this call
     * started is left running, and a restored position stays restored.</p>
     *
     * <p>Refused, touching nothing, when no take is being prepared — never
     * prepared, already begun, cancelled, or rolled back — and while the
     * take's readiness has not completed normally (still pending, or
     * failed: such a take can only be cancelled).</p>
     *
     * @throws IllegalStateException if no take is being prepared, or the take's
     *                               readiness has not completed normally
     */
    public void beginCapture() {
        if (!preparing) {
            throw new IllegalStateException("Recording pipeline has no take being prepared; call prepare() first");
        }
        CaptureRing takeRing = ring;
        CaptureFlushService service = flush;
        if (!service.isReady()) {
            throw new IllegalStateException("The take under " + outputDirectory + " is not ready: capture begins"
                    + " only once the stage prepare() returned has completed normally");
        }
        preparing = false;
        active = true;
        try {
            TransportState state = transport.getState();
            boolean rolling = state == TransportState.PLAYING || state == TransportState.RECORDING;
            if (!rolling && transport.getPositionInBeats() != preparedPositionBeats) {
                transport.setPositionInBeats(preparedPositionBeats);
            }

            // Wire the take's own recording callback on the audio engine: it
            // carries the take's ring, flush service and tempo in final
            // fields, so the audio thread reads nothing of this pipeline.
            audioEngine.setRecordingCallback(new CaptureCallback(takeRing, service, instrumentTracks,
                    transport, audioEngine, format.sampleRate(), projectSampleRate, takeTempoBpm));

            // Start the audio engine if it is not already running
            audioEngine.start();

            // Transition transport to recording
            transport.record();
        } catch (RuntimeException | Error e) {
            // The gate first: later entries claim nothing, and a callback
            // already inside drops its block if its final read sees the close.
            rollbackStep(e, () -> takeRing.closeProducer());
            rollbackStep(e, () -> audioEngine.setRecordingCallback(null));
            rollbackStep(e, () -> {
                if (transport.getState() == TransportState.RECORDING) {
                    transport.stop();
                }
            });
            releaseFailedStart(e);
            throw e;
        }
    }

    /**
     * Cancels the take being prepared — before capture began, whether or not
     * its readiness has completed, and the only way out once it has failed.
     * Caller thread (FX in the app); no storage I/O and no waiting. The take's
     * flush service is asked to discard the take
     * ({@link CaptureFlushService#requestAbort()}): the {@code capture-flush}
     * thread deletes the segment and manifest files it created for the take
     * — and, if the take's first manifest write failed before its rename,
     * any manifest an earlier take of the same pipeline left in the take
     * directory (story 350) — and each track directory it created, if that
     * leaves it empty (best-effort: what an I/O error keeps from being
     * deleted is left and the error logged), and then terminates; the take
     * directory — even one it had to create — is left for its caller. An
     * abort that the take's initialisation sees fails the readiness; one
     * that comes later — after the initialisation's last look, or once
     * readiness has completed normally — ends the drain loop instead, and
     * the take is discarded the same way. If the flush thread had already
     * ended on its own before the abort — a throwable that escaped its drain
     * loop sealed the take early — the abort deletes nothing: the take stays
     * as it was sealed early, and the termination has already completed. The
     * recording flags are cleared, the take's captures are dropped and the
     * pipeline is idle again; its flush service stays reachable until the
     * next {@link #prepare()}, which is refused until that thread has marked
     * itself terminated ({@link CaptureFlushService#isTerminated()};
     * {@link #termination()} completes right after). Nothing was captured,
     * and the engine and the transport were never touched.
     *
     * @return the flush thread's termination ({@link CaptureFlushService#termination()}):
     *         it completes once the thread has deleted what it deletes for
     *         the take, so cleanup that must follow the thread — removing the
     *         take directory — is scheduled on it. A dependent
     *         registered with a non-async method runs on the
     *         {@code capture-flush} thread, or on the registering thread if
     *         the thread has terminated already: storage work belongs in an
     *         async variant with an executor of its own, never on the FX
     *         thread
     * @throws IllegalStateException if no take is being prepared
     */
    public CompletionStage<Void> cancelStart() {
        if (!preparing) {
            throw new IllegalStateException("Recording pipeline has no take being prepared");
        }
        CaptureFlushService service = flush;
        // No callback was installed for this take; the gate is closed all
        // the same, so the take's ring can never be published to.
        ring.closeProducer();
        service.requestAbort();
        captures.clear();
        for (Track track : armedTracks) {
            track.setRecording(false);
        }
        preparing = false;
        return service.termination();
    }

    /**
     * The common tail of a failed {@link #prepare()} or
     * {@link #beginCapture()}: the rollback fault seam, the take's flush
     * service — if this start created one — asked to discard the take
     * without waiting, the captures dropped, the recording flags cleared.
     * Every step runs even if an earlier one throws; whatever the steps
     * throw is attached to {@code cause} as suppressed, so the caller sees
     * the failure that ended the start, and the pipeline always ends idle.
     * The take's ring, if this start allocated one, has its producer gate
     * closed (a {@code beginCapture()} that failed closed it before it
     * removed the callback; closing it again changes nothing): a callback
     * the audio thread loaded before it was removed carries that ring and
     * may still run, and then finds the gate closed and publishes nothing.
     * The ring and the flush service stay reachable through
     * {@link #getCaptureRing()} and {@link #getCaptureFlushService()} so a
     * caller can follow the thread; the next start replaces them.
     */
    private void releaseFailedStart(Throwable cause) {
        try {
            Runnable fault = rollbackFault;
            if (fault != null) {
                rollbackStep(cause, fault);
            }
            rollbackStep(cause, () -> {
                CaptureRing takeRing = ring;
                if (takeRing != null) {
                    takeRing.closeProducer();
                }
                CaptureFlushService service = flush;
                if (service != null) {
                    service.requestAbort();
                }
            });
            rollbackStep(cause, captures::clear);
            for (Track track : armedTracks) {
                rollbackStep(cause, () -> track.setRecording(false));
            }
        } finally {
            preparing = false;
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
     * Stops the take — the first half of a stop. Caller thread (FX in the
     * app); no storage I/O, and it never waits for the flush thread (what the
     * transport's stop does is the transport's). It closes the take's
     * producer gate ({@link CaptureRing#closeProducer()}), removes the
     * recording callback, clears the recording flags and stops the transport
     * (story 315: that returns the playhead to the record-start anchor, per
     * {@link Transport#isReturnToStartOnStop()}), then asks the flush thread
     * to seal the take ({@link CaptureFlushService#requestStop(TakeManifest.SealedBy)}):
     * it waits, bounded, for a callback in flight to leave the gate, its
     * final sweep drains the ring, every lane is sealed — in loop-record
     * the lap in flight becomes the last take — and the final manifest is
     * written, and then the thread terminates. The seal is requested even if
     * one of the steps before it throws; that throwable then propagates. From
     * here until {@link #completeStop()} returns the clips the pipeline is
     * {@linkplain #isFinalizationPending() finalization pending}.
     *
     * <p>The closing of the gate, the removal of the callback, the clearing
     * of the flags and the transport stop happen once: a further call while
     * the finalisation is pending repeats none of them — by then the
     * callback slot, the flags and the transport may belong to whatever the
     * user did next — and only returns the same termination signal. On a
     * pipeline that is neither active nor finalising — never started,
     * cancelled, or whose clips were returned — it does nothing and returns
     * a completed stage.</p>
     *
     * <p><strong>The fence</strong> (book §5.2 FINALIZING: deregister →
     * drain → seal). Removing the callback does not wait for the audio
     * thread, and neither does this method: the gate does the work. It is
     * closed first, so the take ends at a block boundary — the last block
     * whose callback read the gate open right before it published. A
     * callback that was inside when the gate closed and reads it closed
     * drops its block whole; one that enters later claims nothing. Every
     * block that is published is in the sealed take, because the flush
     * thread makes its final sweep only once the callback in flight has left
     * the gate ({@link CaptureRing#awaitProducerQuiescent(long)}, bounded by
     * {@link CaptureFlushService#PRODUCER_QUIESCENCE_BOUND}), and each was
     * stamped before the transport stop here moved the playhead. If the
     * callback has not left when that bound elapses — an audio thread that
     * died or hangs inside it — the flush thread warns, sweeps and seals
     * all the same, and a block published behind that sweep is recorded in
     * the manifest as a gap if it is in the ring once the lanes have been
     * sealed.</p>
     *
     * @return the flush thread's termination ({@link CaptureFlushService#termination()}):
     *         once it has completed, {@link #completeStop()} builds the
     *         clips. A holder cannot complete it. A dependent registered with
     *         a non-async method runs on the {@code capture-flush} thread as
     *         its last act, or on the registering thread if the thread has
     *         terminated already: one that touches FX state only posts to the
     *         FX thread
     * @throws IllegalStateException if a take is being prepared — that take is
     *                               cancelled with {@link #cancelStart()}
     */
    public CompletionStage<Void> requestStop() {
        if (preparing) {
            throw new IllegalStateException("Recording pipeline is still preparing its take under "
                    + outputDirectory + "; cancel it with cancelStart()");
        }
        CaptureFlushService service = flush;
        if (finalizationPending) {
            service.requestStop(TakeManifest.SealedBy.STOP);
            return service.termination();
        }
        if (!active) {
            return CompletableFuture.completedStage(null);
        }
        active = false;
        // From here until the clips are built the take is finalising; a
        // further request skips this block.
        finalizationPending = true;
        clipTempoBpm = transport.getTempo();
        try {
            // The fence, first: from this store on no callback publishes a
            // block it had not already decided to publish — and every block
            // that is published was stamped before the transport stop below
            // moves the playhead.
            ring.closeProducer();

            // Remove the recording callback
            audioEngine.setRecordingCallback(null);

            // Clear recording indicator on armed tracks
            for (Track track : armedTracks) {
                track.setRecording(false);
            }

            // Stop the transport (story 315: this returns the playhead to the
            // record-start anchor, per Transport.isReturnToStartOnStop()).
            transport.stop();
        } finally {
            // Whatever threw above, the take is sealed and its thread ends:
            // nothing else would ever ask for it.
            service.requestStop(TakeManifest.SealedBy.STOP);
        }
        return service.termination();
    }

    /**
     * Completes the stop — the second half, once the signal
     * {@link #requestStop()} returned has completed. Caller thread (FX in the
     * app); no storage I/O and no waiting. It builds the take's clips from
     * what the flush thread wrote, at the tempo read when the stop began:
     * {@link AudioClip}s on the armed tracks referencing every sealed
     * segment of the take in manifest order, or, in loop-record, each
     * track's take stack with its active take's clip. It adds them to the
     * tracks ({@code addClip}, {@code putTakeGroup}), records them in
     * {@link #getRecordedClips()} and ends the finalisation. It repeats none
     * of {@code requestStop()}'s one-shot steps.
     *
     * <p>The clips carry no audio data ({@link AudioClip#getAudioData()} is
     * {@code null}): the pipeline keeps no copy of what it captured. Each
     * clip — every take's clip in a loop-record stack too — lists its
     * sealed segment files ({@link AudioClip#getSourceSegmentPaths()}) and
     * declares the sample rate, channel count and frame count it was
     * captured with ({@link AudioClip#getSourceRateMetadata()}). A caller
     * that wants the clips published with their audio reads it back first —
     * {@link #recordedSegmentPaths()}, then {@link SegmentFile#readFrames(List)}
     * off this thread — and completes the stop with
     * {@link #completeStop(Function)} instead. A
     * clip whose take ended with a segment that did not seal lists only the
     * segments that did — none, when its only segment is the one that
     * failed — and still declares every frame captured: the frames the list
     * does not hold are in the {@code .part} the failed seal left for
     * recovery.</p>
     *
     * <p>Idempotent once the clips have been returned: a further call
     * returns an empty list, as does a call on a pipeline that was never
     * started or whose start was cancelled — an empty list always means
     * "nothing was recorded".</p>
     *
     * @return the list of {@link AudioClip}s created on armed tracks
     * @throws IllegalStateException if the flush thread has not terminated yet
     *                               (nothing is read or built then, and the
     *                               finalisation stays pending), the pipeline
     *                               is still active (no stop was requested),
     *                               or a take is being prepared
     */
    public List<AudioClip> completeStop() {
        return completeStop(NO_RECORDED_AUDIO);
    }

    /**
     * Completes the stop as {@link #completeStop()} does, and gives each
     * clip the audio the caller has already read back for it: for every
     * clip the take publishes — the plain take's clip of each armed track,
     * and in loop-record the clip of <em>every</em> take of each track's
     * stack, not only the active one — {@code loadedAudio} is asked once,
     * with the clip's segment-path list
     * ({@link AudioClip#getSourceSegmentPaths()}, one of the lists
     * {@link #recordedSegmentPaths()} returned), and what it returns is
     * attached with {@link AudioClip#setAudioData} <em>before</em> the clip
     * is added to its track ({@code addClip}, {@code putTakeGroup}). So no
     * clip of the take is ever visible on a track without its audio, unless
     * the lookup had none for it.
     *
     * <p>The lookup is called on this thread — the caller thread, FX in the
     * app — and must not block, wait or touch storage: it hands over arrays
     * that were read earlier, off this thread, from the lists
     * {@code recordedSegmentPaths()} returned (a map lookup). It returns
     * {@code null} for "no audio": the clip is then published exactly as
     * {@code completeStop()} publishes it, without audio data. A clip whose
     * segment-path list is empty — its only segment did not seal — has
     * nothing that could have been read; the lookup is not asked for it and
     * it is published without audio. The array is attached as it is, not
     * copied. If the lookup throws, the exception propagates: the clips
     * published before it stay published, and the finalisation has ended.</p>
     *
     * @param loadedAudio from a clip's segment-path list to its audio
     *                    {@code [channel][frame]}, or {@code null} for none
     * @return the list of {@link AudioClip}s created on armed tracks
     * @throws IllegalStateException as {@link #completeStop()}
     */
    public List<AudioClip> completeStop(Function<List<String>, float[][]> loadedAudio) {
        Objects.requireNonNull(loadedAudio, "loadedAudio must not be null");
        if (preparing) {
            throw new IllegalStateException("Recording pipeline is still preparing its take under "
                    + outputDirectory + "; there is no stop to complete");
        }
        if (!finalizationPending) {
            if (active) {
                throw new IllegalStateException("Recording pipeline is recording: call requestStop() first");
            }
            return Collections.emptyList();
        }
        CaptureFlushService service = flush;
        if (!service.isTerminated()) {
            throw new IllegalStateException("The take under " + outputDirectory + " is still being written"
                    + " to disk: call completeStop() once the stage requestStop() returned has completed");
        }
        finalizationPending = false;
        return buildClips(service, loadedAudio);
    }

    /**
     * Returns the segment-path list of every clip the {@link #completeStop()}
     * that follows will publish with at least one sealed segment, so that
     * the caller can read their audio back
     * ({@link SegmentFile#readFrames(List)}) off this thread <em>before</em>
     * it completes the stop, and hand it to
     * {@link #completeStop(Function)}. Caller thread; it reads what the
     * flush thread left and touches no storage and never waits.
     *
     * <p>One list per clip, in publication order: for each armed track, in
     * armed order, the plain take's clip — every sealed segment of the
     * track, in manifest order — or, in loop-record, one list per take of
     * the track's stack, lap by lap, each holding that lap's sealed
     * segments. Each list equals the {@link AudioClip#getSourceSegmentPaths()}
     * of the clip it stands for. A clip with no sealed segment — its only
     * segment did not seal — is left out: there is nothing to read for it,
     * and {@code completeStop} publishes it without audio. A track that
     * recorded nothing gets no clip and no list.</p>
     *
     * <p>Meaningful between the completion of the stage
     * {@link #requestStop()} returned and {@code completeStop}: on a
     * pipeline with no finalisation pending — never started, cancelled, or
     * whose clips were returned — the list is empty.</p>
     *
     * @return the lists, unmodifiable; possibly empty
     * @throws IllegalStateException if the flush thread has not terminated
     *                               yet, or a take is being prepared
     */
    public List<List<String>> recordedSegmentPaths() {
        if (preparing) {
            throw new IllegalStateException("Recording pipeline is still preparing its take under "
                    + outputDirectory + "; there is no recorded take yet");
        }
        if (!finalizationPending) {
            return Collections.emptyList();
        }
        CaptureFlushService service = flush;
        if (!service.isTerminated()) {
            throw new IllegalStateException("The take under " + outputDirectory + " is still being written"
                    + " to disk: its segment lists are final once the stage requestStop() returned has completed");
        }
        boolean loopRecorded = service.config().loopRecord();
        List<List<String>> lists = new ArrayList<>();
        for (Track track : armedTracks) {
            TrackCapture capture = captures.get(track);
            if (capture == null) {
                continue;
            }
            if (loopRecorded) {
                for (Take take : capture.takeGroup().takes()) {
                    List<String> lapPaths = take.clip().getSourceSegmentPaths();
                    if (!lapPaths.isEmpty()) {
                        lists.add(List.copyOf(lapPaths));
                    }
                }
                continue;
            }
            RecordingSession session = capture.session();
            if (session != null && session.getTotalSamplesRecorded() > 0) {
                List<String> segmentPaths = capture.sealedSegmentPaths();
                if (!segmentPaths.isEmpty()) {
                    lists.add(segmentPaths);
                }
            }
        }
        return Collections.unmodifiableList(lists);
    }

    /**
     * Gives {@code clip} the audio {@code loadedAudio} has for its
     * segment-path list, if the list is not empty and the lookup returns
     * one. Called before the clip is added to a track.
     */
    private static void attachLoadedAudio(AudioClip clip, Function<List<String>, float[][]> loadedAudio) {
        List<String> segmentPaths = clip.getSourceSegmentPaths();
        if (segmentPaths.isEmpty()) {
            return;
        }
        float[][] audio = loadedAudio.apply(segmentPaths);
        if (audio != null) {
            clip.setAudioData(audio);
        }
    }

    /**
     * Builds the take's clips on the caller thread once the flush thread has
     * terminated — a plain take's from the captured start position at
     * {@link #clipTempoBpm}, a loop-record take's from the stacks the flush
     * thread built — gives each the audio {@code loadedAudio} has for it,
     * and only then adds them to the armed tracks and records them in
     * {@link #recordedClips}.
     */
    private List<AudioClip> buildClips(CaptureFlushService service,
                                       Function<List<String>, float[][]> loadedAudio) {
        // The take ends the way it began: the mode the flush thread worked
        // from, not whatever the field holds now.
        boolean loopRecorded = service.config().loopRecord();

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
                    // Every lap's clip gets its audio before any of them
                    // can be seen through the track.
                    for (Take take : group.takes()) {
                        attachLoadedAudio(take.clip(), loadedAudio);
                    }
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
                double durationBeats = durationSeconds * (clipTempoBpm / 60.0);
                if (durationBeats <= 0) {
                    durationBeats = 0.01;
                }
                List<String> segmentPaths = capture.sealedSegmentPaths();

                AudioClip clip = new AudioClip(
                        "Recording — " + track.getName(),
                        compensatedStartBeat(track, clipTempoBpm),
                        durationBeats,
                        segmentPaths.isEmpty() ? null : segmentPaths.getFirst());
                clip.setSourceSegmentPaths(segmentPaths);
                // Nothing is read here: the clip names its segment files and
                // declares what was captured; reading them back is storage
                // I/O, which is not this thread's to do. What the caller
                // read beforehand is attached before the track sees the clip.
                clip.setSourceRateMetadata(
                        TrackCapture.declaredRate(session, session.getTotalSamplesRecorded()));
                attachLoadedAudio(clip, loadedAudio);

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
     * Returns whether a take is being prepared: {@link #prepare()} has
     * returned and neither {@link #beginCapture()} nor {@link #cancelStart()}
     * has run since. Meanwhile the pipeline is not
     * {@linkplain #isActive() active}, {@code prepare()} and
     * {@link #requestStop()} are refused, and every setter that refuses a
     * change while recording refuses it too. Any thread.
     *
     * @return {@code true} while a take is being prepared
     */
    public boolean isPreparing() {
        return preparing;
    }

    /**
     * Returns whether a {@link #requestStop()} has begun and
     * {@link #completeStop()} has not yet returned the take's clips — the
     * take is being finalised: its flush thread may still be draining,
     * sealing or writing the manifest. Meanwhile the pipeline is not
     * {@linkplain #isActive() active}, {@link #prepare()} is refused, every
     * setter that refuses a change while recording refuses it too, and the
     * {@code completeStop()} made once the flush thread has terminated
     * completes the stop. Any thread.
     *
     * @return {@code true} while the stop of the current take is incomplete
     */
    public boolean isFinalizationPending() {
        return finalizationPending;
    }

    /**
     * Returns the termination signal of the current take's
     * {@code capture-flush} thread, or of the last take's until the next
     * {@link #prepare()} — the stage {@link #cancelStart()} and
     * {@link #requestStop()} return ({@link CaptureFlushService#termination()}).
     * It is how a caller follows the thread after a {@link #beginCapture()}
     * that failed: that rollback asked the thread to discard the take and
     * left the pipeline idle, so {@code cancelStart()} is refused, and the
     * signal completes once the thread has deleted the segment and manifest
     * files it created for the take (best-effort: what an I/O error keeps
     * from being deleted is left and the error logged). If that thread had
     * already ended on its own before the discard was asked for — a
     * throwable that escaped its drain loop sealed the take early — the
     * discard deletes nothing, the take stays as it was sealed early, and
     * the signal has already completed. The next {@code prepare()} of this
     * pipeline is refused until that thread has marked itself terminated
     * ({@link CaptureFlushService#isTerminated()}), right before the signal
     * completes. A completed stage when no
     * prepare has created a take's flush service (never prepared, or the
     * last prepare failed before it created one). Caller thread; it reads a
     * field and never waits.
     *
     * @return the signal; a holder cannot complete it, and a dependent
     *         registered with a non-async method runs on the
     *         {@code capture-flush} thread, or on the registering thread if
     *         the signal has completed already
     */
    public CompletionStage<Void> termination() {
        CaptureFlushService service = flush;
        return service == null ? CompletableFuture.completedStage(null) : service.termination();
    }

    /**
     * Returns the early-seal signal of the current take, or of the last take
     * until the next {@link #prepare()}: it completes, at most once, on the
     * {@code capture-flush} thread, when that thread has sealed the take on
     * its own — disk exhaustion or a write failure ({@link EarlySeal}) — and
     * that seal is done; never before the take's readiness. It never
     * completes for the seal {@link #requestStop()} requests
     * ({@link #stopSealFailure()} reports a lane that threw in that seal), for
     * a cancelled start ({@link #cancelStart()}), for the rollback of a failed
     * start or for the flush service's {@code stopAndAbandon} test seam, never
     * on the audio thread and never exceptionally, and a holder cannot
     * complete it; see {@link CaptureFlushService#earlySeal()}, including
     * the early seal in the final sweep of a stop already requested. The
     * pipeline does nothing on it: the callback stays installed and the
     * transport keeps its state until {@code requestStop()}, after which
     * {@link #completeStop()} returns the clips of what was sealed. Each
     * prepare creates the take's own signal, so a pipeline started again
     * never hands out the previous take's. Once the take's flush thread has
     * terminated — as when {@code completeStop()} has returned the clips —
     * the signal has completed if it ever will. Caller thread (the one that
     * runs the take's lifecycle).
     *
     * @return the signal; a dependent registered with a non-async method
     *         runs on the {@code capture-flush} thread, or on the registering
     *         thread if the signal has completed already
     * @throws IllegalStateException if no prepare has created a take's flush
     *                               service yet ({@link #prepare()} never
     *                               called, or the last one failed before it
     *                               created one)
     */
    public CompletionStage<EarlySeal> earlySeal() {
        CaptureFlushService service = flush;
        if (service == null) {
            throw new IllegalStateException("Recording pipeline has not been started");
        }
        return service.earlySeal();
    }

    /**
     * Returns the failure of the seal {@link #requestStop()} requested for the
     * current take, or for the last take until the next {@link #prepare()}, if
     * a lane's seal threw in it ({@link StopSealFailure}): a segment whose
     * seal failed is left as its {@code .part} for recovery, the take's final
     * manifest reads {@code seal-status=aborted} with
     * {@code sealed-by=write-failure} when the flush thread's write of it
     * lands, and {@link #completeStop()} still returns the clips it built.
     * Empty when no lane's seal threw in that seal, for a take the
     * {@code capture-flush} thread sealed early ({@link #earlySeal()} reports
     * that one, also when it sealed the take in the final sweep of the stop),
     * for a cancelled start, for the rollback of a failed start and for the
     * flush service's {@code stopAndAbandon} test seam; see
     * {@link CaptureFlushService#stopSealFailure()}. Final once the take's
     * flush thread has terminated — as when the signal {@code requestStop()}
     * returned has completed; read before that, empty may only mean that the
     * seal has not run yet. Each prepare creates the take's own flush
     * service, so a pipeline started again never reports the previous take's.
     * Caller thread (the one that runs the take's lifecycle).
     *
     * @return the failure of the stop's seal, or empty
     * @throws IllegalStateException if no prepare has created a take's flush
     *                               service yet ({@link #prepare()} never
     *                               called, or the last one failed before it
     *                               created one)
     */
    public Optional<StopSealFailure> stopSealFailure() {
        CaptureFlushService service = flush;
        if (service == null) {
            throw new IllegalStateException("Recording pipeline has not been started");
        }
        return service.stopSealFailure();
    }

    /**
     * Returns the recording session for the given track (its current lane
     * while recording, its last lane after a stop), or {@code null} before
     * {@link #prepare()} and after a start that was cancelled or rolled
     * back. While the take is being prepared the {@code capture-flush}
     * thread may still be starting that session; while the finalisation is
     * {@linkplain #isFinalizationPending() pending} it may still be sealing
     * it; its results are final once {@link #completeStop()} has returned
     * the clips.
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
     * populated by the {@link #completeStop()} that returns them — empty
     * while the finalisation is {@linkplain #isFinalizationPending() pending}.
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
     * <p>This is captured when {@link #prepare()} is called and used to
     * position recorded clips on the timeline. When the transport is stopped
     * or paused as capture begins, {@link #beginCapture()} puts the transport
     * back at the position it had then, so a seek while the take was being
     * prepared cannot separate recording from this anchor; over a rolling
     * transport it does not, so when this anchor is the transport's position,
     * recording begins about the preparation time after it (see
     * {@code beginCapture()}).</p>
     *
     * @return the recording start beat position
     */
    public double getRecordingStartBeat() {
        return recordingStartBeat;
    }

    /**
     * Returns the frame position of {@link #getRecordingStartBeat()} at the
     * tempo current when {@link #prepare()} ran — the manifest's {@code start-frame}.
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
     * @throws IllegalStateException if capture never began (the pipeline was
     *                               never started, its take is still being
     *                               prepared, or its start was cancelled or
     *                               rolled back), the bound elapses, or the
     *                               flush thread has stopped with blocks
     *                               unapplied
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
        // A pending finalisation's thread may still be in the final sweep,
        // before anything is sealed: that take was started.
        if (service == null || (!active && !finalizationPending && !service.isSealed())) {
            throw new IllegalStateException("Recording pipeline has not been started");
        }
        service.awaitFlushed(timeout);
    }

    /**
     * Returns how many incoming blocks the callback dropped because the ring
     * was full, in the current take or in the last one until the next
     * {@link #prepare()}; {@code 0} while the pipeline holds no ring (see
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
     * {@link #prepare()}; {@code 0} while the pipeline holds no ring (see
     * {@link #getCaptureRing()}). Any thread.
     */
    public long getTruncatedFrames() {
        CaptureRing current = ring;
        return current == null ? 0L : current.truncatedFrames();
    }

    /**
     * Sets the segment rotation caps (defaults 30 min / 500 MB). Must be
     * called before {@link #prepare()}.
     *
     * @param maxSegmentDuration rotate when a segment holds this much audio; positive
     * @param maxSegmentBytes    rotate when a segment's data chunk reaches this size, and
     *                           before an append would take it past this size; positive and
     *                           at most {@link SegmentWriter#MAX_DATA_BYTES}
     * @throws IllegalArgumentException if a cap is out of range
     * @throws IllegalStateException    if a take is being prepared or is recording, or
     *                                  the previous take is still being finalised
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
     * before {@link #prepare()}.
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
     * watch, the manifest retry interval and the peak publication interval
     * read (default
     * {@code System::nanoTime}). Test/diagnostic seam; must be called before
     * {@link #prepare()}.
     */
    public void setNanoClock(LongSupplier nanoClock) {
        requireInactive("nano clock");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock must not be null");
    }

    /**
     * Sets the sink for user-facing warnings (ring overflow, block
     * truncation, low disk, disk exhaustion, early seal, a segment that
     * could not be sealed, an unwritable manifest, a flush-loop failure);
     * {@code null} logs at WARNING. Called on the {@code capture-flush}
     * thread only; a sink that touches the UI must marshal itself. A sink
     * that throws a {@link RuntimeException} is logged and ignored; it can
     * never end a take. Story 339 injects the production notification seam.
     * Must be called before {@link #prepare()}.
     */
    public void setWarningSink(Consumer<String> warningSink) {
        requireInactive("warning sink");
        this.warningSink = warningSink;
    }

    /**
     * Sets the sink for the peak snapshots of the lanes being captured
     * (Recording Reliability book §4.5; default: none are delivered, and
     * none are built). For
     * each armed track the sink receives an immutable
     * {@link CapturePeakSnapshot} of the current lane's bounded, decimated
     * peak mirror: at most once per
     * {@link CaptureFlushService#PEAK_PUBLISH_INTERVAL} while the lane gains
     * frames, and once more when the lane is finalized — at each loop wrap
     * in loop-record, and when the take is sealed. Called on the
     * {@code capture-flush} thread only, never on the audio callback; a sink
     * that touches the UI must marshal itself. Whatever a sink throws — a
     * {@link RuntimeException} or an {@link Error} — is logged, once per
     * track of a take, and ignored; it can never end a take, and it never
     * replaces the failure of a seal. Must be called before
     * {@link #prepare()}.
     *
     * @param peakSnapshotSink the sink; not {@code null}
     * @throws IllegalStateException if a take is being prepared or is recording, or the
     *                               previous take is still being finalised
     */
    public void setPeakSnapshotSink(Consumer<CapturePeakSnapshot> peakSnapshotSink) {
        requireInactive("peak snapshot sink");
        this.peakSnapshotSink = Objects.requireNonNull(peakSnapshotSink, "peakSnapshotSink must not be null");
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
     * {@link #prepare()} at the ring allocation.
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
     * affected. Must be called before {@link #prepare()}.
     *
     * @throws IllegalStateException if a take is being prepared or is recording, or the
     *                               previous take is still being finalised
     */
    void setChannelOpener(SegmentWriter.ChannelOpener opener) {
        requireInactive("channel opener");
        this.channelOpener = Objects.requireNonNull(opener, "opener must not be null");
    }

    /**
     * Fault seam (test-only): {@code fault} runs inside the rollback of a
     * failed {@link #prepare()} or {@link #beginCapture()} — after
     * {@code beginCapture()}'s rollback has removed the recording callback,
     * and before the take's flush service is asked to discard the take — so
     * a test can make the rollback itself throw. {@code null} removes it.
     */
    void setRollbackFault(Runnable fault) {
        this.rollbackFault = fault;
    }

    /**
     * Returns the flush service of the current take, or of the last take
     * until the next {@link #prepare()}; {@code null} before the first
     * prepare and after a prepare that failed before it created one.
     */
    CaptureFlushService getCaptureFlushService() {
        return flush;
    }

    /**
     * Returns the capture ring of the current take, or of the last take
     * until the next {@link #prepare()}; {@code null} before the first
     * prepare and after a prepare that failed before it allocated one.
     */
    CaptureRing getCaptureRing() {
        return ring;
    }

    /**
     * Refuses a pre-start setting while a take is being prepared or is
     * recording, and while one is still finalising: the
     * {@code capture-flush} thread starts lane 0's sessions while the take is
     * being prepared, and in loop-record it creates each later lane on that
     * thread — ahead of the lap, or at a wrap, also in the final sweep —
     * through {@link #newSession}, which
     * reads the segment limits, the force cadence, the clock and the channel
     * opener. The other settings wait for the take in the same way.
     */
    private void requireInactive(String what) {
        if (preparing) {
            throw new IllegalStateException("cannot change the " + what + " while a take is being prepared");
        }
        if (active) {
            throw new IllegalStateException("cannot change the " + what + " while recording");
        }
        if (finalizationPending) {
            throw new IllegalStateException("cannot change the " + what
                    + " while the previous take is still being finalised");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Driver round-trip latency compensation
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Returns the driver-reported round-trip latency this pipeline will
     * compensate for at {@link #prepare()}. Defaults to
     * {@link RoundTripLatency#UNKNOWN} (no compensation).
     *
     * @return the configured round-trip latency; never {@code null}
     */
    public RoundTripLatency getReportedLatency() {
        return reportedLatency;
    }

    /**
     * Configures the driver-reported round-trip latency to compensate for.
     * Must be called <em>before</em> {@link #prepare()} — the pipeline
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
     * <em>before</em> {@link #prepare()}.
     *
     * @param apply {@code true} to compensate, {@code false} to leave
     *              recorded takes uncompensated
     */
    public void setApplyLatencyCompensation(boolean apply) {
        this.applyLatencyCompensation = apply;
    }

    /**
     * Returns the compensation amount (in sample frames) the pipeline
     * resolved at {@link #prepare()} from the configured
     * {@link #getReportedLatency()} and toggle state. {@code 0} when
     * compensation is disabled or the driver reports zero latency. Only
     * meaningful after {@link #prepare()}.
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
            double startBeats = (transportPunch.startFrames() / projectSampleRate)
                    * (bpm / 60.0);
            double endBeats = (transportPunch.endFrames() / projectSampleRate)
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

    private long beatsToFrames(double beats, double bpm) {
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
     * for early takes near the start of the timeline. {@code bpm} converts
     * the compensation to beats: the tempo at start for the capture's
     * snapshot, {@link #clipTempoBpm} for the clip.
     */
    private double compensatedStartBeat(Track track, double bpm) {
        long compensationFrames = trackCompensationFrames.getOrDefault(track, 0L);
        if (compensationFrames <= 0) {
            return recordingStartBeat;
        }
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
     * {@link #prepare()}: the mode is part of the take's configuration, and a
     * change while a take is being prepared or is recording is refused.
     *
     * @throws IllegalStateException if a take is being prepared or is recording, or the
     *                               previous take is still being finalised
     */
    public void setLoopRecord(boolean loopRecord) {
        requireInactive("loop-record mode");
        this.loopRecord = loopRecord;
    }

    /**
     * Returns an unmodifiable map of the {@link TakeGroup}s accumulated so
     * far during a loop-record session, keyed by armed track. The map is
     * populated as each loop lap wraps (on the flush thread; the values are
     * immutable snapshots); once {@link #completeStop()} has returned the clips it
     * contains the final stacks. While the finalisation is
     * {@linkplain #isFinalizationPending() pending} the flush thread may not
     * have stacked the last lap yet.
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
