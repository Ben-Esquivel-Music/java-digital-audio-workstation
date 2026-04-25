package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.dynamics.TruePeakLimiterProcessor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Built-in true-peak brickwall limiter — the final stage of the mastering chain.
 *
 * <p>Wraps {@link TruePeakLimiterProcessor} as a first-class built-in plugin so
 * it appears in the Plugins menu under the {@link BuiltInPluginCategory#MASTERING
 * Mastering} category alongside other mastering tools. Holds output below a
 * configurable ceiling (typically {@code -1.0 dBTP} per AES TD1004.1.15-10) using
 * a lookahead architecture and oversampled inter-sample peak (ISP) detection at
 * {@code 2×}, {@code 4×}, or {@code 8×}.</p>
 *
 * <p>Built-in plugins are registered via the sealed permits list of
 * {@link BuiltInDawPlugin}. Discovery and menu entries are resolved
 * reflectively by {@link BuiltInDawPlugin#discoverAll()} and
 * {@link BuiltInDawPlugin#menuEntries()} — no additional registry wiring is
 * required.</p>
 */
@BuiltInPlugin(label = "True-Peak Limiter", icon = "limiter",
        category = BuiltInPluginCategory.MASTERING)
public final class TruePeakLimiterPlugin implements BuiltInDawPlugin {

    /** Stable plugin identifier — used by the host to map plugins to views. */
    public static final String PLUGIN_ID = "com.benesquivelmusic.daw.builtin.truepeaklimiter";

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
            PLUGIN_ID,
            "True-Peak Limiter",
            "1.0.0",
            "DAW Built-in",
            PluginType.EFFECT
    );

    private TruePeakLimiterProcessor processor;
    private boolean active;

    public TruePeakLimiterPlugin() {
    }

    @Override
    public PluginDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void initialize(PluginContext context) {
        Objects.requireNonNull(context, "context must not be null");
        processor = new TruePeakLimiterProcessor(
                context.getAudioChannels(), context.getSampleRate());
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
     * Returns the underlying {@link TruePeakLimiterProcessor}, or {@code null}
     * if the plugin has not been initialized or has been disposed.
     */
    public TruePeakLimiterProcessor getProcessor() {
        return processor;
    }

    /**
     * Returns the parameter descriptors for this limiter.
     *
     * <p>Parameter ids correspond to: 0=ceiling (dBTP), 1=release (ms),
     * 2=lookahead (ms), 3=oversampling factor (2/4/8), 4=channel link (%).</p>
     */
    @Override
    public List<PluginParameter> getParameters() {
        return List.of(
                new PluginParameter(0, "Ceiling (dBTP)",   -3.0,    0.0,   -1.0),
                new PluginParameter(1, "Release (ms)",      1.0, 1000.0,   50.0),
                new PluginParameter(2, "Lookahead (ms)",    1.0,   10.0,    5.0),
                new PluginParameter(3, "ISR",               2.0,    8.0,    4.0),
                new PluginParameter(4, "Channel Link (%)",  0.0,  100.0,  100.0));
    }

    /**
     * Routes an automation value to the underlying
     * {@link TruePeakLimiterProcessor}.
     *
     * <p>Parameter ids match {@link #getParameters()}. The ISR value is
     * snapped to the nearest supported oversampling factor by the processor.</p>
     */
    @Override
    public void setAutomatableParameter(int parameterId, double value) {
        if (processor == null) {
            return;
        }
        switch (parameterId) {
            case 0 -> processor.setCeilingDb(clamp(value, -3.0, 0.0));
            case 1 -> processor.setReleaseMs(clamp(value, 1.0, 1000.0));
            case 2 -> processor.setLookaheadMs(clamp(value, 1.0, 10.0));
            case 3 -> processor.setIsr((int) Math.round(clamp(value, 2.0, 8.0)));
            case 4 -> processor.setChannelLinkPercent(clamp(value, 0.0, 100.0));
            default -> { /* unknown parameter id */ }
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.min(hi, Math.max(lo, v));
    }
}
