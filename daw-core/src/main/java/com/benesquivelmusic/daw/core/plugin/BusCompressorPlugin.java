package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.dynamics.BusCompressorProcessor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Built-in SSL-style bus compressor effect plugin.
 *
 * <p>Wraps {@link BusCompressorProcessor} as a first-class built-in plugin so
 * it appears in the Plugins menu alongside the existing {@link CompressorPlugin}.
 * Whereas {@code CompressorPlugin} targets single-channel dynamics, this plugin
 * is tuned for gentle "mix glue" compression on group and master busses.</p>
 */
@BuiltInPlugin(label = "Bus Compressor", icon = "compressor", category = BuiltInPluginCategory.EFFECT)
public final class BusCompressorPlugin implements BuiltInDawPlugin {

    /** Stable plugin identifier — used by the host to map plugins to views. */
    public static final String PLUGIN_ID = "com.benesquivelmusic.daw.builtin.buscompressor";

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
            PLUGIN_ID,
            "Bus Compressor",
            "1.0.0",
            "DAW Built-in",
            PluginType.EFFECT
    );

    private BusCompressorProcessor processor;
    private boolean active;

    public BusCompressorPlugin() {
    }

    @Override
    public PluginDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void initialize(PluginContext context) {
        Objects.requireNonNull(context, "context must not be null");
        processor = new BusCompressorProcessor(context.getAudioChannels(), context.getSampleRate());
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
     * Returns the underlying {@link BusCompressorProcessor}, or {@code null}
     * if the plugin has not been initialized or has been disposed.
     */
    public BusCompressorProcessor getProcessor() {
        return processor;
    }

    /**
     * Returns the parameter descriptors for this bus compressor plugin.
     *
     * <p>Parameter ids correspond to: 0=threshold (dB), 1=ratio,
     * 2=attack (ms), 3=release (s), 4=makeup gain (dB), 5=mix.</p>
     */
    @Override
    public List<PluginParameter> getParameters() {
        return List.of(
                new PluginParameter(0, "Threshold (dB)",  -40.0,  0.0, -10.0),
                new PluginParameter(1, "Ratio",             1.5, 10.0,   4.0),
                new PluginParameter(2, "Attack (ms)",       0.1, 30.0,  10.0),
                new PluginParameter(3, "Release (s)",       0.1,  1.2,   0.6),
                new PluginParameter(4, "Makeup Gain (dB)",  0.0, 24.0,   0.0),
                new PluginParameter(5, "Mix",               0.0,  1.0,   1.0));
    }
}
