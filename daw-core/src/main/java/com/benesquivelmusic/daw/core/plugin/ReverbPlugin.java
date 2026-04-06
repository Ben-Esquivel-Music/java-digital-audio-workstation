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
public final class ReverbPlugin implements BuiltInDawPlugin {

    /** Stable plugin identifier — used by the host to map plugins to views. */
    public static final String PLUGIN_ID = "com.benesquivelmusic.daw.builtin.reverb";

    /** Parameter ID for the room size. */
    public static final int PARAM_ROOM_SIZE = 0;
    /** Parameter ID for the decay amount. */
    public static final int PARAM_DECAY = 1;
    /** Parameter ID for the high-frequency damping. */
    public static final int PARAM_DAMPING = 2;
    /** Parameter ID for the wet/dry mix. */
    public static final int PARAM_MIX = 3;

    private static final List<PluginParameter> PARAMETERS = List.of(
            new PluginParameter(PARAM_ROOM_SIZE, "Room Size", 0.0, 1.0, 0.5),
            new PluginParameter(PARAM_DECAY,     "Decay",     0.0, 1.0, 0.5),
            new PluginParameter(PARAM_DAMPING,   "Damping",   0.0, 1.0, 0.5),
            new PluginParameter(PARAM_MIX,       "Mix",       0.0, 1.0, 0.3)
    );

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
    public String getMenuLabel() {
        return "Reverb";
    }

    @Override
    public String getMenuIcon() {
        return "reverb";
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

    @Override
    public List<PluginParameter> getParameters() {
        return PARAMETERS;
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
}
