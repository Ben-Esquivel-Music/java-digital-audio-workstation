package com.benesquivelmusic.daw.core.audio;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Central audio engine responsible for managing the audio processing pipeline.
 *
 * <p>The engine coordinates audio I/O, drives the mixer, and dispatches
 * audio buffers to tracks and plugins.</p>
 */
public final class AudioEngine {

    private final AudioFormat format;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Creates a new audio engine with the specified format.
     *
     * @param format the audio format configuration
     */
    public AudioEngine(AudioFormat format) {
        this.format = Objects.requireNonNull(format, "format must not be null");
    }

    /**
     * Starts the audio engine. If already running, this method is a no-op.
     *
     * @return {@code true} if the engine was started, {@code false} if already running
     */
    public boolean start() {
        return running.compareAndSet(false, true);
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
}
