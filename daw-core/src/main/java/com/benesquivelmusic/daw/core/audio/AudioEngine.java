package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.audio.performance.TrackCpuBudgetEnforcer;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.performance.PerformanceMonitor;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.*;

import java.util.List;
import java.util.Objects;
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
 * when the transport's loop mode is enabled.</p>
 *
 * <p>An optional {@link NativeAudioBackend} can be attached to provide
 * low-latency audio I/O via PortAudio FFM bindings or Java Sound API.
 * Use {@link AudioBackendFactory} to obtain a backend instance.</p>
 */
public final class AudioEngine {

    private static final Logger LOG = Logger.getLogger(AudioEngine.class.getName());

    /** Maximum number of tracks supported for pre-allocated buffers. */
    static final int MAX_TRACKS = 64;

    private AudioFormat format;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final EffectsChain masterChain;
    private AudioBufferPool bufferPool;
    private NativeAudioBackend audioBackend;

    // Audio output stream state
    private volatile boolean streamOpen;
    private volatile boolean streamPaused;
    private volatile boolean backendInitialized;

    // Unified per-block render pipeline shared by live playback and offline
    // export. Owns the pre-allocated mix, per-track, and return-bus buffers.
    private RenderPipeline renderPipeline;

    // MIDI track renderer for SoundFont synthesis (volatile for safe stop() from UI thread)
    private volatile MidiTrackRenderer midiTrackRenderer;

    // Volatile references for lock-free UI ↔ audio thread communication
    private volatile Transport transport;
    private volatile Mixer mixer;
    private volatile List<Track> tracks;

    // Optional callback invoked from processBlock when recording is active
    private volatile RecordingCallback recordingCallback;

    // Optional performance monitor for CPU load and underrun tracking
    private volatile PerformanceMonitor performanceMonitor;

    // Optional per-track CPU budget enforcer for graceful degradation
    private volatile TrackCpuBudgetEnforcer cpuBudgetEnforcer;

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

        // Pre-allocate the buffer pool (8 buffers for intermediate processing)
        bufferPool = new AudioBufferPool(8, channels, frames);

        // Pre-allocate intermediate buffers in the master effects chain
        masterChain.allocateIntermediateBuffers(channels, frames);

        // Pre-allocate intermediate buffers for mixer channel insert effects
        Mixer currentMixer = this.mixer;
        if (currentMixer != null) {
            currentMixer.prepareForPlayback(channels, frames);
        }

        // Pre-allocate MIDI track renderer for SoundFont synthesis
        midiTrackRenderer = new MidiTrackRenderer(format.sampleRate(), frames);

        return true;
    }

    /**
     * Stops the audio engine. If not running, this method is a no-op.
     *
     * @return {@code true} if the engine was stopped, {@code false} if already stopped
     */
    public boolean stop() {
        if (!running.compareAndSet(true, false)) {
            return false;
        }
        if (midiTrackRenderer != null) {
            midiTrackRenderer.close();
            midiTrackRenderer = null;
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
        this.format = Objects.requireNonNull(format, "format must not be null");
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
     * Sets the audio backend for native I/O.
     *
     * <p>Must be called before {@link #start()}. The backend provides
     * low-latency audio input/output via PortAudio FFM bindings or the
     * Java Sound API fallback.</p>
     *
     * @param backend the audio backend, or {@code null} to use no backend
     */
    public void setAudioBackend(NativeAudioBackend backend) {
        if (running.get()) {
            throw new IllegalStateException("Cannot change audio backend while engine is running");
        }
        this.audioBackend = backend;
        this.backendInitialized = false;
    }

    /**
     * Returns the currently configured audio backend, or {@code null} if none is set.
     *
     * @return the audio backend
     */
    public NativeAudioBackend getAudioBackend() {
        return audioBackend;
    }

    /**
     * Ensures the audio backend is initialized, calling {@link NativeAudioBackend#initialize()}
     * if it has not been called yet.
     *
     * <p>This is safe to call multiple times — the underlying backends are idempotent.
     * Use this before querying devices (e.g. {@link NativeAudioBackend#getAvailableDevices()})
     * when audio output has not yet been started.</p>
     */
    public void ensureBackendInitialized() {
        NativeAudioBackend backend = this.audioBackend;
        if (backend != null && !backendInitialized) {
            backend.initialize();
            backendInitialized = true;
        }
    }

    // ── Audio output stream lifecycle ────────────────────────────────────────

    /**
     * Starts audio output by opening and starting a stream on the configured
     * {@link NativeAudioBackend}, registering {@link #processBlock} as the
     * audio callback driven at the hardware buffer rate.
     *
     * <p>If the stream was previously paused via {@link #pauseAudioOutput()},
     * this resumes it without re-opening the stream.</p>
     *
     * <p>If no audio backend is configured, the engine is started without
     * hardware output (the transport will still advance via the UI timer).</p>
     *
     * @throws AudioBackendException if the stream cannot be opened or started
     */
    public void startAudioOutput() {
        startAudioOutput(0);
    }

    /**
     * Starts audio output on the specified output device index. Use
     * {@code 0} to select the backend's default output device.
     *
     * @param outputDeviceIndex the output device index reported by
     *                          {@link NativeAudioBackend#getAvailableDevices()}
     * @throws AudioBackendException if the stream cannot be opened or started
     */
    public void startAudioOutput(int outputDeviceIndex) {
        if (streamOpen && streamPaused) {
            resumeAudioOutput();
            return;
        }

        if (streamOpen && !streamPaused) {
            return; // already running
        }

        // Ensure the engine is running (pre-allocates buffers)
        start();

        NativeAudioBackend backend = this.audioBackend;
        if (backend == null) {
            LOG.info("No audio backend configured; playback without hardware output");
            return;
        }

        if (!backendInitialized) {
            backend.initialize();
            backendInitialized = true;
        }

        AudioStreamConfig config = new AudioStreamConfig(
                -1,                  // no input device
                outputDeviceIndex,
                0,                   // no input channels
                format.channels(),
                SampleRate.fromHz((int) format.sampleRate()),
                BufferSize.fromFrames(format.bufferSize())
        );

        backend.openStream(config, this::processBlock);
        try {
            backend.startStream();
        } catch (AudioBackendException e) {
            backend.closeStream();
            throw e;
        }
        streamOpen = true;
        streamPaused = false;

        LOG.info("Audio output started via " + backend.getBackendName()
                + " (output device: " + outputDeviceIndex + ")");
    }

    /**
     * Stops the audio output stream and closes it.
     *
     * <p>A subsequent call to {@link #startAudioOutput()} will open a fresh
     * stream.</p>
     */
    public void stopAudioOutput() {
        NativeAudioBackend backend = this.audioBackend;
        if (backend != null && streamOpen) {
            try {
                backend.stopStream();
                backend.closeStream();
            } catch (AudioBackendException e) {
                LOG.log(Level.WARNING, "Error stopping audio output stream", e);
            }
            streamOpen = false;
            streamPaused = false;
        }
    }

    /**
     * Starts audio I/O for recording by opening a stream that includes both
     * input and output channels on the configured {@link NativeAudioBackend}.
     *
     * <p>If a stream is already open, it is closed first so that a new
     * full-duplex stream can be opened with the specified input device.</p>
     *
     * <p>If no audio backend is configured, the engine is started without
     * hardware I/O (recording will still capture data via the recording
     * callback from {@link #processBlock}).</p>
     *
     * @param inputDeviceIndex the index of the input device to open
     * @throws AudioBackendException if the stream cannot be opened or started
     */
    public void startAudioInputOutput(int inputDeviceIndex) {
        // Close existing stream if open
        if (streamOpen) {
            stopAudioOutput();
        }

        // Ensure the engine is running (pre-allocates buffers)
        start();

        NativeAudioBackend backend = this.audioBackend;
        if (backend == null) {
            LOG.info("No audio backend configured; recording without hardware I/O");
            return;
        }

        if (!backendInitialized) {
            backend.initialize();
            backendInitialized = true;
        }

        AudioStreamConfig config = new AudioStreamConfig(
                inputDeviceIndex,
                0,                   // default output device
                format.channels(),   // input channels matching format
                format.channels(),
                SampleRate.fromHz((int) format.sampleRate()),
                BufferSize.fromFrames(format.bufferSize())
        );

        backend.openStream(config, this::processBlock);
        try {
            backend.startStream();
        } catch (AudioBackendException e) {
            backend.closeStream();
            throw e;
        }
        streamOpen = true;
        streamPaused = false;

        LOG.info("Audio input/output started via " + backend.getBackendName()
                + " (input device: " + inputDeviceIndex + ")");
    }

    /**
     * Pauses audio output by stopping the stream without closing it,
     * allowing a fast resume via {@link #startAudioOutput()}.
     */
    public void pauseAudioOutput() {
        NativeAudioBackend backend = this.audioBackend;
        if (backend != null && streamOpen && !streamPaused) {
            backend.stopStream();
            streamPaused = true;
        }
    }

    /**
     * Returns whether the audio output stream is currently open.
     *
     * @return {@code true} if a stream is open (active or paused)
     */
    public boolean isStreamOpen() {
        return streamOpen;
    }

    /**
     * Returns whether the audio output stream is paused.
     *
     * @return {@code true} if the stream is open but paused
     */
    public boolean isStreamPaused() {
        return streamPaused;
    }

    private void resumeAudioOutput() {
        NativeAudioBackend backend = this.audioBackend;
        if (backend != null && streamOpen && streamPaused) {
            backend.startStream();
            streamPaused = false;
        }
    }

    /**
     * Sets the transport used to control playback position and state.
     *
     * <p>This reference is read from the audio thread on every
     * {@link #processBlock(float[][], float[][], int)} call. The caller
     * may update it from the UI thread at any time — the volatile reference
     * guarantees visibility without locks.</p>
     *
     * @param transport the transport, or {@code null} to disable playback rendering
     */
    public void setTransport(Transport transport) {
        this.transport = transport;
    }

    /**
     * Returns the currently configured transport, or {@code null}.
     *
     * @return the transport
     */
    public Transport getTransport() {
        return transport;
    }

    /**
     * Sets the mixer used to sum track outputs into the master bus.
     *
     * <p>This reference is read from the audio thread on every
     * {@link #processBlock(float[][], float[][], int)} call. If the engine
     * is already running, the mixer's channel effects chains are pre-allocated
     * immediately so that the audio thread remains allocation-free.</p>
     *
     * @param mixer the mixer, or {@code null} to disable playback rendering
     */
    public void setMixer(Mixer mixer) {
        this.mixer = mixer;
        if (mixer != null && running.get()) {
            mixer.prepareForPlayback(format.channels(), format.bufferSize());
        }
    }

    /**
     * Returns the currently configured mixer, or {@code null}.
     *
     * @return the mixer
     */
    public Mixer getMixer() {
        return mixer;
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
        Mixer currentMixer = this.mixer;
        return currentMixer != null ? currentMixer.getSystemLatencySamples() : 0;
    }

    /**
     * Sets the list of tracks whose clips are rendered during playback.
     *
     * <p>The list reference is captured once per
     * {@link #processBlock(float[][], float[][], int)} call. To avoid
     * concurrent-modification issues, pass an immutable or snapshot list
     * and update the reference atomically from the UI thread.</p>
     *
     * @param tracks the track list, or {@code null} to disable playback rendering
     */
    public void setTracks(List<Track> tracks) {
        this.tracks = tracks;
    }

    /**
     * Returns the currently configured track list, or {@code null}.
     *
     * @return the track list
     */
    public List<Track> getTracks() {
        return tracks;
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
     * thread. When no {@link TrackCpuBudgetEnforcer} is configured, it
     * performs zero allocations and zero lock acquisitions — all buffers
     * are pre-allocated during {@link #start()}, and the
     * {@link RenderPipeline} reads only from volatile snapshots of the
     * transport, mixer, track list, and MIDI renderer. When an enforcer
     * is present, per-track CPU timing occurs and the enforcer acquires
     * an internal lock for each measurement; the enforcer pre-allocates
     * its own buffers to minimize GC pressure on the audio thread.</p>
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
        // UI thread cannot tear the configuration mid-render.
        Transport currentTransport = this.transport;
        Mixer currentMixer = this.mixer;
        List<Track> currentTracks = this.tracks;
        MidiTrackRenderer currentMidiRenderer = this.midiTrackRenderer;
        RecordingCallback cb = this.recordingCallback;
        PerformanceMonitor monitor = this.performanceMonitor;
        TrackCpuBudgetEnforcer enforcer = this.cpuBudgetEnforcer;

        renderPipeline.renderBlock(inputBuffer, outputBuffer, numFrames,
                currentTransport, currentMixer, currentTracks,
                currentMidiRenderer, masterChain, cb, monitor,
                enforcer);
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
