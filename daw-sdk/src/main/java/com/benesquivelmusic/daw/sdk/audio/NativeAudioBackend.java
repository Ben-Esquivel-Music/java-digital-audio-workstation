package com.benesquivelmusic.daw.sdk.audio;

import java.util.List;

/**
 * Abstraction for low-latency audio I/O backends.
 *
 * <p>Implementations may use PortAudio via FFM bindings, direct OS audio
 * APIs (ALSA, CoreAudio, WASAPI) via FFM, or fall back to the Java Sound
 * API ({@code javax.sound.sampled}).</p>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link #initialize()} — set up the backend (load native libraries, etc.)</li>
 *   <li>{@link #getAvailableDevices()} — enumerate audio devices</li>
 *   <li>{@link #openStream(AudioStreamConfig, AudioStreamCallback)} — configure a stream</li>
 *   <li>{@link #startStream()} — begin audio I/O</li>
 *   <li>{@link #stopStream()} — stop audio I/O</li>
 *   <li>{@link #closeStream()} — release the stream</li>
 *   <li>{@link #close()} — terminate the backend and release all resources</li>
 * </ol>
 *
 * <h2>Thread Safety</h2>
 * <p>The {@link AudioStreamCallback} is invoked on a dedicated high-priority
 * audio thread. All other methods must be called from the application thread.</p>
 */
public interface NativeAudioBackend extends AutoCloseable {

    /**
     * Initializes the audio backend. Must be called before any other method.
     *
     * @throws AudioBackendException if initialization fails
     */
    void initialize();

    /**
     * Returns a list of all available audio devices.
     *
     * @return an unmodifiable list of device information
     * @throws AudioBackendException if device enumeration fails
     */
    List<AudioDeviceInfo> getAvailableDevices();

    /**
     * Returns the default input device, or {@code null} if none is available.
     *
     * @return the default input device info, or null
     */
    AudioDeviceInfo getDefaultInputDevice();

    /**
     * Returns the default output device, or {@code null} if none is available.
     *
     * @return the default output device info, or null
     */
    AudioDeviceInfo getDefaultOutputDevice();

    /**
     * Opens an audio stream with the specified configuration and callback.
     *
     * @param config   the stream configuration
     * @param callback the audio processing callback
     * @throws AudioBackendException if the stream cannot be opened
     */
    void openStream(AudioStreamConfig config, AudioStreamCallback callback);

    /**
     * Starts audio I/O on the opened stream.
     *
     * @throws AudioBackendException if the stream cannot be started
     * @throws IllegalStateException if no stream is open
     */
    void startStream();

    /**
     * Stops audio I/O on the active stream.
     *
     * @throws AudioBackendException if the stream cannot be stopped
     */
    void stopStream();

    /**
     * Closes the current stream and releases its resources.
     *
     * @throws AudioBackendException if the stream cannot be closed
     */
    void closeStream();

    /**
     * Returns latency information for the currently open stream.
     *
     * @return the latency info
     * @throws IllegalStateException if no stream is open
     */
    LatencyInfo getLatencyInfo();

    /**
     * Returns whether the audio stream is currently active (started).
     *
     * @return true if the stream is active
     */
    boolean isStreamActive();

    /**
     * Returns the human-readable name of this backend (e.g., "PortAudio", "Java Sound").
     *
     * @return the backend name
     */
    String getBackendName();

    /**
     * Returns whether this backend is available on the current platform.
     *
     * <p>For native backends, this checks whether the required native library
     * can be loaded. For Java Sound, this always returns {@code true}.</p>
     *
     * @return true if the backend can be used
     */
    boolean isAvailable();

    /**
     * Terminates the backend and releases all resources.
     */
    @Override
    void close();
}
