package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.dynamics.TransientShaperProcessor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Built-in transient shaper effect plugin.
 *
 * <p>Wraps {@link TransientShaperProcessor} as a first-class built-in plugin so
 * it appears in the Plugins menu alongside the existing dynamics processors.
 * A transient shaper boosts or suppresses the attack and sustain of a signal
 * independently of absolute level — useful for adding punch to a kick drum or
 * taming a ringy snare without resorting to threshold-based compression.</p>
 */
@BuiltInPlugin(label = "Transient Shaper", icon = "compressor", category = BuiltInPluginCategory.EFFECT)
public final class TransientShaperPlugin implements BuiltInDawPlugin {

    /** Stable plugin identifier — used by the host to map plugins to views. */
    public static final String PLUGIN_ID = "com.benesquivelmusic.daw.builtin.transientshaper";

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
            PLUGIN_ID,
            "Transient Shaper",
            "1.0.0",
            "DAW Built-in",
            PluginType.EFFECT
    );

    private TransientShaperProcessor processor;
    private boolean active;

    public TransientShaperPlugin() {
    }

    @Override
    public PluginDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void initialize(PluginContext context) {
        Objects.requireNonNull(context, "context must not be null");
        processor = new TransientShaperProcessor(context.getAudioChannels(), context.getSampleRate());
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
     * Returns the underlying {@link TransientShaperProcessor}, or {@code null}
     * if the plugin has not been initialized or has been disposed.
     */
    public TransientShaperProcessor getProcessor() {
        return processor;
    }

    /**
     * Returns the parameter descriptors for this transient shaper plugin.
     *
     * <p>Parameter ids correspond to: 0=attack (-100..+100 %),
     * 1=sustain (-100..+100 %), 2=output (-12..+12 dB),
     * 3=input monitor toggle (0=off, 1=on),
     * 4=channel link (0..1). The "Toggle" suffix on parameter 3 is
     * significant: the generic parameter editor renders 0/1 parameters as
     * on/off controls only when the parameter name contains "toggle".</p>
     */
    @Override
    public List<PluginParameter> getParameters() {
        return List.of(
                new PluginParameter(0, "Attack (%)",          -100.0, 100.0,   0.0),
                new PluginParameter(1, "Sustain (%)",         -100.0, 100.0,   0.0),
                new PluginParameter(2, "Output (dB)",          -12.0,  12.0,   0.0),
                new PluginParameter(3, "Input Monitor Toggle",   0.0,   1.0,   0.0),
                new PluginParameter(4, "Channel Link",           0.0,   1.0,   1.0));
    }

    /**
     * Routes an automation value to the underlying {@link TransientShaperProcessor}.
     *
     * <p>Parameter ids match {@link #getParameters()}. The input-monitor toggle
     * is decoded with a {@code 0.5} threshold.</p>
     */
    @Override
    public void setAutomatableParameter(int parameterId, double value) {
        if (processor == null) {
            return;
        }
        switch (parameterId) {
            case 0 -> processor.setAttackPercent(clamp(value, -100.0, 100.0));
            case 1 -> processor.setSustainPercent(clamp(value, -100.0, 100.0));
            case 2 -> processor.setOutputDb(clamp(value, -12.0, 12.0));
            case 3 -> processor.setInputMonitor(value >= 0.5);
            case 4 -> processor.setChannelLink(clamp(value, 0.0, 1.0));
            default -> { /* unknown parameter id */ }
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.min(hi, Math.max(lo, v));
    }
}
