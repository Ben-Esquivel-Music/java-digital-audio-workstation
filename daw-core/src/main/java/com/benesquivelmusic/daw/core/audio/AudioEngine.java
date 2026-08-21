package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.analysis.InputLevelMonitor;
import com.benesquivelmusic.daw.core.analysis.InputLevelMonitorRegistry;
import com.benesquivelmusic.daw.core.audio.performance.TrackCpuBudgetEnforcer;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.mixer.CueBusManager;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.performance.PerformanceMonitor;
import com.benesquivelmusic.daw.core.recording.Metronome;
import com.benesquivelmusic.daw.core.recording.MetronomeSideOutputRouter;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Central audio engine responsible for managing the audio processing pipeline.
 *
 * <p>The engine coordinates audio I/O, drives the mixer, and dispatches
 * audio buffers to tracks and plugins.</p>
 *
 * <p>Audio processing is performed in fixed-size blocks via
 * {@link #processBlock(float[][], float[][], int)}. All processing buffers
 * are pre-allocated during {@link #start()} so that the audio callback
 * performs zero heap allocations — making it real-time-safe.</p>
 *
 * <p>When a {@link Transport}, {@link Mixer}, and track list are configured,
 * the engine reads audio data from each track's {@link AudioClip}s at the
 * current transport position, mixes them through the mixer channel strips
 * into the master bus, and advances the transport. Loop playback is supported
 * when the transport's loop mode is enabled. That transport/mixer/tracks
 * configuration is published atomically via
 * {@link #setGraph(Transport, Mixer, List)} as one immutable snapshot record
 * behind a single volatile field, so the audio thread always sees either the
 * entire old graph or the entire new one — never a half-swapped hybrid
 * (story 314 review).</p>
 *
 * <p>Audio I/O streams through ONE slot typed by the SDK
 * {@link AudioBackend} interface (story 316 — the backend consolidation this
 * class's former dual-slot javadoc awaited). The application installs a
 * {@link StreamingProvision} — the requested backend plus an explicit
 * fallback ladder — via {@link #setStreamingProvision(StreamingProvision)};
 * {@link #startAudioOutput()} walks the ladder, opens the first rung that
 * accepts its device and negotiated format, and starts an engine-owned
 * {@link EngineStreamPump} that calls {@link #processBlock} once per buffer
 * period, paced by the backend's device-clock backpressure signal. The
 * reported active backend ({@link #openStreamBackendName()} /
 * {@link #openStreamDevice()}) is by construction the open stream's.</p>
 */
public final class AudioEngine {

    private static final Logger LOG = Logger.getLogger(AudioEngine.class.getName());

    /** Maximum number of tracks supported for pre-allocated buffers. */
    static final int MAX_TRACKS = 64;

    private AudioFormat format;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final EffectsChain masterChain;
    private AudioBufferPool bufferPool;

    /**
     * The engine's ONE streaming slot (story 316): the requested backend
     * plus its explicit fallback ladder.
     */
    private volatile StreamingProvision streamingProvision;

    /**
     * Backend of the stream the engine currently tracks — the winning
     * ladder rung after a successful open. Non-null while
     * {@link StreamState#RUNNING} or {@link StreamState#PAUSED}, and while
     * {@link StreamState#RELEASE_PENDING} (where it names the backend still
     * holding the unreleasable handle). {@link #processBlock} snapshots this
     * once per block for metronome {@code writeToChannel} routing, so the
     * click side output finally targets an OPEN stream (stories 135/136).
     */
    private volatile AudioBackend openBackend;

    /** Device identity of the tracked stream (the winning rung's device). */
    private volatile DeviceId openDevice;

    /** Negotiated SDK format the tracked stream was actually opened with. */
    private volatile com.benesquivelmusic.daw.sdk.audio.AudioFormat openSdkFormat;

    /** Render pump of the open stream; non-null only while RUNNING. */
    private volatile EngineStreamPump pump;

    /**
     * Lifecycle state of the backend audio stream (story 315 review). One
     * volatile field replaces the former {@code streamOpen}/{@code streamPaused}
     * pair so a stopped-but-unreleasable stream can be told apart from a
     * paused one.
     *
     * <p>Transitions, and what each does to the transport's RT-clock claim
     * ({@link Transport#setRealTimeClockActive(boolean)}):</p>
     * <ul>
     *   <li>{@code CLOSED → RUNNING}: a ladder rung's {@code open} succeeded;
     *       the claim is taken <em>before</em> the render pump starts (the
     *       pump may call {@link #processBlock} before the start call
     *       returns).</li>
     *   <li>{@code RUNNING → CLOSED} / {@code RUNNING → RELEASE_PENDING} on a
     *       start failure: the claim is released (draining any seek queued in
     *       the window), then the handle is closed — {@code CLOSED} if that
     *       close succeeded, {@code RELEASE_PENDING} if not.</li>
     *   <li>{@code RUNNING → PAUSED} ({@link #pauseAudioOutput()}): releases
     *       the claim. {@code PAUSED → RUNNING} ({@link #startAudioOutput()}):
     *       claims before the pump start; a failed resume returns to
     *       {@code PAUSED} and releases.</li>
     *   <li>{@code RUNNING|PAUSED|RELEASE_PENDING → CLOSED}
     *       ({@link #stopAudioOutput()}, close succeeded): releases the claim.
     *       {@code RUNNING|PAUSED → RELEASE_PENDING} (close failed, callback
     *       reported inactive): releases the claim. Close failed with the
     *       callback active or its state unknown: state and claim are
     *       preserved for a later retry.</li>
     *   <li>{@code RELEASE_PENDING → CLOSED} ({@link #startAudioOutput()}
     *       retrying the close before a fresh open): the claim is already
     *       released; nothing changes until the fresh stream starts.</li>
     *   <li>{@code RUNNING|PAUSED|RELEASE_PENDING → CLOSED}
     *       ({@link #setStreamingProvision(StreamingProvision)} re-pointing
     *       the engine at a provision that no longer carries the tracked
     *       backend instance): the outgoing backend's handle is closed
     *       best-effort and abandoned if that fails — the controller owns
     *       the outgoing instances' lifecycles — and the claim is
     *       released.</li>
     * </ul>
     */
    private enum StreamState {
        /** No backend stream handle is held. */
        CLOSED,
        /**
         * The stream is open and started (or being started): the callback may
         * call {@link #processBlock(float[][], float[][], int)}, and the RT
         * clock is claimed.
         */
        RUNNING,
        /**
         * The stream is open but stopped via {@link #pauseAudioOutput()};
         * resumable, clock released.
         */
        PAUSED,
        /**
         * The stream is stopped (callback known inactive) but
         * {@code closeStream()} failed, so the backend still owns the handle.
         * The clock is released; the handle must be released by a later close
         * retry before any new stream can be opened. Not resumable, and not
         * reported by {@link #isStreamOpen()} — to callers, output is simply
         * stopped; the retained handle is the engine's own business.
         */
        RELEASE_PENDING
    }

    // Audio output stream state
    private volatile StreamState streamState = StreamState.CLOSED;

    // Unified per-block render pipeline shared by live playback and offline
    // export. Owns the pre-allocated mix, per-track, and return-bus buffers.
    private RenderPipeline renderPipeline;

    // MIDI track renderer for SoundFont synthesis (volatile for safe stop() from UI thread)
    private volatile MidiTrackRenderer midiTrackRenderer;

    // The whole transport/mixer/tracks project graph is published to the
    // audio thread as ONE volatile store of an immutable record (story 314
    // review). Three separate volatile fields allowed processBlock to read
    // the OLD transport (playbackActive gate) alongside the NEW tracks and
    // mixer mid-publish — a hybrid graph. One field makes that impossible.
    private volatile EngineGraph graph = EngineGraph.EMPTY;

    // Serializes the graph mutators (setGraph / setTracks) so concurrent
    // read-modify-writes cannot lose an update. The RT read path NEVER
    // touches this lock — processBlock reads the volatile graph field only.
    private final Object graphLock = new Object();

    // Optional callback invoked from processBlock when recording is active
    private volatile RecordingCallback recordingCallback;

    // Optional performance monitor for CPU load and underrun tracking
    private volatile PerformanceMonitor performanceMonitor;

    // Optional per-track CPU budget enforcer for graceful degradation
    private volatile TrackCpuBudgetEnforcer cpuBudgetEnforcer;

    // Process-wide cache of sample-rate-converted clip buffers (story 126).
    // The render pipeline consults this cache before reading each clip's
    // audio data so clips imported at a rate different from the session
    // rate are JIT resampled rather than pitch-shifted.
    private volatile SampleRateConversionCache srcCache = new SampleRateConversionCache();

    // Optional registry of input-level monitors (story 137). When set, the
    // engine taps the raw input signal per armed track ahead of any
    // processing and feeds it through the track's monitor so the mixer's
    // input-meter column and the arrangement-view clip indicator stay live.
    private volatile InputLevelMonitorRegistry inputLevelMonitorRegistry;

    // Metronome click-generation pipeline (story 136). All three are
    // published volatilely so the audio thread sees a consistent view
    // without locking. The router writes its side output via
    // {@link AudioBackend#writeToChannel(int, float[])} so it requires
    // the SDK backend slot above; cue-bus contributions are written to
    // the cue bus's hardware output stereo pair on the same backend.
    private volatile Metronome metronome;
    private volatile MetronomeSideOutputRouter metronomeSideOutputRouter;
    private volatile CueBusManager cueBusManager;

    // Multi-core graph scheduling state (story 125). The pool size is
    // locked at start(); changing it requires a stop/start cycle. The
    // scheduler is also installed on the mixer so its mixDown() path
    // dispatches independent insert chains across the worker pool.
    private volatile AudioEngineSettings engineSettings = AudioEngineSettings.defaults();
    private AudioWorkerPool workerPool;
    private AudioGraphScheduler graphScheduler;

    /**
     * Creates a new audio engine with the specified format.
     *
     * @param format the audio format configuration
     */
    public AudioEngine(AudioFormat format) {
        this.format = Objects.requireNonNull(format, "format must not be null");
        this.masterChain = new EffectsChain();
    }

    /**
     * Creates a new audio engine with the specified format and engine
     * settings (story 125 — Multi-Core Parallel Audio Graph Processing).
     *
     * <p>The {@link AudioEngineSettings#workerPoolSize() worker-pool size}
     * is locked at {@link #start()} time; changing it later requires
     * stopping and restarting the engine.</p>
     *
     * @param format         the audio format configuration
     * @param engineSettings the engine settings; must not be null
     */
    public AudioEngine(AudioFormat format, AudioEngineSettings engineSettings) {
        this.format = Objects.requireNonNull(format, "format must not be null");
        this.masterChain = new EffectsChain();
        this.engineSettings = Objects.requireNonNull(
                engineSettings, "engineSettings must not be null");
    }

    /**
     * Starts the audio engine. If already running, this method is a no-op.
     *
     * <p>Pre-allocates all processing buffers so that the audio callback
     * path is allocation-free.</p>
     *
     * @return {@code true} if the engine was started, {@code false} if already running
     */
    public boolean start() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        int channels = format.channels();
        int frames = format.bufferSize();

        // Pre-allocate the unified render pipeline (owns mix/track/return buffers)
        renderPipeline = new RenderPipeline(format, MAX_TRACKS, frames);
        renderPipeline.setSampleRateConversionCache(srcCache);
        renderPipeline.setSrcQualityTier(srcQualityTier);

        // Pre-allocate the buffer pool (8 buffers for intermediate processing)
        bufferPool = new AudioBufferPool(8, channels, frames);

        // Pre-allocate intermediate buffers in the master effects chain
        masterChain.allocateIntermediateBuffers(channels, frames);

        // Pre-allocate intermediate buffers for mixer channel insert effects
        Mixer currentMixer = this.graph.mixer();
        if (currentMixer != null) {
            currentMixer.prepareForPlayback(channels, frames);
        }

        // Pre-allocate MIDI track renderer for SoundFont synthesis
        midiTrackRenderer = new MidiTrackRenderer(format.sampleRate(), frames);

        // Story 125: spin up the multi-core graph scheduler and install it
        // on the mixer so independent per-channel insert chains run on the
        // worker pool. A pool size of 1 is treated as a request to disable
        // parallelism — we skip pool/scheduler creation entirely so the
        // mixer falls back to its sequential mixDown path with zero
        // coordination overhead.
        AudioEngineSettings settings = this.engineSettings;
        int poolSize = settings.workerPoolSize();
        if (poolSize > 1) {
            workerPool = new AudioWorkerPool(poolSize);
            graphScheduler = new AudioGraphScheduler(
                    workerPool, MAX_TRACKS, settings.minParallelBlockSize());
            if (currentMixer != null) {
                currentMixer.setGraphScheduler(graphScheduler);
            }
        }

        return true;
    }

    /**
     * Stops the audio engine, first quiescing the render drive: a still
     * {@link StreamState#RUNNING} stream's pump is stopped and joined, the
     * stream is marked {@link StreamState#RELEASE_PENDING}, and the
     * transport's RT-clock claim is released (draining any queued seek).
     * If not running, this method is a no-op.
     *
     * <p>The quiesce happens BEFORE the running flag is cleared — i.e. while
     * {@link #processBlock(float[][], float[][], int)} is still legal — so a
     * pump caught mid-block finishes that block normally instead of throwing
     * on the freshly cleared flag.</p>
     *
     * <p>Why the quiesce exists (story 316 review): when an earlier
     * {@link #stopAudioOutput()} took its {@code !stopPump()} early return
     * (the bounded join timed out), the state stayed {@code RUNNING} with an
     * orphaned pump still alive. Clearing {@code running} without touching
     * that pump killed it on its next {@code processBlock}, while
     * {@link #startAudioOutput()}'s {@code state == RUNNING} early return then
     * refused to ever restart it — permanent silence behind a UI still
     * reporting RUNNING.</p>
     *
     * <p>{@link StreamState#RELEASE_PENDING} — not {@link StreamState#PAUSED}
     * — is the honest post-state: the backend handle is still held and must
     * be released before any new open, {@link #isStreamOpen()} correctly
     * reports {@code false} (output is stopped and not resumable), and
     * {@link #startAudioOutput()} already releases a retained handle and then
     * opens <em>fresh</em> through the ladder — which is exactly what a
     * caller that changed the format while stopped needs. A pause-shaped
     * PAUSED would instead resume a stream opened at the old format.</p>
     *
     * @return {@code true} if the engine was stopped, {@code false} if already stopped
     */
    public boolean stop() {
        if (!running.get()) {
            return false;
        }
        if (this.openBackend != null && streamState == StreamState.RUNNING) {
            // Quiesce while processBlock is still legal (see the javadoc).
            boolean joined = stopPump();
            streamState = StreamState.RELEASE_PENDING;
            releaseTransportClock();
            if (!joined) {
                LOG.severe("Engine stop: the render pump did not join in time and may"
                        + " still be inside processBlock — the pump reference is kept so"
                        + " the next open re-joins it instead of starting a second render"
                        + " thread; the backend handle is retained for release");
            }
        }
        if (!running.compareAndSet(true, false)) {
            return false;
        }
        if (midiTrackRenderer != null) {
            midiTrackRenderer.close();
            midiTrackRenderer = null;
        }
        // Story 125: tear down the multi-core graph scheduling state.
        // Detach the scheduler from the mixer first so subsequent mixDown
        // calls (e.g. via offline export) revert to the single-threaded
        // path even if the mixer reference outlives the engine restart.
        Mixer currentMixer = this.graph.mixer();
        if (currentMixer != null && currentMixer.getGraphScheduler() == graphScheduler) {
            currentMixer.setGraphScheduler(null);
        }
        graphScheduler = null;
        if (workerPool != null) {
            workerPool.close();
            workerPool = null;
        }
        return true;
    }

    /**
     * Returns whether the audio engine is currently running.
     *
     * @return {@code true} if running
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Returns the audio format used by this engine.
     *
     * @return the audio format
     */
    public AudioFormat getFormat() {
        return format;
    }

    /**
     * Replaces the audio format. Must only be called while the engine is
     * stopped; a subsequent {@link #start()} will re-allocate buffers to
     * match the new format's channel count and buffer size.
     *
     * @param format the new audio format (must not be {@code null})
     * @throws IllegalStateException if the engine is currently running
     */
    public void setFormat(AudioFormat format) {
        if (running.get()) {
            throw new IllegalStateException("Cannot change format while engine is running");
        }
        Objects.requireNonNull(format, "format must not be null");
        // Drop every cached SRC entry when the session sample rate
        // changes — otherwise stale conversions targeting the old rate
        // would be replayed at the new rate (story 126).
        if (this.format == null || this.format.sampleRate() != format.sampleRate()) {
            srcCache.invalidateAll();
        }
        this.format = format;
    }

    /**
     * Returns the master effects chain applied to the final mix output.
     *
     * @return the master effects chain
     */
    public EffectsChain getMasterChain() {
        return masterChain;
    }

    /**
     * Returns the MIDI track renderer used for SoundFont synthesis, or
     * {@code null} if the engine has not been started.
     *
     * @return the MIDI track renderer
     */
    MidiTrackRenderer getMidiTrackRenderer() {
        return midiTrackRenderer;
    }

    /**
     * Replaces the MIDI track renderer. Package-private for testing — allows
     * tests to inject a custom renderer without reflection.
     *
     * @param renderer the MIDI track renderer to use, or {@code null}
     */
    void setMidiTrackRenderer(MidiTrackRenderer renderer) {
        this.midiTrackRenderer = renderer;
    }

    /**
     * Returns the pre-allocated buffer pool, or {@code null} if the engine
     * has not been started.
     *
     * @return the buffer pool
     */
    public AudioBufferPool getBufferPool() {
        return bufferPool;
    }

    /**
     * Replaces the engine's streaming provision — the requested backend plus
     * its explicit fallback ladder (story 316). Must be called while the
     * engine is stopped.
     *
     * <p>Story 315 review, carried forward — a stream handle the engine
     * still tracks (running, paused, or retained after a failed close — see
     * {@link StreamState#RELEASE_PENDING}) belongs to a backend of the
     * <em>outgoing</em> provision. When the incoming provision no longer
     * carries that backend instance, this method first closes the tracked
     * stream best-effort — logging a warning naming the backend if that
     * close fails, because the handle is then being abandoned — and marks
     * the stream {@link StreamState#CLOSED}, releasing the transport's RT
     * clock (which drains any queued seek). A provision that still carries
     * the tracked instance leaves the stream state untouched: a paused
     * stream stays paused and resumable.</p>
     *
     * <p>Closing the outgoing provision's backend <em>instances</em> is the
     * caller's job — the controller owns backend lifecycles.</p>
     *
     * @param provision the new provision, or {@code null} to stream nothing
     *                  (engine-only mode)
     * @throws IllegalStateException if the engine is currently running
     */
    public void setStreamingProvision(StreamingProvision provision) {
        if (running.get()) {
            throw new IllegalStateException(
                    "Cannot change streaming provision while engine is running");
        }
        AudioBackend outgoing = this.openBackend;
        if (streamState != StreamState.CLOSED && outgoing != null
                && !ladderContains(provision, outgoing)) {
            abandonStreamOnOutgoingBackend(outgoing);
        }
        this.streamingProvision = provision;
    }

    /**
     * Returns the currently installed streaming provision, or {@code null}
     * when the engine streams nothing.
     */
    public StreamingProvision getStreamingProvision() {
        return streamingProvision;
    }

    private static boolean ladderContains(StreamingProvision provision, AudioBackend backend) {
        if (provision == null) {
            return false;
        }
        for (BackendStreamRung rung : provision.ladder()) {
            if (rung.backend() == backend) {
                return true;
            }
        }
        return false;
    }

    /**
     * Closes, best-effort, the stream the engine still tracks on a backend
     * that the incoming provision no longer carries, then forgets it: the
     * state becomes {@link StreamState#CLOSED} and the RT clock is released
     * whether or not the close succeeded, because no later retry could
     * reach this backend once the engine points elsewhere.
     *
     * <p>The stream HANDLE is abandoned here; the render PUMP is not. A pump
     * whose bounded join timed out keeps its reference (story 316 review) —
     * exactly what {@link #stopPump()} itself does on a failed join — so the
     * next {@link #startAudioOutput()} re-joins it through
     * {@link #requireQuiescedPump()} instead of starting a second render
     * thread into the one shared {@link RenderPipeline}. The abandoned loop
     * exits on the cleared {@code running} flag, which is already clear here:
     * {@link #setStreamingProvision(StreamingProvision)} refuses to run while
     * the engine is running.</p>
     */
    private void abandonStreamOnOutgoingBackend(AudioBackend outgoing) {
        if (!stopPump()) {
            LOG.severe("Audio stream abandoned on outgoing backend " + outgoing.name()
                    + " while its render pump had not joined: the pump reference is"
                    + " RETAINED so the next open re-joins it rather than starting a"
                    + " second render thread; its loop exits on the cleared running flag");
        }
        try {
            outgoing.close();
        } catch (RuntimeException closeFailure) {
            LOG.log(Level.WARNING,
                    "Abandoning the audio stream handle still held by " + outgoing.name()
                            + ": it could not be closed, and the engine is being re-pointed at"
                            + " another backend, so no later retry can release it",
                    closeFailure);
        }
        streamState = StreamState.CLOSED;
        clearOpenStream();
        releaseTransportClock();
    }

    /**
     * Returns the backend the engine would honestly answer questions about:
     * the OPEN stream's backend while a stream is open (running or paused),
     * else the provision's first rung's backend (the one the next open will
     * try first — the requested backend when it passed the app layer's
     * gate), else {@code null}. Capability queries, device enumeration and
     * metronome routing all read this and thereby follow the open stream by
     * construction (story 316, book &sect;2.4).
     *
     * @return the open backend, the first rung's backend, or {@code null}
     */
    public AudioBackend getBackend() {
        AudioBackend backend = this.openBackend;
        if (backend != null && isStreamOpen()) {
            return backend;
        }
        StreamingProvision provision = this.streamingProvision;
        return provision != null ? provision.firstRung().backend() : null;
    }

    /**
     * Returns the name of the backend carrying the OPEN stream, or empty
     * when no stream is open (a retained {@link StreamState#RELEASE_PENDING}
     * handle is not an open stream). This is the truth query behind honest
     * "active backend" reporting: it can never name a backend that is not
     * actually streaming.
     */
    public Optional<String> openStreamBackendName() {
        AudioBackend backend = this.openBackend;
        return backend != null && isStreamOpen()
                ? Optional.of(backend.name())
                : Optional.empty();
    }

    /**
     * Returns the device identity of the OPEN stream, or empty when no
     * stream is open. The identity is the winning ladder rung's stable
     * {@link DeviceId}, resolved by the backend on every open (&sect;3.2).
     */
    public Optional<DeviceId> openStreamDevice() {
        DeviceId device = this.openDevice;
        return device != null && isStreamOpen()
                ? Optional.of(device)
                : Optional.empty();
    }

    /** Forgets the tracked stream's backend, device and negotiated format. */
    private void clearOpenStream() {
        this.openBackend = null;
        this.openDevice = null;
        this.openSdkFormat = null;
    }

    // ── Audio output stream lifecycle ────────────────────────────────────────
    //
    // The backend stream's lifecycle is one volatile StreamState (see the enum
    // javadoc for the state machine and for which transitions claim or release
    // the transport's RT clock). Lifecycle methods run on non-RT threads only
    // (controller / UI thread, device-event thread) — never from the audio
    // callback or the render pump.

    /**
     * Starts audio output: walks the installed {@link StreamingProvision}'s
     * fallback ladder, opens the first rung whose backend accepts its device
     * and negotiated format, and starts the engine-owned render pump that
     * drives {@link #processBlock} at the device's buffer rate.
     *
     * <p>Per rung, the engine calls
     * {@link AudioBackend#negotiateFormat(com.benesquivelmusic.daw.sdk.audio.AudioFormat)}
     * and passes the negotiated format to
     * {@link AudioBackend#open(DeviceId, com.benesquivelmusic.daw.sdk.audio.AudioFormat, int)}.
     * Every FAILED hop publishes a {@link BackendFallbackEvent} on the
     * {@link com.benesquivelmusic.daw.core.event.EventBusPublisher EventBus}
     * (non-RT, on this caller's thread) so requested &ne; active is always a
     * visible fact; when every rung fails, the FIRST rung's exception — the
     * requested backend's failure, the actionable one — is rethrown.</p>
     *
     * <p>If the stream was previously paused via {@link #pauseAudioOutput()},
     * this resumes it (a fresh pump on the same open backend) without
     * re-opening the stream. If an earlier stop left a backend holding an
     * unreleasable stream handle (see {@link StreamState#RELEASE_PENDING}),
     * that close is retried first and the method fails — without opening a
     * second stream — when the handle still cannot be released.</p>
     *
     * <p>If no provision is installed, the engine is started without
     * hardware output: nothing drives the transport's clock, its RT-clock
     * claim is never taken and seeks apply inline. Honest playing states for
     * that case are story 317.</p>
     *
     * @throws AudioBackendException if every ladder rung failed to open (the
     *                               first rung's failure), or a previously
     *                               retained stream handle still cannot be
     *                               released, or a previous render pump has
     *                               not exited yet (see
     *                               {@link #requireQuiescedPump()} — this
     *                               open would otherwise put a SECOND thread
     *                               into the single shared render pipeline)
     */
    public void startAudioOutput() {
        StreamState state = this.streamState;
        if (state == StreamState.RUNNING) {
            return; // already running
        }
        requireQuiescedPump();
        if (state == StreamState.PAUSED) {
            resumeAudioOutput();
            return;
        }

        AudioBackend retained = this.openBackend;
        if (state == StreamState.RELEASE_PENDING && retained != null) {
            // Backends refuse open() while they still hold a handle, so the
            // retained one must go before a fresh open.
            releaseRetainedStreamHandle(retained);
        }

        // Ensure the engine is running (pre-allocates buffers)
        start();

        StreamingProvision provision = this.streamingProvision;
        if (provision == null) {
            LOG.info("No audio backend configured; playback without hardware output");
            return;
        }

        OpenedRung opened = openLadder(provision);
        this.openBackend = opened.rung().backend();
        this.openDevice = opened.rung().device();
        this.openSdkFormat = opened.negotiatedFormat();
        startOpenedStream(opened.rung().backend(), opened.negotiatedFormat());

        LOG.info("Audio output started via " + opened.rung().backend().name()
                + " (device: " + opened.rung().device().name() + ")");
    }

    /**
     * Guarantees no render pump can still be inside {@link #processBlock}
     * before this open creates another one (story 316 review). Retries the
     * bounded join of a pump whose previous stop timed out — the one case
     * where {@link #stopPump()} deliberately KEEPS the reference — and fails
     * the open outright when it still has not exited.
     *
     * <p>Two live pumps would both call {@link #processBlock}, which renders
     * through the SINGLE pre-allocated {@link RenderPipeline} allocated once
     * in {@link #start()} — one set of mix, per-track and return-bus buffers
     * shared by both threads. The result is interleaved garbage, not a
     * doubled signal, so this must be a hard failure rather than a warning.
     * The retry-join also covers the {@link StreamState#RELEASE_PENDING}
     * release that follows it: the backend is never closed out from under a
     * live pump thread.</p>
     *
     * @throws AudioBackendException if the previous pump has not exited yet
     */
    private void requireQuiescedPump() {
        if (!stopPump()) {
            throw new AudioBackendException(
                    "Cannot start audio output: the previous render pump has not exited yet"
                            + " and may still be inside processBlock; retry once it unblocks");
        }
    }

    /** A ladder rung that opened, with the format it actually opened at. */
    private record OpenedRung(
            BackendStreamRung rung,
            com.benesquivelmusic.daw.sdk.audio.AudioFormat negotiatedFormat) {
    }

    /**
     * Walks the provision's ladder in order. First successful open wins.
     * Publishes one {@link BackendFallbackEvent} per failed hop — after the
     * winner is known, so the events can honestly name the rung that ended
     * up carrying the stream (or the literal {@code "none"}).
     *
     * @throws RuntimeException the FIRST rung's failure when every rung failed
     */
    private OpenedRung openLadder(StreamingProvision provision) {
        com.benesquivelmusic.daw.sdk.audio.AudioFormat requested =
                new com.benesquivelmusic.daw.sdk.audio.AudioFormat(
                        format.sampleRate(), format.channels(), format.bitDepth());
        RuntimeException firstFailure = null;
        List<String> failedHopCauses = new ArrayList<>();
        for (BackendStreamRung rung : provision.ladder()) {
            try {
                com.benesquivelmusic.daw.sdk.audio.AudioFormat negotiated =
                        rung.backend().negotiateFormat(requested);
                rung.backend().open(rung.device(), negotiated, format.bufferSize());
                publishFallbackEvents(provision, failedHopCauses,
                        rung.backend().name(), rung.device().name());
                return new OpenedRung(rung, negotiated);
            } catch (RuntimeException hopFailure) {
                if (firstFailure == null) {
                    firstFailure = hopFailure;
                }
                failedHopCauses.add(causeMessage(hopFailure));
                LOG.log(Level.WARNING,
                        "Backend ladder hop failed: " + rung.backend().name()
                                + " could not open device '" + rung.device().name() + "'",
                        hopFailure);
            }
        }
        publishFallbackEvents(provision, failedHopCauses, "none", "none");
        throw firstFailure;
    }

    /**
     * Publishes one {@link BackendFallbackEvent} per failed ladder hop.
     * Non-RT context — runs on the lifecycle caller's thread.
     */
    private static void publishFallbackEvents(StreamingProvision provision,
                                              List<String> failedHopCauses,
                                              String activeBackend,
                                              String activeDevice) {
        for (String cause : failedHopCauses) {
            EventBusPublisher.publish(new BackendFallbackEvent(
                    provision.requestedBackendName(),
                    // The provision's carried requested device, NOT the first
                    // rung's: when the app layer's availability/streaming gate
                    // rejected the requested backend, the ladder already starts
                    // on a FALLBACK rung, so the first rung's device belongs to
                    // that fallback and would be paired with the user's
                    // requested backend name here (story 316 review).
                    provision.requestedDevice().name(),
                    activeBackend,
                    activeDevice,
                    cause));
        }
    }

    private static String causeMessage(RuntimeException failure) {
        String message = failure.getMessage();
        return message != null ? message : failure.toString();
    }

    /**
     * Starts the render pump on the backend a ladder rung has just opened,
     * claiming the transport's RT clock <em>before</em> the pump start and
     * unwinding both the claim and the handle when the start fails.
     *
     * <p>Story 315 review, carried onto the pump — the pump may call
     * {@link #processBlock} before its start call returns, so claiming
     * afterwards would leave a window in which the render thread advanced
     * the transport while UI seeks still applied inline. Claiming first
     * closes that window: a seek issued in it is queued and applied by the
     * first rendered block. Should the start then fail, the claim is
     * released first — which drains that queued seek inline instead of
     * stranding it — and the handle is given back best-effort: a close that
     * also fails leaves the stream {@link StreamState#RELEASE_PENDING} and
     * is attached as a suppressed exception, never masking the start
     * failure. The unwind covers <em>every</em> throwable from the start
     * call — a start that escaped it would leave the engine {@code RUNNING}
     * with the clock claimed and nothing driving, a permanent wedge.</p>
     *
     * @throws RuntimeException the pump-start failure, rethrown as-is after
     *                          the unwind (an {@link Error} unwinds the same
     *                          way)
     */
    private void startOpenedStream(AudioBackend backend,
                                   com.benesquivelmusic.daw.sdk.audio.AudioFormat negotiatedFormat) {
        streamState = StreamState.RUNNING;
        claimTransportClock();
        try {
            EngineStreamPump newPump =
                    new EngineStreamPump(backend, this, format, negotiatedFormat);
            newPump.start();
            this.pump = newPump;
        } catch (RuntimeException | Error startFailure) {
            // The pump is not running; the handle is still held until the
            // close below succeeds.
            streamState = StreamState.RELEASE_PENDING;
            releaseTransportClock();
            try {
                backend.close();
                streamState = StreamState.CLOSED;
                clearOpenStream();
            } catch (RuntimeException closeFailure) {
                startFailure.addSuppressed(closeFailure);
                LOG.log(Level.WARNING,
                        "Audio stream failed to start and its handle could not be released;"
                                + " the release is retried by the next start or stop",
                        closeFailure);
            }
            throw startFailure;
        }
    }

    /**
     * Retries the close a previous stop could not complete, so the backend
     * no longer owns the stale handle and a fresh stream can be opened.
     * Only called in {@link StreamState#RELEASE_PENDING}, where the pump is
     * known stopped and the clock is already released.
     *
     * @throws AudioBackendException if the handle still cannot be released;
     *                               the state stays {@code RELEASE_PENDING}
     */
    private void releaseRetainedStreamHandle(AudioBackend backend) {
        try {
            backend.close();
        } catch (RuntimeException closeFailure) {
            throw new AudioBackendException(
                    "Cannot open a new audio stream: the previous stream's handle is still"
                            + " held by " + backend.name()
                            + " and could not be released",
                    closeFailure);
        }
        streamState = StreamState.CLOSED;
        clearOpenStream();
    }

    /**
     * Stops and joins the render pump, if one is running. Never throws.
     *
     * @return {@code true} when no pump thread can call {@link #processBlock}
     *         any more (no pump existed, or the join was confirmed); on a
     *         timed-out join the pump reference is KEPT so a later stop can
     *         retry the join once the abandoned loop exits on its cleared
     *         running flag
     */
    private boolean stopPump() {
        EngineStreamPump current = this.pump;
        if (current == null) {
            return true;
        }
        boolean joined = current.stop();
        if (joined) {
            this.pump = null;
        }
        return joined;
    }

    /**
     * Stops the audio output stream and closes it. Best-effort: backend
     * failures are logged, never thrown.
     *
     * <p>Story 316 — the engine owns the render drive: the pump is stopped
     * and joined first. Only a <em>confirmed</em> join proves nothing can
     * call {@link #processBlock} any more; when the bounded join times out
     * (the pump thread may still be mid-{@code processBlock}), the story-315
     * conservative-preserve rule applies — the transport's RT-clock claim is
     * kept, the backend stays open, and the state stays
     * {@link StreamState#RUNNING} so a later stop retries once the abandoned
     * loop exits on its cleared running flag. After a confirmed join the
     * claim is released (draining any seek queued microseconds earlier
     * rather than stranding it — the story-315 bug class) and the backend
     * close is attempted best-effort: success is {@link StreamState#CLOSED};
     * a refused close is {@link StreamState#RELEASE_PENDING} with the
     * backend retained, and the close is retried by the next stop or before
     * the next open.</p>
     *
     * <p>A subsequent call to {@link #startAudioOutput()} will open a fresh
     * stream once the handle is released.</p>
     */
    public void stopAudioOutput() {
        AudioBackend backend = this.openBackend;
        if (backend == null || streamState == StreamState.CLOSED) {
            return;
        }
        if (!stopPump()) {
            LOG.severe("Audio output stop deferred: the render pump did not join in time"
                    + " and may still be inside processBlock — the RT-clock claim and the"
                    + " backend handle are preserved; retry the stop");
            return;
        }
        releaseTransportClock();
        try {
            backend.close();
            streamState = StreamState.CLOSED;
            clearOpenStream();
        } catch (RuntimeException closeFailure) {
            // The backend kept the handle; the engine keeps tracking it so a
            // later stop — or the next start — retries the close.
            streamState = StreamState.RELEASE_PENDING;
            LOG.log(Level.WARNING,
                    "Error closing audio output stream; the backend retains the handle"
                            + " and the close is retried by the next start or stop",
                    closeFailure);
        }
    }

    /**
     * Starts audio I/O for recording. Story 316 — the SDK
     * {@link AudioBackend#open(DeviceId, com.benesquivelmusic.daw.sdk.audio.AudioFormat, int)}
     * seam is inherently duplex (backends open capture when the provisioned
     * device has it, publishing blocks on
     * {@link AudioBackend#inputBlocks()}), so this runs the same ladder,
     * device identity and pump as {@link #startAudioOutput()}. The record
     * path therefore opens the <em>provisioned</em> device — no index
     * parameter exists any more. Per-track input CHANNEL routing stays on
     * {@code Track.getInputRouting()}; multi-device capture routing is story
     * 326, which is why this method keeps its own name and seam.
     *
     * <p>Unlike {@code startAudioOutput()}, a stream that is already open
     * (running or paused) is <em>closed first</em> and reopened: the open
     * stream may predate the input device — or have been opened by a
     * backend/rung without capture — and record must never silently proceed
     * with zero input channels. When that close does not release the
     * backend's handle, this method fails instead of attempting a second
     * open that the production backends reject (the pre-316
     * {@code startAudioInputOutput(int)} contract).</p>
     *
     * @throws AudioBackendException if every ladder rung failed to open, or
     *                               the previous stream could not be closed
     *                               first, or a previously retained stream
     *                               handle still cannot be released
     */
    public void startAudioInputOutput() {
        if (streamState != StreamState.CLOSED) {
            stopAudioOutput();
            if (streamState != StreamState.CLOSED) {
                throw new AudioBackendException(
                        "Cannot open a full-duplex audio stream: the previous stream's"
                                + " handle is still held by the backend and could not be"
                                + " released");
            }
        }
        startAudioOutput();
    }

    /**
     * Pauses audio output by stopping the render pump without closing the
     * backend stream, allowing a fast resume via {@link #startAudioOutput()}
     * (a fresh pump on the same open backend). Only a
     * {@link StreamState#RUNNING} stream can be paused; in every other state
     * this is a no-op.
     *
     * <p>Like {@link #stopAudioOutput()}, a pump whose bounded join timed
     * out (possibly still inside {@link #processBlock}) preserves the
     * RT-clock claim and the {@code RUNNING} state — a later pause or stop
     * retries once the abandoned loop has exited.</p>
     */
    public void pauseAudioOutput() {
        if (this.openBackend != null && streamState == StreamState.RUNNING) {
            if (!stopPump()) {
                LOG.severe("Audio output pause deferred: the render pump did not join in"
                        + " time and may still be inside processBlock — the RT-clock claim"
                        + " is preserved; retry the pause or stop");
                return;
            }
            streamState = StreamState.PAUSED;
            releaseTransportClock();
        }
    }

    /**
     * Returns whether audio output is live or resumable — the stream is
     * {@link StreamState#RUNNING} or {@link StreamState#PAUSED}. This is what
     * the settings-apply path reads to decide whether to restart output after
     * a reconfigure.
     *
     * <p>A stream whose close failed and whose backend handle the engine still
     * retains ({@link StreamState#RELEASE_PENDING}) is <em>not</em> reported
     * open: output is stopped and not resumable, so a caller that restarted
     * output on this answer would start hardware against a stopped transport.
     * The engine releases that retained handle itself — on the next
     * {@link #stopAudioOutput()}, or before the next open.</p>
     *
     * @return {@code true} only in {@link StreamState#RUNNING} or
     *         {@link StreamState#PAUSED}
     */
    public boolean isStreamOpen() {
        StreamState state = this.streamState;
        return state == StreamState.RUNNING || state == StreamState.PAUSED;
    }

    /**
     * Returns whether the audio output stream is paused via
     * {@link #pauseAudioOutput()} and resumable via {@link #startAudioOutput()}.
     * A stream retained after a failed close is <em>not</em> paused: it is
     * stopped, but not resumable.
     *
     * @return {@code true} only in {@link StreamState#PAUSED}
     */
    public boolean isStreamPaused() {
        return streamState == StreamState.PAUSED;
    }

    /**
     * Restarts a paused stream: claims the RT clock, then starts a fresh
     * render pump on the SAME still-open backend (see
     * {@link #startOpenedStream(AudioBackend, com.benesquivelmusic.daw.sdk.audio.AudioFormat)}
     * for why the claim precedes the start). On a start failure of any kind
     * the stream stays open and paused (the resume is retryable) and the
     * claim is released again.
     *
     * <p>The engine itself is (re)started first (story 316 review): a stream
     * can be {@link StreamState#PAUSED} across an {@link #stop()}, because
     * {@link #pauseAudioOutput()} leaves the engine running and {@code stop()}
     * only quiesces a {@link StreamState#RUNNING} stream. Resuming against a
     * stopped engine would start a pump whose every {@link #processBlock}
     * throws and whose loop then exits — silence. {@link #start()} is a no-op
     * when the engine is already running, and re-allocates the render buffers
     * when it is not.</p>
     *
     * @throws RuntimeException the pump-start failure, rethrown as-is (an
     *                          {@link Error} unwinds the same way)
     */
    private void resumeAudioOutput() {
        AudioBackend backend = this.openBackend;
        com.benesquivelmusic.daw.sdk.audio.AudioFormat negotiated = this.openSdkFormat;
        if (backend != null && negotiated != null && streamState == StreamState.PAUSED) {
            // No-op when already running; re-allocates the render pipeline
            // when this resume follows a stop() (see the javadoc).
            start();
            streamState = StreamState.RUNNING;
            claimTransportClock();
            try {
                EngineStreamPump newPump =
                        new EngineStreamPump(backend, this, format, negotiated);
                newPump.start();
                this.pump = newPump;
            } catch (RuntimeException | Error startFailure) {
                streamState = StreamState.PAUSED;
                releaseTransportClock();
                throw startFailure;
            }
        }
    }

    // ── Real-time clock ownership (story 315 review) ─────────────────────

    /**
     * Returns {@code true} while a backend stream is {@link StreamState#RUNNING},
     * i.e. while {@link #processBlock(float[][], float[][], int)} is (or is
     * about to be) called by the backend and therefore driving
     * {@link Transport#advancePosition(double)}.
     *
     * <p>This — not {@link Transport#getState()} — is the truth condition
     * behind {@link Transport#setRealTimeClockActive(boolean)}. The transport
     * can be {@code PLAYING} with no stream at all:
     * {@link #startAudioOutput()} returns early when no provision is
     * installed, and the UI starts the transport regardless (honest playing
     * states are story 317).</p>
     */
    private boolean callbackIsDriving() {
        return streamState == StreamState.RUNNING;
    }

    /**
     * Claims the RT clock on the currently published transport. Called only
     * from the paths that are about to start a backend stream registered on
     * {@link #processBlock(float[][], float[][], int)} — immediately before
     * the start call, so the very first callback block cannot race an inline
     * seek. The same paths release the claim again should the start fail.
     */
    private void claimTransportClock() {
        Transport transport = this.graph.transport();
        if (transport != null) {
            transport.setRealTimeClockActive(true);
        }
    }

    /**
     * Releases the RT clock on the currently published transport, draining any
     * seek queued while the claim was held. Called from every path that stops
     * or pauses the stream, and from the start paths when the backend refused
     * to start the stream.
     */
    private void releaseTransportClock() {
        Transport transport = this.graph.transport();
        if (transport != null) {
            transport.setRealTimeClockActive(false);
        }
    }

    // ── Project graph publication (story 314) ────────────────────────────

    /**
     * Atomically publishes the whole project graph — transport, mixer, and
     * track list — to the audio thread as a single volatile store of an
     * immutable snapshot record.
     *
     * <p>This is the {@link EngineBinder}'s publish seam. Because all three
     * components land in one store, the next
     * {@link #processBlock(float[][], float[][], int)} observes either the
     * entire previous graph or the entire new one — never a hybrid such as
     * the old (still playing) transport gating playback over the new tracks
     * and mixer. The same source-scan conformance sentinel that restricts
     * {@link #setTracks(List)} applies to this method: only
     * {@code EngineBinder} may call it from production code.</p>
     *
     * <p>Non-RT preparation runs before the store (see
     * {@link #handOffMixer(Mixer, Mixer)}): the graph scheduler is detached
     * from the outgoing mixer (story 125) and, when the engine is running,
     * the incoming mixer is prepared for playback and the scheduler
     * re-installed (story 314 review) so the audio thread never renders
     * through an unprepared mixer.</p>
     *
     * <p>The RT-clock claim (story 315 review) is handed over with the graph:
     * the outgoing transport is released — draining any seek it had queued —
     * and the incoming one is claimed only when a backend stream is actually
     * running, so an {@link EngineBinder} rebind mid-playback leaves exactly
     * one claimed transport, and a rebind with no stream open leaves none.</p>
     *
     * <p>Lifecycle-thread only — never call from the audio callback.</p>
     *
     * @param transport the transport, or {@code null} to disable playback rendering
     * @param mixer     the mixer, or {@code null} to disable playback rendering
     * @param tracks    an immutable track-list snapshot, or {@code null} to
     *                  disable playback rendering
     */
    public void setGraph(Transport transport, Mixer mixer, List<Track> tracks) {
        synchronized (graphLock) {
            handOffMixer(this.graph.mixer(), mixer);
            Transport outgoing = this.graph.transport();
            if (outgoing != null && outgoing != transport) {
                outgoing.setRealTimeClockActive(false);
            }
            this.graph = new EngineGraph(transport, mixer, tracks);
            if (transport != null) {
                transport.setRealTimeClockActive(callbackIsDriving());
            }
        }
    }

    /**
     * Returns the transport from the current graph snapshot, or {@code null}.
     *
     * @return the transport
     */
    public Transport getTransport() {
        return graph.transport();
    }

    /**
     * Returns the mixer from the current graph snapshot, or {@code null}.
     *
     * @return the mixer
     */
    public Mixer getMixer() {
        return graph.mixer();
    }

    /**
     * Non-RT mixer hand-off run before a graph store (factored from the
     * pre-story-314-review {@code setMixer}). Detaches the graph scheduler
     * from the outgoing mixer (story 125) so stale references do not
     * attempt parallel dispatch after the pool is closed in {@link #stop()}
     * — safe even when the old mixer is null or no scheduler is installed.
     * When the engine is running, the INCOMING mixer is prepared BEFORE the
     * caller's volatile store: publishing first would let the RT thread
     * mixDown an unprepared mixer (null scratch buffers → NPE inside the
     * FFM upcall) for the whole multi-ms prepare window, and the scheduler
     * is re-installed so parallel insert dispatch keeps working after a
     * hot mixer swap.
     */
    private void handOffMixer(Mixer previousMixer, Mixer incomingMixer) {
        if (previousMixer != null && graphScheduler != null
                && previousMixer.getGraphScheduler() == graphScheduler) {
            previousMixer.setGraphScheduler(null);
        }
        if (incomingMixer != null && running.get()) {
            incomingMixer.prepareForPlayback(format.channels(), format.bufferSize());
            if (graphScheduler != null) {
                incomingMixer.setGraphScheduler(graphScheduler);
            }
        }
    }

    /**
     * Returns the total system latency introduced by plugin delay
     * compensation, in samples.
     *
     * <p>This is the maximum insert-chain latency across all mixer channels
     * and return buses. The transport can use this value to offset the
     * playback start position so that the first audible sample aligns
     * with beat 1.</p>
     *
     * @return the system latency in sample frames, or 0 if no mixer is configured
     */
    public int getSystemLatencySamples() {
        Mixer currentMixer = this.graph.mixer();
        return currentMixer != null ? currentMixer.getSystemLatencySamples() : 0;
    }

    /**
     * Replaces only the track list in the published graph, keeping the
     * current transport and mixer — a read-modify-write that still lands as
     * one volatile store of a fresh immutable snapshot. This is the
     * {@link EngineBinder}'s structural-change refresh seam (track
     * add/remove/move/duplicate); the source-scan conformance sentinel
     * restricts production callers to {@code EngineBinder}.
     *
     * <p>To avoid concurrent-modification issues, pass an immutable or
     * snapshot list. Lifecycle-thread only — never call from the audio
     * callback.</p>
     *
     * @param tracks the track list, or {@code null} to disable playback rendering
     */
    public void setTracks(List<Track> tracks) {
        synchronized (graphLock) {
            EngineGraph current = this.graph;
            this.graph = new EngineGraph(current.transport(), current.mixer(), tracks);
        }
    }

    /**
     * Returns the track list from the current graph snapshot, or {@code null}.
     *
     * @return the track list
     */
    public List<Track> getTracks() {
        return graph.tracks();
    }

    /**
     * Sets the callback invoked from {@link #processBlock(float[][], float[][], int)}
     * to capture input audio data during recording.
     *
     * @param callback the recording callback, or {@code null} to disable recording capture
     */
    public void setRecordingCallback(RecordingCallback callback) {
        this.recordingCallback = callback;
    }

    /**
     * Returns the currently configured recording callback, or {@code null}.
     *
     * @return the recording callback
     */
    public RecordingCallback getRecordingCallback() {
        return recordingCallback;
    }

    /**
     * Sets the performance monitor used to track CPU load and buffer underruns.
     *
     * <p>When set, the engine measures the time taken by each
     * {@link #processBlock(float[][], float[][], int)} call and reports it
     * to the monitor.</p>
     *
     * @param monitor the performance monitor, or {@code null} to disable monitoring
     */
    public void setPerformanceMonitor(PerformanceMonitor monitor) {
        this.performanceMonitor = monitor;
    }

    /**
     * Returns the currently configured performance monitor, or {@code null}.
     *
     * @return the performance monitor
     */
    public PerformanceMonitor getPerformanceMonitor() {
        return performanceMonitor;
    }

    /**
     * Sets the per-track CPU budget enforcer for graceful degradation.
     *
     * <p>When set, the engine measures the time taken by each track's mixer
     * processing and feeds the measurements to the enforcer. The enforcer
     * evaluates per-track and master budgets each block. When a track
     * persistently exceeds its budget, the enforcer applies the configured
     * {@link com.benesquivelmusic.daw.sdk.audio.performance.DegradationPolicy}
     * and publishes events for the UI.</p>
     *
     * @param enforcer the CPU budget enforcer, or {@code null} to disable
     */
    public void setCpuBudgetEnforcer(TrackCpuBudgetEnforcer enforcer) {
        this.cpuBudgetEnforcer = enforcer;
    }

    /**
     * Returns the currently configured per-track CPU budget enforcer, or
     * {@code null}.
     *
     * @return the CPU budget enforcer
     */
    public TrackCpuBudgetEnforcer getCpuBudgetEnforcer() {
        return cpuBudgetEnforcer;
    }

    /**
     * Returns the process-wide {@link SampleRateConversionCache} used
     * by the render pipeline to memoize JIT sample-rate conversions of
     * clips whose native rate differs from the session rate. Story 126.
     *
     * @return the cache, never {@code null}
     */
    public SampleRateConversionCache getSampleRateConversionCache() {
        return srcCache;
    }

    /**
     * Sets the SRC quality tier ({@code LOW} / {@code MEDIUM} /
     * {@code HIGH}) used by the cache when materializing a new
     * conversion. Story 126.
     *
     * @param tier quality tier (must not be {@code null})
     */
    public void setSrcQualityTier(
            com.benesquivelmusic.daw.sdk.audio.SampleRateConverter.QualityTier tier) {
        if (renderPipeline != null) {
            renderPipeline.setSrcQualityTier(tier);
        }
        this.srcQualityTier = Objects.requireNonNull(tier, "tier must not be null");
    }

    /** Returns the SRC quality tier currently in effect. */
    public com.benesquivelmusic.daw.sdk.audio.SampleRateConverter.QualityTier getSrcQualityTier() {
        return srcQualityTier;
    }

    /** Cached tier reapplied to the render pipeline on each {@link #start()}. */
    private volatile com.benesquivelmusic.daw.sdk.audio.SampleRateConverter.QualityTier srcQualityTier
            = com.benesquivelmusic.daw.sdk.audio.SampleRateConverter.QualityTier.MEDIUM;

    /**
     * Binds an {@link InputLevelMonitorRegistry} to the engine so that the
     * raw input signal for each armed track is tapped ahead of any
     * processing and fed through a per-track monitor (user story 137).
     *
     * <p>When set, every {@link #processBlock(float[][], float[][], int)}
     * call iterates the armed tracks and forwards the configured input-
     * channel slice to {@link InputLevelMonitor#processInputChannels} on
     * the matching monitor. The mixer and arrangement-view UI read the
     * resulting snapshots to drive the input-meter column and the clip
     * LED.</p>
     *
     * <p>Pass {@code null} to disable the tap (the engine then reverts to
     * pre-story-137 behavior — zero per-block overhead).</p>
     *
     * @param registry the registry, or {@code null} to disable
     */
    public void setInputLevelMonitorRegistry(InputLevelMonitorRegistry registry) {
        this.inputLevelMonitorRegistry = registry;
    }

    /**
     * Returns the currently bound input-level monitor registry, or {@code null}.
     */
    public InputLevelMonitorRegistry getInputLevelMonitorRegistry() {
        return inputLevelMonitorRegistry;
    }

    /**
     * Story 136 — sets the {@link Metronome} the audio callback uses to
     * generate a click on each scheduled beat (and subdivision) that
     * lands inside the current buffer.
     *
     * <p>Pass {@code null} to disable click generation entirely. The
     * field is {@code volatile} so the audio thread observes the
     * latest reference without locking.</p>
     *
     * @param metronome the metronome, or {@code null}
     */
    public void setMetronome(Metronome metronome) {
        this.metronome = metronome;
    }

    /**
     * Returns the currently bound metronome, or {@code null}.
     */
    public Metronome getMetronome() {
        return metronome;
    }

    /**
     * Story 136 — sets the {@link MetronomeSideOutputRouter} that gates
     * each generated click into (a) the main mix, (b) a direct hardware
     * channel via {@link AudioBackend#writeToChannel(int, float[])}, and
     * (c) any cue-bus contributions configured by the user.
     *
     * <p>The router is thread-safe: its internal cue-bus level map is
     * published via {@link java.util.concurrent.atomic.AtomicReference}
     * using copy-on-write semantics, so the UI thread can mutate levels
     * while the audio callback reads a consistent immutable snapshot
     * without locking and without risking
     * {@link java.util.ConcurrentModificationException}.</p>
     *
     * <p>Pass {@code null} to bypass routing — no click is summed into
     * the main mix and no side-output is written.</p>
     *
     * @param router the side-output router, or {@code null}
     */
    public void setMetronomeSideOutputRouter(MetronomeSideOutputRouter router) {
        this.metronomeSideOutputRouter = router;
    }

    /**
     * Returns the currently bound side-output router, or {@code null}.
     */
    public MetronomeSideOutputRouter getMetronomeSideOutputRouter() {
        return metronomeSideOutputRouter;
    }

    /**
     * Story 136 — sets the {@link CueBusManager} the audio callback consults
     * when summing cue-bus click contributions to each bus's hardware
     * output stereo pair. Pass {@code null} to skip cue-bus routing.
     *
     * @param cueBusManager the cue bus manager, or {@code null}
     */
    public void setCueBusManager(CueBusManager cueBusManager) {
        this.cueBusManager = cueBusManager;
    }

    /**
     * Returns the currently bound cue bus manager, or {@code null}.
     */
    public CueBusManager getCueBusManager() {
        return cueBusManager;
    }

    /**
     * Processes a single block of audio by delegating to the unified
     * {@link RenderPipeline}.
     *
     * <p>When a transport, mixer, and track list are configured and the
     * transport is in {@link TransportState#PLAYING} or
     * {@link TransportState#RECORDING} state, the pipeline renders clip
     * audio, applies automation, mixes through the mixer, and applies the
     * master effects chain. Otherwise, {@code inputBuffer} is passed
     * through the master effects chain (the original passthrough
     * behavior).</p>
     *
     * <p>This method is designed to be called from the audio callback
     * thread. The core track-mixdown/master-chain path performs zero
     * allocations and zero lock acquisitions — all buffers are
     * pre-allocated during {@link #start()}, and the
     * {@link RenderPipeline} reads from one atomic volatile snapshot of
     * the immutable transport/mixer/tracks graph (published whole via
     * {@link #setGraph(Transport, Mixer, List)}) plus volatile snapshots
     * of the MIDI renderer and other collaborators. When an enforcer
     * is present, per-track CPU timing occurs and the enforcer acquires
     * an internal lock for each measurement; the enforcer pre-allocates
     * its own buffers to minimize GC pressure on the audio thread.</p>
     *
     * <p><b>Allocation note (story 136):</b> when a metronome and
     * side-output router are attached and clicks are scheduled in this
     * block, bounded short-lived allocations occur for click generation
     * and routing buffers (see
     * {@link RenderPipeline#renderBlock(float[][], float[][], int,
     * Transport, Mixer, java.util.List, MidiTrackRenderer, EffectsChain,
     * RecordingCallback, PerformanceMonitor, TrackCpuBudgetEnforcer,
     * Metronome, MetronomeSideOutputRouter, CueBusManager,
     * AudioBackend)}).</p>
     *
     * @param inputBuffer  the input audio data {@code [channel][frame]}
     * @param outputBuffer the output audio data {@code [channel][frame]}
     * @param numFrames    the number of sample frames to process
     * @throws IllegalStateException if the engine is not running
     */
    @RealTimeSafe
    public void processBlock(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        if (!running.get()) {
            throw new IllegalStateException("Engine is not running");
        }

        // Snapshot volatile references once for this block so that the
        // UI thread cannot tear the configuration mid-render. The whole
        // transport/mixer/tracks graph arrives as ONE volatile load of an
        // immutable record (story 314 review), so a concurrent EngineBinder
        // publish can never expose a half-swapped hybrid graph.
        EngineGraph currentGraph = this.graph;
        Transport currentTransport = currentGraph.transport();
        Mixer currentMixer = currentGraph.mixer();
        List<Track> currentTracks = currentGraph.tracks();
        MidiTrackRenderer currentMidiRenderer = this.midiTrackRenderer;
        RecordingCallback cb = this.recordingCallback;
        PerformanceMonitor monitor = this.performanceMonitor;
        TrackCpuBudgetEnforcer enforcer = this.cpuBudgetEnforcer;
        InputLevelMonitorRegistry inputRegistry = this.inputLevelMonitorRegistry;
        Metronome currentMetronome = this.metronome;
        MetronomeSideOutputRouter currentRouter = this.metronomeSideOutputRouter;
        CueBusManager currentCueBusManager = this.cueBusManager;
        // The OPEN stream's backend (story 316): metronome writeToChannel
        // routing finally targets the stream that is actually playing.
        AudioBackend currentBackend = this.openBackend;

        // Story 137: tap the raw input signal per armed track BEFORE any
        // processing so the mixer's input-meter column and the clip LED
        // always reflect the converter-side signal (not post-gain / post-
        // inserts). No-op when no registry is bound or no track is armed.
        if (inputRegistry != null && inputBuffer != null && currentTracks != null) {
            tapArmedTrackInputs(inputRegistry, inputBuffer, numFrames, currentTracks);
        }

        renderPipeline.renderBlock(inputBuffer, outputBuffer, numFrames,
                currentTransport, currentMixer, currentTracks,
                currentMidiRenderer, masterChain, cb, monitor,
                enforcer,
                currentMetronome, currentRouter,
                currentCueBusManager, currentBackend);
    }

    /**
     * Iterates the armed tracks and forwards the raw input-channel slice
     * for each one to its {@link InputLevelMonitor}.
     *
     * <p>Allocation-free hot path: the only per-block state is the snapshot
     * volatile field read at the top of {@link
     * #processBlock(float[][], float[][], int)}. Monitors are looked up by
     * track id via {@link InputLevelMonitorRegistry#getOrCreate(String)},
     * which synchronizes internally but allocates only the first time a
     * given track is armed.</p>
     */
    @RealTimeSafe
    private static void tapArmedTrackInputs(InputLevelMonitorRegistry registry,
                                            float[][] inputBuffer,
                                            int numFrames,
                                            List<Track> currentTracks) {
        int numInputChannels = inputBuffer.length;
        if (numInputChannels == 0) {
            return;
        }
        for (int i = 0, n = currentTracks.size(); i < n; i++) {
            Track track = currentTracks.get(i);
            if (track == null || !track.isArmed()) {
                continue;
            }
            InputRouting routing = track.getInputRouting();
            if (routing == null || routing.isNone()) {
                continue;
            }
            int first = routing.firstChannel();
            int count = routing.channelCount();
            if (first < 0 || count <= 0 || first + count > numInputChannels) {
                // Routing points off the end of the actual input buffer —
                // e.g., user selected "Input 5-6" on a 2-in interface.
                // Skip silently so metering never throws from the audio
                // thread.
                continue;
            }
            InputLevelMonitor monitor = registry.getOrCreate(track.getId());
            monitor.processInputChannels(inputBuffer, first, count, numFrames);
        }
    }

    /**
     * Returns the unified render pipeline used by this engine. Package
     * private so that offline export callers (e.g., master rendering,
     * stem export, track bouncing) can construct their own pipeline or
     * reuse the engine’s to render with identical semantics.
     *
     * @return the render pipeline, or {@code null} if the engine has not
     *         been started
     */
    RenderPipeline getRenderPipeline() {
        return renderPipeline;
    }

    // ── Multi-core graph scheduling (story 125) ─────────────────────────

    /**
     * Returns the current multi-core engine settings (worker-pool size and
     * minimum parallel block size). Story 125.
     *
     * @return the engine settings, never {@code null}
     */
    public AudioEngineSettings getEngineSettings() {
        return engineSettings;
    }

    /**
     * Replaces the multi-core engine settings. Must only be called while
     * the engine is stopped — the worker-pool size locks at
     * {@link #start()} time so changing it requires a stop/start cycle.
     * Story 125.
     *
     * @param settings the new settings; must not be null
     * @throws IllegalStateException if the engine is currently running
     */
    public void setEngineSettings(AudioEngineSettings settings) {
        if (running.get()) {
            throw new IllegalStateException(
                    "Cannot change engine settings while engine is running");
        }
        this.engineSettings = Objects.requireNonNull(settings, "settings must not be null");
    }

    /**
     * Returns the {@link AudioWorkerPool} currently driving parallel insert
     * dispatch, or {@code null} if the engine is not running or
     * parallelism is disabled (worker-pool size = 1). Story 125.
     *
     * @return the worker pool, or {@code null}
     */
    public AudioWorkerPool getWorkerPool() {
        return workerPool;
    }

    /**
     * Returns the {@link AudioGraphScheduler} currently installed on the
     * mixer, or {@code null} if the engine is not running or parallelism
     * is disabled. Story 125.
     *
     * @return the graph scheduler, or {@code null}
     */
    public AudioGraphScheduler getGraphScheduler() {
        return graphScheduler;
    }

    /**
     * Returns the configured worker-pool size (including the audio
     * callback as the coordinator). Reflects {@link AudioEngineSettings}
     * even when the engine is stopped. A return value of {@code 1}
     * indicates parallelism is disabled.
     *
     * @return the worker-pool size; {@code >= 1}
     */
    public int getWorkerPoolSize() {
        return engineSettings.workerPoolSize();
    }

    /**
     * Returns the number of parallel insert tasks dispatched during the
     * most recent block — the live "threads in use" reading shown in the
     * UI performance area (story 125). Returns {@code 0} when parallelism
     * is disabled, the engine is not running, or the previous block fell
     * back to sequential execution.
     *
     * @return the dispatched task count for the previous block; {@code >= 0}
     */
    public int getActiveThreadCount() {
        AudioGraphScheduler scheduler = this.graphScheduler;
        return scheduler == null ? 0 : scheduler.getLastDispatchedTaskCount();
    }


    /**
     * Immutable whole-project graph snapshot (story 314 review). Published
     * with a single volatile store so the RT callback can never observe a
     * half-swapped graph. Every component is nullable — {@code null} means
     * playback rendering is disabled for that aspect. In particular,
     * {@code tracks} is deliberately NOT normalized to an empty list:
     * {@link #getTracks()} must keep returning {@code null} when unset.
     */
    private record EngineGraph(Transport transport, Mixer mixer, List<Track> tracks) {
        static final EngineGraph EMPTY = new EngineGraph(null, null, null);
    }

    /**
     * Callback interface invoked from the audio thread to capture input audio
     * data during recording.
     */
    @FunctionalInterface
    public interface RecordingCallback {

        /**
         * Called from the audio thread with captured input audio data.
         *
         * @param inputBuffer the input audio data {@code [channel][frame]}
         * @param numFrames   the number of sample frames captured
         */
        void onAudioCaptured(float[][] inputBuffer, int numFrames);
    }
}
