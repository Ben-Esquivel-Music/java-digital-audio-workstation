package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.reverb.ConvolutionReverbProcessor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Built-in convolution reverb plugin.
 *
 * <p>Wraps {@link ConvolutionReverbProcessor} so the FFT-partitioned
 * convolution reverb appears in the Plugins menu alongside the other
 * built-in reverbs ({@link ReverbPlugin}, {@link AcousticReverbPlugin}).
 * The plugin ships with a small library of bundled impulse responses
 * (rooms, halls, plates, springs, cathedrals) and can also load custom
 * IR files from disk.</p>
 */
@BuiltInPlugin(label = "Convolution Reverb", icon = "reverb",
        category = BuiltInPluginCategory.EFFECT)
public final class ConvolutionReverbPlugin implements BuiltInDawPlugin {

    /** Stable plugin identifier — used by the host to map plugins to views. */
    public static final String PLUGIN_ID = "com.benesquivelmusic.daw.builtin.convolution-reverb";

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
            PLUGIN_ID,
            "Convolution Reverb",
            "1.0.0",
            "DAW Built-in",
            PluginType.EFFECT
    );

    private ConvolutionReverbProcessor processor;
    private boolean active;

    public ConvolutionReverbPlugin() {
    }

    @Override
    public PluginDescriptor getDescriptor() { return DESCRIPTOR; }

    @Override
    public void initialize(PluginContext context) {
        Objects.requireNonNull(context, "context must not be null");
        processor = new ConvolutionReverbProcessor(
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
     * Returns the underlying {@link ConvolutionReverbProcessor}, or
     * {@code null} if not yet initialized.
     */
    public ConvolutionReverbProcessor getProcessor() { return processor; }

    /**
     * Parameter ids: {@code 0=IR selection}, {@code 1=stretch},
     * {@code 2=predelay (ms)}, {@code 3=low cut (Hz)},
     * {@code 4=high cut (Hz)}, {@code 5=mix}, {@code 6=stereo width},
     * {@code 7=trim start}, {@code 8=trim end}.
     */
    @Override
    public List<PluginParameter> getParameters() {
        return List.of(
                new PluginParameter(0, "IR",         0.0,    7.0,   0.0),
                new PluginParameter(1, "Stretch",    0.5,    2.0,   1.0),
                new PluginParameter(2, "Predelay",   0.0,  200.0,   0.0),
                new PluginParameter(3, "Low Cut",   20.0, 1000.0,  20.0),
                new PluginParameter(4, "High Cut", 1000.0, 20000.0, 20000.0),
                new PluginParameter(5, "Mix",        0.0,    1.0,   0.3),
                new PluginParameter(6, "Width",      0.0,    2.0,   1.0),
                new PluginParameter(7, "Trim Start", 0.0,    1.0,   0.0),
                new PluginParameter(8, "Trim End",   0.0,    1.0,   1.0));
    }
}
