package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.CompressorProcessor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.Objects;
import java.util.Optional;

/**
 * Built-in compressor effect plugin.
 *
 * <p>Wraps the DAW's {@link CompressorProcessor} as a first-class plugin
 * so it appears in the Plugins menu alongside external plugins.</p>
 */
public final class CompressorPlugin implements BuiltInDawPlugin {

    /** Stable plugin identifier — used by the host to map plugins to views. */
    public static final String PLUGIN_ID = "com.benesquivelmusic.daw.builtin.compressor";

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
            PLUGIN_ID,
            "Compressor",
            "1.0.0",
            "DAW Built-in",
            PluginType.EFFECT
    );

    private CompressorProcessor processor;
    private boolean active;

    public CompressorPlugin() {
    }

    @Override
    public String getMenuLabel() {
        return "Compressor";
    }

    @Override
    public String getMenuIcon() {
        return "compressor";
    }

    @Override
    public BuiltInPluginCategory getCategory() {
        return BuiltInPluginCategory.EFFECT;
    }

    @Override
    public PluginDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void initialize(PluginContext context) {
        Objects.requireNonNull(context, "context must not be null");
        processor = new CompressorProcessor(context.getAudioChannels(), context.getSampleRate());
    }

    @Override
    public void activate() {
        active = true;
    }

    @Override
    public void deactivate() {
        active = false;
        if (processor != null) {
            processor.reset();
        }
    }

    @Override
    public void dispose() {
        active = false;
        processor = null;
    }

    @Override
    public Optional<AudioProcessor> asAudioProcessor() {
        return Optional.ofNullable(processor);
    }

    /**
     * Returns the underlying {@link CompressorProcessor}, or {@code null} if
     * the plugin has not been initialized or has been disposed.
     *
     * @return the compressor processor, or {@code null}
     */
    public CompressorProcessor getProcessor() {
        return processor;
    }
}
