package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.spatial.binaural.BinauralMonitoringProcessor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Built-in binaural headphone monitoring plugin backed by the
 * {@code daw-acoustics} spatialiser.
 *
 * <p>Wraps {@link BinauralMonitoringProcessor}, which routes input through a
 * spherical-Fibonacci arrangement of FDN reverb sources and pans each source
 * to stereo based on its direction. This provides an audible preview of
 * spatial mixes on headphones without requiring a dedicated multi-speaker
 * monitoring setup.</p>
 */
public final class BinauralMonitorPlugin implements BuiltInDawPlugin {

    /** Stable plugin identifier — used by the host to map plugins to views. */
    public static final String PLUGIN_ID = "com.benesquivelmusic.daw.builtin.binaural-monitor";

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
            PLUGIN_ID,
            "Binaural Monitor",
            "1.0.0",
            "DAW Built-in",
            PluginType.EFFECT
    );

    private BinauralMonitoringProcessor processor;
    private boolean active;

    public BinauralMonitorPlugin() {
    }

    @Override
    public String getMenuLabel() { return "Binaural Monitor"; }

    @Override
    public String getMenuIcon() { return "binaural-monitor"; }

    @Override
    public BuiltInPluginCategory getCategory() { return BuiltInPluginCategory.EFFECT; }

    @Override
    public PluginDescriptor getDescriptor() { return DESCRIPTOR; }

    @Override
    public void initialize(PluginContext context) {
        Objects.requireNonNull(context, "context must not be null");
        processor = new BinauralMonitoringProcessor(
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
     * Returns the underlying {@link BinauralMonitoringProcessor}, or
     * {@code null} if not initialized / disposed.
     */
    public BinauralMonitoringProcessor getProcessor() { return processor; }

    /** Parameter ids: {@code 0 = wet level}. */
    @Override
    public List<PluginParameter> getParameters() {
        return List.of(
                new PluginParameter(0, "Wet Level", 0.0, 1.0, 0.5));
    }
}
