package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.ExternalPluginHost;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An {@link AudioProcessor} adapter that wraps an {@link ExternalPluginHost} for
 * use in the mixer's insert effect chain.
 *
 * <p>This adapter delegates audio processing to the underlying CLAP plugin
 * and provides crash-resilient processing: if the plugin throws an exception
 * during processing, the adapter falls back to pass-through (copying input
 * to output) rather than propagating the error and crashing the DAW.</p>
 *
 * <p>Once a plugin has crashed, the adapter marks it as faulted and all
 * subsequent calls pass audio through unprocessed until {@link #reset()} is
 * called, which clears the faulted state.</p>
 */
public final class ClapInsertEffect implements AudioProcessor {

    private static final Logger LOGGER = Logger.getLogger(ClapInsertEffect.class.getName());

    private final ExternalPluginHost pluginHost;
    private volatile boolean faulted;

    /**
     * Creates a new CLAP insert effect wrapping the given plugin host.
     *
     * @param pluginHost the external plugin host to delegate processing to
     */
    public ClapInsertEffect(ExternalPluginHost pluginHost) {
        this.pluginHost = Objects.requireNonNull(pluginHost, "pluginHost must not be null");
    }

    /**
     * Returns the underlying external plugin host.
     *
     * @return the plugin host
     */
    public ExternalPluginHost getPluginHost() {
        return pluginHost;
    }

    /**
     * Returns whether this effect has encountered a processing error and is
     * operating in pass-through mode.
     *
     * @return {@code true} if faulted
     */
    public boolean isFaulted() {
        return faulted;
    }

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        if (faulted) {
            copyBuffer(inputBuffer, outputBuffer, numFrames);
            return;
        }

        try {
            pluginHost.process(inputBuffer, outputBuffer, numFrames);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "CLAP plugin processing failed, falling back to pass-through: "
                    + pluginHost.getDescriptor().name(), e);
            faulted = true;
            copyBuffer(inputBuffer, outputBuffer, numFrames);
        }
    }

    @Override
    public void reset() {
        faulted = false;
        try {
            pluginHost.reset();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "CLAP plugin reset failed: "
                    + pluginHost.getDescriptor().name(), e);
        }
    }

    @Override
    public int getInputChannelCount() {
        return pluginHost.getInputChannelCount();
    }

    @Override
    public int getOutputChannelCount() {
        return pluginHost.getOutputChannelCount();
    }

    private static void copyBuffer(float[][] src, float[][] dst, int numFrames) {
        int channels = Math.min(src.length, dst.length);
        for (int ch = 0; ch < channels; ch++) {
            System.arraycopy(src[ch], 0, dst[ch], 0, numFrames);
        }
    }
}
