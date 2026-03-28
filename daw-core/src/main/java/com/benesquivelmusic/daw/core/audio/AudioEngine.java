package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.performance.PerformanceMonitor;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.NativeAudioBackend;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /** Maximum number of tracks supported for pre-allocated buffers. */
    static final int MAX_TRACKS = 64;

    private final AudioFormat format;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final EffectsChain masterChain;
    private AudioBufferPool bufferPool;
    private NativeAudioBackend audioBackend;

    // Pre-allocated mix buffer used by processBlock
    private float[][] mixBuffer;

    // Pre-allocated per-track buffers for rendering: [track][channel][frame]
    private float[][][] trackBuffers;

    // Volatile references for lock-free UI ↔ audio thread communication
    private volatile Transport transport;
    private volatile Mixer mixer;
    private volatile List<Track> tracks;

    // Optional callback invoked from processBlock when recording is active
    private volatile RecordingCallback recordingCallback;

    // Optional performance monitor for CPU load and underrun tracking
    private volatile PerformanceMonitor performanceMonitor;

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

        // Pre-allocate the mix buffer
        mixBuffer = new float[channels][frames];

        // Pre-allocate per-track buffers
        trackBuffers = new float[MAX_TRACKS][channels][frames];

        // Pre-allocate the buffer pool (8 buffers for intermediate processing)
        bufferPool = new AudioBufferPool(8, channels, frames);

        // Pre-allocate intermediate buffers in the master effects chain
        masterChain.allocateIntermediateBuffers(channels, frames);

        return true;
    }

    /**
     * Stops the audio engine. If not running, this method is a no-op.
     *
     * @return {@code true} if the engine was stopped, {@code false} if already stopped
     */
    public boolean stop() {
        return running.compareAndSet(true, false);
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
     * Returns the master effects chain applied to the final mix output.
     *
     * @return the master effects chain
     */
    public EffectsChain getMasterChain() {
        return masterChain;
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
     * {@link #processBlock(float[][], float[][], int)} call.</p>
     *
     * @param mixer the mixer, or {@code null} to disable playback rendering
     */
    public void setMixer(Mixer mixer) {
        this.mixer = mixer;
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
     * Processes a single block of audio through the rendering pipeline.
     *
     * <p>When a transport, mixer, and track list are configured and the
     * transport is in {@link TransportState#PLAYING} or
     * {@link TransportState#RECORDING} state, the engine:</p>
     * <ol>
     *   <li>Reads audio data from each track's clips at the current
     *       transport position into pre-allocated per-track buffers</li>
     *   <li>Sums all track outputs through the {@link Mixer} (applying
     *       per-channel volume, pan, mute, and solo)</li>
     *   <li>Processes the mixed result through the master effects chain</li>
     *   <li>Advances the transport position by the number of beats
     *       corresponding to {@code numFrames} at the current tempo</li>
     * </ol>
     *
     * <p>When the transport is not configured or not playing, the engine
     * falls back to copying {@code inputBuffer} through the master effects
     * chain (the original passthrough behavior).</p>
     *
     * <p>This method is designed to be called from the audio callback thread.
     * It performs zero allocations and zero lock acquisitions — all buffers
     * are pre-allocated during {@link #start()}.</p>
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

        // Snapshot the performance monitor once for this block
        PerformanceMonitor monitor = this.performanceMonitor;
        long startNanos = 0;
        if (monitor != null) {
            startNanos = System.nanoTime();
        }

        // Clear the mix buffer
        for (float[] channel : mixBuffer) {
            Arrays.fill(channel, 0, numFrames, 0.0f);
        }

        // Snapshot volatile references once for this block
        Transport currentTransport = this.transport;
        Mixer currentMixer = this.mixer;
        List<Track> currentTracks = this.tracks;

        boolean playbackActive = currentTransport != null
                && currentMixer != null
                && currentTracks != null
                && (currentTransport.getState() == TransportState.PLAYING
                    || currentTransport.getState() == TransportState.RECORDING);

        if (playbackActive) {
            int trackCount = Math.min(currentTracks.size(), MAX_TRACKS);

            // Render clip audio for each track into pre-allocated per-track buffers
            renderTracks(currentTracks, trackCount, currentTransport, numFrames);

            // Mix all track buffers through the mixer into the mix buffer
            currentMixer.mixDown(trackBuffers, mixBuffer, numFrames);
        } else {
            // Fallback: copy input into the mix buffer (original passthrough behavior)
            int channels = Math.min(inputBuffer.length, mixBuffer.length);
            for (int ch = 0; ch < channels; ch++) {
                System.arraycopy(inputBuffer[ch], 0, mixBuffer[ch], 0, numFrames);
            }
        }

        // Notify recording callback with the captured input
        RecordingCallback cb = recordingCallback;
        if (cb != null) {
            cb.onAudioCaptured(inputBuffer, numFrames);
        }

        // Process through the master effects chain
        masterChain.process(mixBuffer, outputBuffer, numFrames);

        // Advance the transport position
        if (playbackActive) {
            double samplesPerBeat = format.sampleRate() * 60.0 / currentTransport.getTempo();
            double deltaBeats = numFrames / samplesPerBeat;
            currentTransport.advancePosition(deltaBeats);
        }

        // Record processing time to the performance monitor
        if (monitor != null) {
            long elapsedNanos = System.nanoTime() - startNanos;
            monitor.recordProcessingTime(elapsedNanos);
        }
    }

    /**
     * Renders audio from each track's clips into the pre-allocated
     * {@link #trackBuffers} array. Handles loop-boundary crossing by
     * splitting the block into contiguous segments.
     *
     * @param tracks       the list of tracks to render
     * @param trackCount   the number of tracks to process (capped at {@link #MAX_TRACKS})
     * @param transport    the transport providing position and loop state
     * @param numFrames    the total number of frames in this block
     */
    @RealTimeSafe
    private void renderTracks(List<Track> tracks, int trackCount, Transport transport,
                              int numFrames) {
        // Clear per-track buffers
        int audioChannels = format.channels();
        for (int t = 0; t < trackCount; t++) {
            for (int ch = 0; ch < audioChannels; ch++) {
                Arrays.fill(trackBuffers[t][ch], 0, numFrames, 0.0f);
            }
        }

        double tempo = transport.getTempo();
        double sampleRate = format.sampleRate();
        double samplesPerBeat = sampleRate * 60.0 / tempo;
        double currentBeat = transport.getPositionInBeats();
        boolean loopEnabled = transport.isLoopEnabled();
        double loopStart = transport.getLoopStartInBeats();
        double loopEnd = transport.getLoopEndInBeats();
        double loopLength = loopEnd - loopStart;

        int framesProcessed = 0;

        while (framesProcessed < numFrames) {
            int framesToProcess = numFrames - framesProcessed;

            // If looping, limit the segment to not cross the loop boundary
            if (loopEnabled && loopLength > 0.0 && currentBeat < loopEnd) {
                double beatsUntilLoopEnd = loopEnd - currentBeat;
                int framesUntilLoopEnd = (int) Math.ceil(beatsUntilLoopEnd * samplesPerBeat);
                if (framesUntilLoopEnd > 0) {
                    framesToProcess = Math.min(framesToProcess, framesUntilLoopEnd);
                }
            }

            // Render this contiguous segment for all tracks
            renderSegment(tracks, trackCount, currentBeat, samplesPerBeat,
                          framesProcessed, framesToProcess);

            framesProcessed += framesToProcess;
            currentBeat += framesToProcess / samplesPerBeat;

            // Handle loop wrap
            if (loopEnabled && loopLength > 0.0 && currentBeat >= loopEnd) {
                currentBeat = loopStart + (currentBeat - loopEnd);
            }
        }
    }

    /**
     * Renders a contiguous segment of audio from all tracks' clips into
     * the pre-allocated track buffers. This segment does not cross a loop
     * boundary.
     *
     * @param tracks          the list of tracks
     * @param trackCount      the number of tracks to process
     * @param startBeat       the beat position at the start of this segment
     * @param samplesPerBeat  samples per beat at the current tempo
     * @param frameOffset     the frame offset within the block's track buffer
     * @param framesToProcess the number of frames in this segment
     */
    @RealTimeSafe
    private void renderSegment(List<Track> tracks, int trackCount,
                               double startBeat, double samplesPerBeat,
                               int frameOffset, int framesToProcess) {
        double endBeat = startBeat + framesToProcess / samplesPerBeat;

        for (int t = 0; t < trackCount; t++) {
            Track track = tracks.get(t);
            List<AudioClip> clips = track.getClips();

            for (int c = 0; c < clips.size(); c++) {
                AudioClip clip = clips.get(c);
                float[][] audioData = clip.getAudioData();
                if (audioData == null || audioData.length == 0) {
                    continue;
                }

                double clipStart = clip.getStartBeat();
                double clipEnd = clip.getEndBeat();

                // Skip clips that do not overlap this segment
                if (endBeat <= clipStart || startBeat >= clipEnd) {
                    continue;
                }

                // Determine the overlapping beat range
                double overlapStart = Math.max(startBeat, clipStart);
                double overlapEnd = Math.min(endBeat, clipEnd);

                // Map to frame indices in the output buffer
                int outStart = frameOffset + (int) Math.round((overlapStart - startBeat) * samplesPerBeat);
                int outEnd = frameOffset + (int) Math.round((overlapEnd - startBeat) * samplesPerBeat);
                outEnd = Math.min(outEnd, frameOffset + framesToProcess);

                // Map to sample index in the clip's audio data
                double beatInClip = overlapStart - clipStart + clip.getSourceOffsetBeats();
                int srcStart = (int) Math.round(beatInClip * samplesPerBeat);
                int audioLength = audioData[0].length;

                if (srcStart < 0) {
                    outStart += -srcStart;
                    srcStart = 0;
                }

                int copyLength = Math.min(outEnd - outStart, audioLength - srcStart);
                if (copyLength <= 0) {
                    continue;
                }

                int audioChannels = Math.min(audioData.length, trackBuffers[t].length);
                for (int ch = 0; ch < audioChannels; ch++) {
                    for (int f = 0; f < copyLength; f++) {
                        trackBuffers[t][ch][outStart + f] += audioData[ch][srcStart + f];
                    }
                }
            }
        }
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
