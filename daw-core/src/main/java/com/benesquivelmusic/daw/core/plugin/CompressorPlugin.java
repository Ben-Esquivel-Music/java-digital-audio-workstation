package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.CompressorProcessor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Built-in compressor effect plugin.
 *
 * <p>Wraps the DAW's {@link CompressorProcessor} as a first-class plugin
 * so it appears in the Plugins menu alongside external plugins.</p>
 */
public final class CompressorPlugin implements BuiltInDawPlugin {

    /** Stable plugin identifier — used by the host to map plugins to views. */
    public static final String PLUGIN_ID = "com.benesquivelmusic.daw.builtin.compressor";

    /** Parameter ID for the threshold in dB. */
    public static final int PARAM_THRESHOLD = 0;
    /** Parameter ID for the compression ratio. */
    public static final int PARAM_RATIO = 1;
    /** Parameter ID for the attack time in milliseconds. */
    public static final int PARAM_ATTACK_MS = 2;
    /** Parameter ID for the release time in milliseconds. */
    public static final int PARAM_RELEASE_MS = 3;
    /** Parameter ID for the knee width in dB. */
    public static final int PARAM_KNEE_DB = 4;
    /** Parameter ID for the makeup gain in dB. */
    public static final int PARAM_MAKEUP_GAIN_DB = 5;

    private static final List<PluginParameter> PARAMETERS = List.of(
            new PluginParameter(PARAM_THRESHOLD,      "Threshold (dB)",    -60.0,   0.0, -20.0),
            new PluginParameter(PARAM_RATIO,           "Ratio",              1.0,  20.0,   4.0),
            new PluginParameter(PARAM_ATTACK_MS,       "Attack (ms)",        0.1, 200.0,  10.0),
            new PluginParameter(PARAM_RELEASE_MS,      "Release (ms)",      10.0, 1000.0, 100.0),
            new PluginParameter(PARAM_KNEE_DB,         "Knee (dB)",          0.0,  12.0,   6.0),
            new PluginParameter(PARAM_MAKEUP_GAIN_DB,  "Makeup Gain (dB)", -20.0,  20.0,   0.0)
    );

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
            PLUGIN_ID,
            "Compressor",
            "1.0.0",
            "DAW Built-in",
            PluginType.EFFECT
    );

    private CompressorProcessor processor;
    private boolean active;

    public CompressorPlugin() {
    }

    @Override
    public String getMenuLabel() {
        return "Compressor";
    }

    @Override
    public String getMenuIcon() {
        return "compressor";
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
        processor = new CompressorProcessor(context.getAudioChannels(), context.getSampleRate());
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

    @Override
    public List<PluginParameter> getParameters() {
        return PARAMETERS;
    }

    /**
     * Returns the underlying {@link CompressorProcessor}, or {@code null} if
     * the plugin has not been initialized or has been disposed.
     *
     * @return the compressor processor, or {@code null}
     */
    public CompressorProcessor getProcessor() {
        return processor;
    }
}
