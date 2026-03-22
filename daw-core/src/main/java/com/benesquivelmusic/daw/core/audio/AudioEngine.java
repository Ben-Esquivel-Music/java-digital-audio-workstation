package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

import java.util.Arrays;
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
 */
public final class AudioEngine {

    private final AudioFormat format;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final EffectsChain masterChain;
    private AudioBufferPool bufferPool;

    // Pre-allocated mix buffer used by processBlock
    private float[][] mixBuffer;

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
     * Processes a single block of audio through the master effects chain.
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
        // Clear the mix buffer
        for (float[] channel : mixBuffer) {
            Arrays.fill(channel, 0, numFrames, 0.0f);
        }

        // Copy input into the mix buffer
        int channels = Math.min(inputBuffer.length, mixBuffer.length);
        for (int ch = 0; ch < channels; ch++) {
            System.arraycopy(inputBuffer[ch], 0, mixBuffer[ch], 0, numFrames);
        }

        // Process through the master effects chain
        masterChain.process(mixBuffer, outputBuffer, numFrames);
    }
}
