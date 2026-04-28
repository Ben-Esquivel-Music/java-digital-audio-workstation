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

    /**
     * Routes automation values to the underlying processor. Parameter ids
     * match {@link #getParameters()}: {@code 0} frequency, {@code 1} drive,
     * {@code 2} mix, {@code 3} output, {@code 4} mode (enum ordinal — values
     * outside the enum range are clamped to the nearest valid ordinal).
     */
    @Override
    public void setAutomatableParameter(int parameterId, double value) {
        if (processor == null) {
            return;
        }
        switch (parameterId) {
            case 0 -> processor.setFrequencyHz(clamp(value,
                    ExciterProcessor.MIN_FREQUENCY_HZ,
                    ExciterProcessor.MAX_FREQUENCY_HZ));
            case 1 -> processor.setDrivePercent(clamp(value,
                    ExciterProcessor.MIN_DRIVE_PERCENT,
                    ExciterProcessor.MAX_DRIVE_PERCENT));
            case 2 -> processor.setMixPercent(clamp(value,
                    ExciterProcessor.MIN_MIX_PERCENT,
                    ExciterProcessor.MAX_MIX_PERCENT));
            case 3 -> processor.setOutputGainDb(clamp(value,
                    ExciterProcessor.MIN_OUTPUT_GAIN_DB,
                    ExciterProcessor.MAX_OUTPUT_GAIN_DB));
            case 4 -> {
                ExciterProcessor.Mode[] modes = ExciterProcessor.Mode.values();
                int idx = (int) Math.round(clamp(value, 0.0, modes.length - 1.0));
                processor.setMode(modes[idx]);
            }
            default -> { /* unknown parameter id */ }
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.min(hi, Math.max(lo, v));
    }
}
