package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.MultibandCompressorProcessor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Built-in multiband compressor effect plugin.
 *
 * <p>Wraps {@link MultibandCompressorProcessor} as a first-class built-in
 * plugin so it appears in the Plugins menu alongside the existing
 * {@link CompressorPlugin} and {@link BusCompressorPlugin}.  The processor
 * splits the signal into 3 to 5 frequency bands using a Linkwitz-Riley
 * 4th-order crossover network and applies independent dynamics to each band
 * — the standard tool for surgical mastering and complex bus processing.</p>
 *
 * <h2>Default configuration</h2>
 * <p>The plugin initializes with {@value #DEFAULT_BAND_COUNT} bands and the
 * crossover layout {@code [200 Hz, 2000 Hz, 8000 Hz]}.  The band count can
 * be changed at any time via {@link #setBandCount(int)} (3, 4, or 5);
 * crossover frequencies and per-band controls are accessed through the
 * underlying {@linkplain #getProcessor() processor}.</p>
 */
@BuiltInPlugin(label = "Multiband Compressor", icon = "compressor", category = BuiltInPluginCategory.EFFECT)
public final class MultibandCompressorPlugin implements BuiltInDawPlugin {

    /** Stable plugin identifier — used by the host to map plugins to views. */
    public static final String PLUGIN_ID = "com.benesquivelmusic.daw.builtin.multibandcompressor";

    /** Default number of bands when the plugin is first initialized. */
    public static final int DEFAULT_BAND_COUNT = 4;

    /** Maximum number of bands supported by this plugin (and the underlying processor). */
    public static final int MAX_BAND_COUNT = 5;

    /** Minimum number of bands (per spec the multiband plugin supports 3–5 bands). */
    public static final int MIN_BAND_COUNT = 3;

    /** Default crossover frequencies for each supported band count, in Hz. */
    private static final double[][] DEFAULT_CROSSOVERS = {
            /* 3 bands */ {250.0, 4000.0},
            /* 4 bands */ {200.0, 2000.0, 8000.0},
            /* 5 bands */ {120.0, 500.0, 2500.0, 8000.0}
    };

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
            PLUGIN_ID,
            "Multiband Compressor",
            "1.0.0",
            "DAW Built-in",
            PluginType.EFFECT
    );

    private MultibandCompressorProcessor processor;
    private PluginContext context;
    private int bandCount = DEFAULT_BAND_COUNT;
    private boolean linearPhase;
    private boolean active;

    public MultibandCompressorPlugin() {
    }

    @Override
    public PluginDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void initialize(PluginContext context) {
        this.context = Objects.requireNonNull(context, "context must not be null");
        rebuildProcessor();
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
        context = null;
    }

    @Override
    public Optional<AudioProcessor> asAudioProcessor() {
        return Optional.ofNullable(processor);
    }

    /**
     * Returns the underlying {@link MultibandCompressorProcessor}, or
     * {@code null} if the plugin has not been initialized or has been disposed.
     *
     * @return the multiband compressor processor, or {@code null}
     */
    public MultibandCompressorProcessor getProcessor() {
        return processor;
    }

    /**
     * Returns the current band count (3, 4, or 5).
     *
     * @return the active band count
     */
    public int getBandCount() {
        return bandCount;
    }

    /**
     * Sets the band count and rebuilds the underlying processor with the
     * default crossover layout for that band count.  Per-band parameters are
     * reset to their defaults.
     *
     * @param bandCount the desired band count, must be {@value #MIN_BAND_COUNT}
     *                  to {@value #MAX_BAND_COUNT}
     * @throws IllegalArgumentException if {@code bandCount} is out of range
     * @throws IllegalStateException    if the plugin has not been initialized
     */
    public void setBandCount(int bandCount) {
        if (bandCount < MIN_BAND_COUNT || bandCount > MAX_BAND_COUNT) {
            throw new IllegalArgumentException(
                    "bandCount must be in [" + MIN_BAND_COUNT + ", " + MAX_BAND_COUNT
                            + "]: " + bandCount);
        }
        if (context == null) {
            throw new IllegalStateException(
                    "plugin must be initialized before changing the band count");
        }
        if (this.bandCount == bandCount && processor != null) {
            return;
        }
        this.bandCount = bandCount;
        rebuildProcessor();
    }

    /**
     * Returns whether the linear-phase crossover mode is requested.
     *
     * <p>When enabled, the host should engage a linear-phase crossover
     * implementation suitable for mastering contexts (at the cost of
     * additional latency reported via plugin delay compensation).  The
     * current built-in processor implements zero-latency IIR Linkwitz-Riley
     * crossovers; this flag is preserved so projects can persist the user's
     * preference until the linear-phase variant lands.</p>
     *
     * @return {@code true} if linear-phase mode is requested
     */
    public boolean isLinearPhase() {
        return linearPhase;
    }

    /**
     * Sets whether the linear-phase crossover mode is requested.
     *
     * @param linearPhase {@code true} to request linear-phase crossovers
     */
    public void setLinearPhase(boolean linearPhase) {
        this.linearPhase = linearPhase;
    }

    /**
     * Returns the parameter descriptors for this multiband compressor plugin.
     *
     * <p>Parameter ids are laid out in two sections:</p>
     * <ul>
     *   <li><b>0</b>: {@code Band Count} (3..5)</li>
     *   <li><b>1</b>: {@code Linear Phase} (0/1)</li>
     *   <li><b>2..5</b>: {@code Crossover N (Hz)} for the four possible
     *       crossover points; defaults match the {@link #DEFAULT_BAND_COUNT}
     *       layout, with any trailing slots populated with sensible
     *       higher-frequency placeholders for use after a band-count up-shift.</li>
     *   <li><b>6 + 8*band + offset</b>: per-band parameters where {@code band}
     *       is in {@code 0..4} and {@code offset} is one of:
     *       0=Threshold (dB), 1=Ratio, 2=Attack (ms), 3=Release (ms),
     *       4=Makeup Gain (dB), 5=Bypass, 6=Mute, 7=Solo.</li>
     * </ul>
     *
     * @return an unmodifiable list of multiband compressor parameter descriptors
     */
    @Override
    public List<PluginParameter> getParameters() {
        var params = new ArrayList<PluginParameter>(2 + 4 + MAX_BAND_COUNT * 8);
        params.add(new PluginParameter(0, "Band Count",
                MIN_BAND_COUNT, MAX_BAND_COUNT, DEFAULT_BAND_COUNT));
        params.add(new PluginParameter(1, "Linear Phase", 0.0, 1.0, 0.0));

        // Defaults align with the processor's actual initial state
        // (DEFAULT_BAND_COUNT crossovers); slots beyond DEFAULT_BAND_COUNT - 1
        // fall back to high-frequency placeholders so the schema is still
        // valid for users who later up-shift the band count.
        double[] defaultCrossovers = DEFAULT_CROSSOVERS[DEFAULT_BAND_COUNT - MIN_BAND_COUNT];
        double[] fallbackCrossovers = DEFAULT_CROSSOVERS[MAX_BAND_COUNT - MIN_BAND_COUNT];
        for (int i = 0; i < 4; i++) {
            double def;
            if (i < defaultCrossovers.length) {
                def = defaultCrossovers[i];
            } else if (i < fallbackCrossovers.length) {
                def = fallbackCrossovers[i];
            } else {
                def = 16000.0;
            }
            params.add(new PluginParameter(2 + i,
                    "Crossover " + (i + 1) + " (Hz)", 20.0, 20000.0, def));
        }

        int base = 6;
        for (int band = 0; band < MAX_BAND_COUNT; band++) {
            int b = base + band * 8;
            String prefix = "Band " + (band + 1) + " ";
            params.add(new PluginParameter(b,     prefix + "Threshold (dB)", -60.0,    0.0, -20.0));
            params.add(new PluginParameter(b + 1, prefix + "Ratio",            1.0,   20.0,   4.0));
            params.add(new PluginParameter(b + 2, prefix + "Attack (ms)",      0.01, 100.0,  10.0));
            params.add(new PluginParameter(b + 3, prefix + "Release (ms)",    10.0, 1000.0, 100.0));
            params.add(new PluginParameter(b + 4, prefix + "Makeup Gain (dB)", 0.0,   30.0,   0.0));
            params.add(new PluginParameter(b + 5, prefix + "Bypass",           0.0,    1.0,   0.0));
            params.add(new PluginParameter(b + 6, prefix + "Mute",             0.0,    1.0,   0.0));
            params.add(new PluginParameter(b + 7, prefix + "Solo",             0.0,    1.0,   0.0));
        }
        return List.copyOf(params);
    }

    private void rebuildProcessor() {
        if (context == null) {
            return;
        }
        double[] crossovers = DEFAULT_CROSSOVERS[bandCount - MIN_BAND_COUNT];
        processor = new MultibandCompressorProcessor(
                context.getAudioChannels(),
                context.getSampleRate(),
                Arrays.copyOf(crossovers, crossovers.length));
    }
}
