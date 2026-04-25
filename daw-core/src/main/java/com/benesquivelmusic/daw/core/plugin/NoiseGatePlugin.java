package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.dynamics.NoiseGateProcessor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Built-in noise-gate effect plugin.
 *
 * <p>Wraps {@link NoiseGateProcessor} as a first-class built-in plugin,
 * exposing its threshold, hysteresis, attack/hold/release timing, range,
 * lookahead, and sidechain controls as automatable plugin parameters.</p>
 *
 * <p>The classic use cases are:</p>
 * <ul>
 *   <li><b>Vocal mic</b> — gate out room hiss between phrases.</li>
 *   <li><b>Drum mics</b> — silence bleed between hits; pair with a sidechain
 *       trigger track filtered to the kick band (e.g., 50–100&nbsp;Hz, Q&nbsp;≈&nbsp;0.7)
 *       to gate the kick mic from a clean trigger.</li>
 *   <li><b>Guitar amp</b> — kill amp hum during quiet passages.</li>
 * </ul>
 */
@BuiltInPlugin(label = "Noise Gate", icon = "compressor", category = BuiltInPluginCategory.EFFECT)
public final class NoiseGatePlugin implements BuiltInDawPlugin {

    /** Stable plugin identifier — used by the host to map plugins to views. */
    public static final String PLUGIN_ID = "com.benesquivelmusic.daw.builtin.noisegate";

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
            PLUGIN_ID,
            "Noise Gate",
            "1.0.0",
            "DAW Built-in",
            PluginType.EFFECT
    );

    private NoiseGateProcessor processor;
    private boolean active;

    public NoiseGatePlugin() {
    }

    @Override
    public PluginDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void initialize(PluginContext context) {
        Objects.requireNonNull(context, "context must not be null");
        processor = new NoiseGateProcessor(context.getAudioChannels(), context.getSampleRate());
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
     * Returns the underlying {@link NoiseGateProcessor}, or {@code null} if
     * the plugin has not been initialized or has been disposed.
     */
    public NoiseGateProcessor getProcessor() {
        return processor;
    }

    /**
     * Returns the parameter descriptors for this noise-gate plugin.
     *
     * <p>Parameter ids correspond to: 0=threshold (dB), 1=hysteresis (dB),
     * 2=attack (ms), 3=hold (ms), 4=release (ms), 5=range (dB),
     * 6=lookahead (ms), 7=sidechain enabled toggle (0=off, 1=on),
     * 8=sidechain filter frequency (Hz), 9=sidechain filter Q. The "Toggle"
     * suffix on parameter 7 is significant: the generic parameter editor
     * renders 0/1 parameters as on/off controls only when the parameter name
     * contains "toggle".</p>
     */
    @Override
    public List<PluginParameter> getParameters() {
        return List.of(
                new PluginParameter(0, "Threshold (dB)",        -80.0,    0.0,  -40.0),
                new PluginParameter(1, "Hysteresis (dB)",         0.0,   24.0,    3.0),
                new PluginParameter(2, "Attack (ms)",             0.01,  50.0,    1.0),
                new PluginParameter(3, "Hold (ms)",               0.0,  500.0,   50.0),
                new PluginParameter(4, "Release (ms)",            1.0,  500.0,  100.0),
                new PluginParameter(5, "Range (dB)",            -80.0,    0.0,  -80.0),
                new PluginParameter(6, "Lookahead (ms)",          0.0,   10.0,    0.0),
                new PluginParameter(7, "Sidechain Enabled Toggle", 0.0,    1.0,   0.0),
                new PluginParameter(8, "Sidechain Filter Freq (Hz)", 20.0, 2000.0, 80.0),
                new PluginParameter(9, "Sidechain Filter Q",      0.1,   10.0,    0.7));
    }

    /**
     * Routes an automation value to the underlying {@link NoiseGateProcessor}.
     *
     * <p>Parameter ids match {@link #getParameters()}. The sidechain enabled
     * toggle is decoded with a {@code 0.5} threshold.</p>
     */
    @Override
    public void setAutomatableParameter(int parameterId, double value) {
        if (processor == null) {
            return;
        }
        switch (parameterId) {
            case 0 -> processor.setThresholdDb(clamp(value, -80.0, 0.0));
            case 1 -> processor.setHysteresisDb(clamp(value, 0.0, 24.0));
            case 2 -> processor.setAttackMs(clamp(value, 0.01, 50.0));
            case 3 -> processor.setHoldMs(clamp(value, 0.0, 500.0));
            case 4 -> processor.setReleaseMs(clamp(value, 1.0, 500.0));
            case 5 -> processor.setRangeDb(clamp(value, -80.0, 0.0));
            case 6 -> processor.setLookaheadMs(clamp(value, 0.0, 10.0));
            case 7 -> processor.setSidechainEnabled(value >= 0.5);
            case 8 -> processor.setSidechainFilterFreqHz(clamp(value, 20.0, 2000.0));
            case 9 -> processor.setSidechainFilterQ(clamp(value, 0.1, 10.0));
            default -> { /* unknown parameter id */ }
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.min(hi, Math.max(lo, v));
    }
}
