package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.eq.MatchEqProcessor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Built-in spectrum-matched ("match EQ") effect plugin.
 *
 * <p>Wraps the DAW's {@link MatchEqProcessor} as a first-class plugin so it
 * appears in the Plugins menu alongside external plugins. Match EQ analyses
 * the long-term-average spectrum of a reference track (story 041) and the
 * current source, computes the tonal difference, and applies it as either
 * a minimum-phase IIR cascade or a linear-phase FIR.</p>
 *
 * <p>Exposed generic parameters cover the parameters that map naturally to
 * sliders in the stock {@code PluginParameterEditorPanel}. Reference loading
 * and spectrum capture are driven by dedicated API calls and the
 * {@code MatchEqPluginView} UI — not through numeric parameters.</p>
 */
@BuiltInPlugin(label = "Match EQ", icon = "eq", category = BuiltInPluginCategory.EFFECT)
public final class MatchEqPlugin implements BuiltInDawPlugin {

    /** Stable plugin identifier — used by the host to map plugins to views. */
    public static final String PLUGIN_ID = "com.benesquivelmusic.daw.builtin.match-eq";

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
            PLUGIN_ID,
            "Match EQ",
            "1.0.0",
            "DAW Built-in",
            PluginType.EFFECT
    );

    private MatchEqProcessor processor;
    private boolean active;

    public MatchEqPlugin() {
    }

    @Override
    public PluginDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void initialize(PluginContext context) {
        Objects.requireNonNull(context, "context must not be null");
        processor = new MatchEqProcessor(context.getAudioChannels(), context.getSampleRate());
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
     * Returns the underlying {@link MatchEqProcessor}, or {@code null} if
     * the plugin has not been initialized or has been disposed.
     */
    public MatchEqProcessor getProcessor() {
        return processor;
    }

    /**
     * Returns the parameter descriptors for this plugin.
     *
     * <p>Parameter ids correspond to: 0 = FFT size (enum ordinal), 1 = smoothing
     * (enum ordinal), 2 = amount (0–1), 3 = phase mode (enum ordinal).</p>
     */
    @Override
    public List<PluginParameter> getParameters() {
        int fftMax = MatchEqProcessor.FftSize.values().length - 1;
        int smoothingMax = MatchEqProcessor.Smoothing.values().length - 1;
        int phaseMax = MatchEqProcessor.PhaseMode.values().length - 1;
        return List.of(
                new PluginParameter(0, "FFT Size", 0.0, fftMax,
                        MatchEqProcessor.FftSize.SIZE_2048.ordinal()),
                new PluginParameter(1, "Smoothing", 0.0, smoothingMax,
                        MatchEqProcessor.Smoothing.THIRD_OCTAVE.ordinal()),
                new PluginParameter(2, "Amount", 0.0, 1.0, 1.0),
                new PluginParameter(3, "Phase Mode", 0.0, phaseMax,
                        MatchEqProcessor.PhaseMode.MINIMUM_PHASE.ordinal()));
    }
}
