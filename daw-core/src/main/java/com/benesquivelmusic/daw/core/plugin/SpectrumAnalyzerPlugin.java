package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.analysis.SpectrumAnalyzer;
import com.benesquivelmusic.daw.sdk.analysis.WindowType;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.Objects;

/**
 * Built-in spectrum analyzer plugin.
 *
 * <p>Wraps the DAW's {@link SpectrumAnalyzer} as a first-class plugin
 * so it appears in the Plugins menu alongside external plugins.</p>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link #initialize(PluginContext)} — creates a {@link SpectrumAnalyzer}
 *       configured with the context's sample rate, a default FFT size of 4096,
 *       Hann windowing, and peak hold enabled.</li>
 *   <li>{@link #activate()} — marks the plugin as active.</li>
 *   <li>{@link #deactivate()} — resets the analyzer state and marks inactive.</li>
 *   <li>{@link #dispose()} — releases the analyzer instance.</li>
 * </ol>
 */
public final class SpectrumAnalyzerPlugin implements BuiltInDawPlugin {

    /** Stable plugin identifier — used by the host to map plugins to views. */
    public static final String PLUGIN_ID = "com.benesquivelmusic.daw.spectrum-analyzer";

    /** Default FFT size used when the plugin is initialized. */
    static final int DEFAULT_FFT_SIZE = 4096;

    private static final double DEFAULT_SMOOTHING = 0.8;
    private static final double DEFAULT_PEAK_DECAY_DB = 0.5;

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
            PLUGIN_ID,
            "Spectrum Analyzer",
            "1.0.0",
            "DAW Built-in",
            PluginType.ANALYZER
    );

    private SpectrumAnalyzer analyzer;
    private boolean active;

    public SpectrumAnalyzerPlugin() {
    }

    @Override
    public String getMenuLabel() {
        return "Spectrum Analyzer";
    }

    @Override
    public String getMenuIcon() {
        return "spectrum";
    }

    @Override
    public BuiltInPluginCategory getCategory() {
        return BuiltInPluginCategory.ANALYZER;
    }

    @Override
    public PluginDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void initialize(PluginContext context) {
        Objects.requireNonNull(context, "context must not be null");
        analyzer = new SpectrumAnalyzer(
                DEFAULT_FFT_SIZE,
                context.getSampleRate(),
                DEFAULT_SMOOTHING,
                WindowType.HANN,
                true,
                DEFAULT_PEAK_DECAY_DB
        );
    }

    @Override
    public void activate() {
        active = true;
    }

    @Override
    public void deactivate() {
        active = false;
        if (analyzer != null) {
            analyzer.reset();
        }
    }

    @Override
    public void dispose() {
        active = false;
        analyzer = null;
    }

    /**
     * Returns the {@link SpectrumAnalyzer} created during
     * {@link #initialize(PluginContext)}, or {@code null} if the plugin
     * has not been initialized or has been disposed.
     *
     * @return the spectrum analyzer, or {@code null}
     */
    public SpectrumAnalyzer getAnalyzer() {
        return analyzer;
    }

    /**
     * Returns whether the plugin is currently active.
     *
     * @return {@code true} if active
     */
    public boolean isActive() {
        return active;
    }
}
