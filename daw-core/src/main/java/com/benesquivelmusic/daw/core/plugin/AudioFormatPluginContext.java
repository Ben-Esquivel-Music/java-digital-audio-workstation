package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * The {@link PluginContext} a plugin is initialized with for a project's
 * audio format: its sample rate, buffer size and channel count. A message the
 * plugin logs goes to this class's logger at INFO.
 *
 * @param format the audio format the plugin runs in
 */
public record AudioFormatPluginContext(AudioFormat format) implements PluginContext {

    private static final Logger LOG = Logger.getLogger(AudioFormatPluginContext.class.getName());

    public AudioFormatPluginContext {
        Objects.requireNonNull(format, "format must not be null");
    }

    @Override
    public double getSampleRate() {
        return format.sampleRate();
    }

    @Override
    public int getBufferSize() {
        return format.bufferSize();
    }

    @Override
    public int getAudioChannels() {
        return format.channels();
    }

    @Override
    public void log(String message) {
        LOG.info(message);
    }
}
