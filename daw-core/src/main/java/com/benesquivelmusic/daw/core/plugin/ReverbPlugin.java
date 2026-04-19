package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.ReverbProcessor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Built-in reverb effect plugin.
 *
 * <p>Wraps the DAW's {@link ReverbProcessor} as a first-class plugin
 * so it appears in the Plugins menu alongside external plugins.</p>
 */
@BuiltInPlugin(label = "Reverb", icon = "reverb", category = BuiltInPluginCategory.EFFECT)
public final class ReverbPlugin implements BuiltInDawPlugin {

    /** Stable plugin identifier — used by the host to map plugins to views. */
    public static final String PLUGIN_ID = "com.benesquivelmusic.daw.builtin.reverb";

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
            PLUGIN_ID,
            "Reverb",
            "1.0.0",
            "DAW Built-in",
            PluginType.EFFECT
    );

    private ReverbProcessor processor;
    private boolean active;

    public ReverbPlugin() {
    }

    @Override
    public PluginDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void initialize(PluginContext context) {
        Objects.requireNonNull(context, "context must not be null");
        processor = new ReverbProcessor(context.getAudioChannels(), context.getSampleRate());
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
     * Returns the underlying {@link ReverbProcessor}, or {@code null} if
     * the plugin has not been initialized or has been disposed.
     *
     * @return the reverb processor, or {@code null}
     */
    public ReverbProcessor getProcessor() {
        return processor;
    }

    /**
     * Returns the parameter descriptors for this reverb plugin.
     *
     * <p>Parameter ids correspond to: 0=room size, 1=decay, 2=damping, 3=mix.</p>
     *
     * @return an unmodifiable list of reverb parameter descriptors
     */
    @Override
    public List<PluginParameter> getParameters() {
        return List.of(
                new PluginParameter(0, "Room Size", 0.0, 1.0, 0.5),
                new PluginParameter(1, "Decay",     0.0, 1.0, 0.5),
                new PluginParameter(2, "Damping",   0.0, 1.0, 0.3),
                new PluginParameter(3, "Mix",       0.0, 1.0, 0.3));
    }
}
