package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.saturation.ExciterProcessor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Built-in Harmonic Exciter / Psychoacoustic Enhancer plugin.
 *
 * <p>Wraps {@link ExciterProcessor} as a first-class plugin so it appears in
 * the Plugins menu alongside other built-in effects. Adds subtle high-frequency
 * harmonic content (controlled 2nd- and 3rd-order distortion) to perceptually
 * brighten a signal without raising broadband level — the classic "Aural
 * Exciter" trick popularized by Aphex.</p>
 */
@BuiltInPlugin(label = "Harmonic Exciter", icon = "exciter", category = BuiltInPluginCategory.EFFECT)
public final class ExciterPlugin implements BuiltInDawPlugin {

    /** Stable plugin identifier — used by the host to map plugins to views. */
    public static final String PLUGIN_ID = "com.benesquivelmusic.daw.builtin.exciter";

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
            PLUGIN_ID,
            "Harmonic Exciter",
            "1.0.0",
            "DAW Built-in",
            PluginType.EFFECT
    );

    private ExciterProcessor processor;
    private boolean active;

    public ExciterPlugin() {
    }

    @Override
    public PluginDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void initialize(PluginContext context) {
        Objects.requireNonNull(context, "context must not be null");
        processor = new ExciterProcessor(context.getAudioChannels(), context.getSampleRate());
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
     * Returns the underlying {@link ExciterProcessor}, or {@code null} if the
     * plugin has not been initialized or has been disposed.
     */
    public ExciterProcessor getProcessor() {
        return processor;
    }

    /**
     * Returns the parameter descriptors for this plugin.
     *
     * <p>Parameter ids: 0 = frequency (Hz), 1 = drive (%), 2 = mix (%),
     * 3 = output (dB), 4 = mode (enum ordinal: CLASS_A_TUBE=0,
     * TRANSFORMER=1, TAPE=2).</p>
     */
    @Override
    public List<PluginParameter> getParameters() {
        int modeMax = ExciterProcessor.Mode.values().length - 1;
        return List.of(
                new PluginParameter(0, "Frequency (Hz)",
                        ExciterProcessor.MIN_FREQUENCY_HZ,
                        ExciterProcessor.MAX_FREQUENCY_HZ,
                        8_000.0),
                new PluginParameter(1, "Drive (%)",
                        ExciterProcessor.MIN_DRIVE_PERCENT,
                        ExciterProcessor.MAX_DRIVE_PERCENT,
                        25.0),
                new PluginParameter(2, "Mix (%)",
                        ExciterProcessor.MIN_MIX_PERCENT,
                        ExciterProcessor.MAX_MIX_PERCENT,
                        25.0),
                new PluginParameter(3, "Output (dB)",
                        ExciterProcessor.MIN_OUTPUT_GAIN_DB,
                        ExciterProcessor.MAX_OUTPUT_GAIN_DB,
                        0.0),
                new PluginParameter(4, "Mode", 0.0, modeMax,
                        ExciterProcessor.Mode.CLASS_A_TUBE.ordinal()));
    }
}
