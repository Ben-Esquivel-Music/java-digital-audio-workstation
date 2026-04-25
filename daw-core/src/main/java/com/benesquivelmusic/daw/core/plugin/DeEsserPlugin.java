package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.dynamics.DeEsserProcessor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Built-in de-esser effect plugin.
 *
 * <p>Wraps {@link DeEsserProcessor} as a first-class built-in plugin so it
 * appears in the Plugins menu alongside the other dynamics processors.</p>
 */
@BuiltInPlugin(label = "De-Esser", icon = "compressor", category = BuiltInPluginCategory.EFFECT)
public final class DeEsserPlugin implements BuiltInDawPlugin {

    /** Stable plugin identifier — used by the host to map plugins to views. */
    public static final String PLUGIN_ID = "com.benesquivelmusic.daw.builtin.deesser";

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
            PLUGIN_ID,
            "De-Esser",
            "1.0.0",
            "DAW Built-in",
            PluginType.EFFECT
    );

    private DeEsserProcessor processor;
    private boolean active;

    public DeEsserPlugin() {
    }

    @Override
    public PluginDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void initialize(PluginContext context) {
        Objects.requireNonNull(context, "context must not be null");
        processor = new DeEsserProcessor(context.getAudioChannels(), context.getSampleRate());
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
     * Returns the underlying {@link DeEsserProcessor}, or {@code null} if the
     * plugin has not been initialized or has been disposed.
     */
    public DeEsserProcessor getProcessor() {
        return processor;
    }

    /**
     * Returns the parameter descriptors for this de-esser plugin.
     *
     * <p>Parameter ids correspond to: 0=frequency (Hz), 1=Q, 2=threshold (dB),
     * 3=range (dB), 4=mode (0=Wideband, 1=Split-Band), 5=listen toggle
     * (0=off, 1=on). The "Toggle" suffix on parameter 5 is significant: the
     * generic parameter editor renders 0/1 parameters as on/off controls only
     * when the parameter name contains "toggle".</p>
     */
    @Override
    public List<PluginParameter> getParameters() {
        return List.of(
                new PluginParameter(0, "Frequency (Hz)",  2000.0, 12000.0, 6500.0),
                new PluginParameter(1, "Q",                  0.5,     4.0,    1.4),
                new PluginParameter(2, "Threshold (dB)",   -60.0,     0.0,  -30.0),
                new PluginParameter(3, "Range (dB)",         0.0,    20.0,   12.0),
                new PluginParameter(4, "Mode",               0.0,     1.0,    1.0),
                new PluginParameter(5, "Listen Toggle",      0.0,     1.0,    0.0));
    }

    /**
     * Routes an automation value to the underlying {@link DeEsserProcessor}.
     *
     * <p>Parameter ids match {@link #getParameters()}. Modes are decoded as
     * {@code value &lt; 0.5 ⇒ WIDEBAND}, otherwise {@code SPLIT_BAND}; the
     * listen toggle is decoded with the same threshold.</p>
     */
    @Override
    public void setAutomatableParameter(int parameterId, double value) {
        if (processor == null) {
            return;
        }
        switch (parameterId) {
            case 0 -> processor.setFrequencyHz(clamp(value, 2000.0, 12000.0));
            case 1 -> processor.setQ(clamp(value, 0.5, 4.0));
            case 2 -> processor.setThresholdDb(clamp(value, -60.0, 0.0));
            case 3 -> processor.setRangeDb(clamp(value, 0.0, 20.0));
            case 4 -> processor.setMode(value >= 0.5
                    ? DeEsserProcessor.Mode.SPLIT_BAND
                    : DeEsserProcessor.Mode.WIDEBAND);
            case 5 -> processor.setListen(value >= 0.5);
            default -> { /* unknown parameter id */ }
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.min(hi, Math.max(lo, v));
    }
}
