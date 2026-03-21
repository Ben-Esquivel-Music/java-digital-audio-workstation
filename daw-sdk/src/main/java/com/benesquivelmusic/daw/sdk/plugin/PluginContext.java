package com.benesquivelmusic.daw.sdk.plugin;

/**
 * Context provided by the DAW host to plugins during initialization.
 *
 * <p>Gives plugins access to DAW services such as the sample rate,
 * buffer size, and logging facilities.</p>
 */
public interface PluginContext {

    /**
     * Returns the current audio sample rate in Hz (e.g., 44100, 48000, 96000).
     *
     * @return the sample rate in Hz
     */
    double getSampleRate();

    /**
     * Returns the audio buffer size in sample frames.
     *
     * @return the buffer size in frames
     */
    int getBufferSize();

    /**
     * Logs an informational message on behalf of the plugin.
     *
     * @param message the message to log
     */
    void log(String message);
}
