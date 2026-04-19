package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.WaveshaperProcessor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Built-in waveshaper / saturation effect plugin.
 *
 * <p>Wraps the DAW's {@link WaveshaperProcessor} as a first-class plugin so it
 * appears in the Plugins menu alongside external plugins. Provides drive, mix,
 * output gain, oversampling-factor, and transfer-function parameters for
 * high-quality, antialiased nonlinear saturation.</p>
 */
@BuiltInPlugin(label = "Waveshaper / Saturation", icon = "waveshaper", category = BuiltInPluginCategory.EFFECT)
public final class WaveshaperPlugin implements BuiltInDawPlugin {

    /** Stable plugin identifier — used by the host to map plugins to views. */
    public static final String PLUGIN_ID = "com.benesquivelmusic.daw.builtin.waveshaper";

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
            PLUGIN_ID,
            "Waveshaper / Saturation",
            "1.0.0",
            "DAW Built-in",
            PluginType.EFFECT
    );

    private WaveshaperProcessor processor;
    private boolean active;

    public WaveshaperPlugin() {
    }

    @Override
    public PluginDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void initialize(PluginContext context) {
        Objects.requireNonNull(context, "context must not be null");
        processor = new WaveshaperProcessor(context.getAudioChannels(), context.getSampleRate());
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
     * Returns the underlying {@link WaveshaperProcessor}, or {@code null} if
     * the plugin has not been initialized or has been disposed.
     *
     * @return the waveshaper processor, or {@code null}
     */
    public WaveshaperProcessor getProcessor() {
        return processor;
    }

    /**
     * Returns the parameter descriptors for this waveshaper plugin.
     *
     * <p>Parameter ids correspond to: 0 = drive (dB), 1 = mix (0-1),
     * 2 = output gain (dB), 3 = oversampling factor (enum ordinal:
     * NONE=0, TWO_X=1, FOUR_X=2, EIGHT_X=3), 4 = transfer function
     * (enum ordinal: SOFT_CLIP=0, HARD_CLIP=1, TUBE_SATURATION=2,
     * TAPE_SATURATION=3, CUSTOM=4).</p>
     *
     * @return an unmodifiable list of waveshaper parameter descriptors
     */
    @Override
    public List<PluginParameter> getParameters() {
        int oversampleMax = WaveshaperProcessor.OversampleFactor.values().length - 1;
        int transferMax = WaveshaperProcessor.TransferFunction.values().length - 1;
        return List.of(
                new PluginParameter(0, "Drive (dB)",
                        WaveshaperProcessor.MIN_DRIVE_DB,
                        WaveshaperProcessor.MAX_DRIVE_DB, 0.0),
                new PluginParameter(1, "Mix", 0.0, 1.0, 1.0),
                new PluginParameter(2, "Output Gain (dB)",
                        WaveshaperProcessor.MIN_OUTPUT_GAIN_DB,
                        WaveshaperProcessor.MAX_OUTPUT_GAIN_DB, 0.0),
                new PluginParameter(3, "Oversampling", 0.0, oversampleMax,
                        WaveshaperProcessor.OversampleFactor.TWO_X.ordinal()),
                new PluginParameter(4, "Transfer Function", 0.0, transferMax,
                        WaveshaperProcessor.TransferFunction.SOFT_CLIP.ordinal()));
    }
}
