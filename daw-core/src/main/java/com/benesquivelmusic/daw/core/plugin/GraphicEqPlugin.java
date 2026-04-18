package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.GraphicEqProcessor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Built-in graphic equalizer effect plugin.
 *
 * <p>Wraps the DAW's {@link GraphicEqProcessor} as a first-class plugin so it
 * appears in the Plugins menu alongside external plugins.  A graphic EQ offers
 * fixed-frequency band sliders — 10 ISO octave bands (31.5 Hz – 16 kHz) or
 * 31 ISO third-octave bands (20 Hz – 20 kHz) — that are faster and more
 * intuitive for broad tonal shaping than a parametric EQ.</p>
 *
 * <p>Each band's gain is exposed as a {@link PluginParameter} with a range of
 * ±{@value GraphicEqProcessor#MAX_GAIN_DB}&nbsp;dB, so the generic plugin
 * parameter editor can render one slider per band.</p>
 */
public final class GraphicEqPlugin implements BuiltInDawPlugin {

    /** Stable plugin identifier — used by the host to map plugins to views. */
    public static final String PLUGIN_ID = "com.benesquivelmusic.daw.builtin.graphic-eq";

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
            PLUGIN_ID,
            "Graphic EQ",
            "1.0.0",
            "DAW Built-in",
            PluginType.EFFECT
    );

    private GraphicEqProcessor processor;
    private boolean active;

    public GraphicEqPlugin() {
    }

    @Override
    public String getMenuLabel() {
        return "Graphic EQ";
    }

    @Override
    public String getMenuIcon() {
        return "eq";
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
        processor = new GraphicEqProcessor(context.getAudioChannels(), context.getSampleRate());
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
     * Returns the underlying {@link GraphicEqProcessor}, or {@code null} if
     * the plugin has not been initialized or has been disposed.
     *
     * @return the graphic EQ processor, or {@code null}
     */
    public GraphicEqProcessor getProcessor() {
        return processor;
    }

    /**
     * Returns the per-band gain parameter descriptors for this graphic EQ.
     *
     * <p>One parameter is exposed per band with id equal to the band index,
     * a name of the form {@code "Band <n> (<center-frequency> Hz) Gain (dB)"}
     * (or {@code " kHz"} for frequencies ≥ 1000 Hz), range
     * ±{@value GraphicEqProcessor#MAX_GAIN_DB}&nbsp;dB, and a default of 0&nbsp;dB.</p>
     *
     * <p>The band list reflects the processor's current configuration (octave
     * by default, or third-octave after {@link GraphicEqProcessor#setBandType})
     * once the plugin has been initialized.  Before initialization, the default
     * 10-band octave layout at 44.1&nbsp;kHz is used so hosts can discover
     * parameter metadata without a full context.</p>
     *
     * @return an unmodifiable list of per-band gain parameter descriptors
     */
    @Override
    public List<PluginParameter> getParameters() {
        double[] frequencies = (processor != null)
                ? processor.getFrequencies()
                : new GraphicEqProcessor(1, 44100.0).getFrequencies();

        var params = new ArrayList<PluginParameter>(frequencies.length);
        for (int i = 0; i < frequencies.length; i++) {
            params.add(new PluginParameter(
                    i,
                    formatBandName(i, frequencies[i]),
                    -GraphicEqProcessor.MAX_GAIN_DB,
                    GraphicEqProcessor.MAX_GAIN_DB,
                    0.0));
        }
        return List.copyOf(params);
    }

    private static String formatBandName(int bandIndex, double frequencyHz) {
        String label;
        if (frequencyHz >= 1000.0) {
            double khz = frequencyHz / 1000.0;
            label = (khz == Math.floor(khz))
                    ? "%.0f kHz".formatted(khz)
                    : "%s kHz".formatted(trimTrailingZeros(khz));
        } else {
            label = (frequencyHz == Math.floor(frequencyHz))
                    ? "%.0f Hz".formatted(frequencyHz)
                    : "%s Hz".formatted(trimTrailingZeros(frequencyHz));
        }
        return "Band %d (%s) Gain (dB)".formatted(bandIndex + 1, label);
    }

    private static String trimTrailingZeros(double value) {
        String s = "%.3f".formatted(value);
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 0 && s.charAt(end - 1) == '.') {
            end--;
        }
        return s.substring(0, end);
    }
}
