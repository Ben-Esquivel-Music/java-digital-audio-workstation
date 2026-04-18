package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.acoustics.AcousticReverbProcessor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Built-in reverb effect plugin backed by the {@code daw-acoustics} FDN reverb.
 *
 * <p>Wraps {@link AcousticReverbProcessor} so the frequency-dependent,
 * room-dimension-aware acoustics reverb can be inserted on any mixer channel
 * alongside the simpler {@link ReverbPlugin}. This plugin is the user-visible
 * integration point for the {@code daw-acoustics} module.</p>
 */
public final class AcousticReverbPlugin implements BuiltInDawPlugin {

    /** Stable plugin identifier — used by the host to map plugins to views. */
    public static final String PLUGIN_ID = "com.benesquivelmusic.daw.builtin.acoustic-reverb";

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
            PLUGIN_ID,
            "Acoustic Reverb",
            "1.0.0",
            "DAW Built-in",
            PluginType.EFFECT
    );

    private AcousticReverbProcessor processor;
    private boolean active;

    public AcousticReverbPlugin() {
    }

    @Override
    public String getMenuLabel() { return "Acoustic Reverb"; }

    @Override
    public String getMenuIcon() { return "acoustic-reverb"; }

    @Override
    public BuiltInPluginCategory getCategory() { return BuiltInPluginCategory.EFFECT; }

    @Override
    public PluginDescriptor getDescriptor() { return DESCRIPTOR; }

    @Override
    public void initialize(PluginContext context) {
        Objects.requireNonNull(context, "context must not be null");
        processor = new AcousticReverbProcessor(
                context.getAudioChannels(),
                context.getSampleRate());
    }

    @Override
    public void activate() { active = true; }

    @Override
    public void deactivate() {
        active = false;
        if (processor != null) processor.reset();
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
     * Returns the underlying {@link AcousticReverbProcessor}, or {@code null}
     * if the plugin has not been initialized or has been disposed.
     */
    public AcousticReverbProcessor getProcessor() { return processor; }

    /**
     * Parameter ids: {@code 0 = preset (0..3)}, {@code 1 = T60 seconds},
     * {@code 2 = mix}.
     */
    @Override
    public List<PluginParameter> getParameters() {
        return List.of(
                new PluginParameter(0, "Preset", 0.0, 3.0, 1.0),
                new PluginParameter(1, "T60 (s)", 0.1, 10.0, 0.8),
                new PluginParameter(2, "Mix",     0.0, 1.0, 0.3));
    }
}
