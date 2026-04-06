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
     * Returns the number of audio channels (e.g., 2 for stereo).
     *
     * <p>Plugins that create audio processors use this to configure their
     * channel count. The default is 2 (stereo).</p>
     *
     * @return the number of audio channels
     */
    default int getAudioChannels() {
        return 2;
    }

    /**
     * Logs an informational message on behalf of the plugin.
     *
     * @param message the message to log
     */
    void log(String message);
}
