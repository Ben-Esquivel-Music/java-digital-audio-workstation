package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.analysis.SpectrumAnalyzer;
import com.benesquivelmusic.daw.core.plugin.editor.SpectrumAnalyzerEditor;
import com.benesquivelmusic.daw.sdk.analysis.WindowType;
import com.benesquivelmusic.daw.sdk.editor.PluginCategory;
import com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.Objects;
import java.util.function.Consumer;
import com.benesquivelmusic.daw.core.analysis.AnalyzerProcessor;
import com.benesquivelmusic.daw.core.analysis.AnalyzerSnapshot;

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
@BuiltInPlugin(label = "Spectrum Analyzer", icon = "spectrum", category = BuiltInPluginCategory.ANALYZER)
public final class SpectrumAnalyzerPlugin implements BuiltInDawPlugin, LiveAnalyzerPlugin {

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
            PluginType.ANALYZER,
            PluginCategory.ANALYZER,
            "spectrum"
    );

    private PluginContext context;
    private SpectrumAnalyzer analyzer;
    private boolean active;
    private long analysisRevision;

    @Override public long analysisRevision() { return analysisRevision; }
    private volatile com.benesquivelmusic.daw.sdk.visualization.SpectrumData latestSpectrum;

    @Override
    public AnalyzerProcessor createAnalysisConsumer(Consumer<AnalyzerSnapshot> publish) {
        var configured = analyzer;
        return new AnalyzerProcessor(AnalyzerProcessor.Kind.SPECTRUM, publish, () -> 440,
                configured == null ? DEFAULT_FFT_SIZE : configured.getFftSize(),
                configured == null ? WindowType.HANN : configured.getWindowType());
    }

    @Override
    public void acceptAnalysis(AnalyzerSnapshot snapshot) {
        latestSpectrum = snapshot instanceof AnalyzerSnapshot.Spectrum spectrum ? spectrum.data() : null;
    }

    public com.benesquivelmusic.daw.sdk.visualization.SpectrumData getLatestSpectrum() {
        return latestSpectrum;
    }

    public SpectrumAnalyzerPlugin() {
    }

    @Override
    public PluginDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void initialize(PluginContext context) {
        analysisRevision++;
        Objects.requireNonNull(context, "context must not be null");
        this.context = context;
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
        analysisRevision++;
        active = false;
        latestSpectrum = null;
        if (analyzer != null) {
            analyzer.reset();
        }
    }

    @Override
    public void dispose() {
        analysisRevision++;
        active = false;
        latestSpectrum = null;
        analyzer = null;
        context = null;
    }

    /**
     * Reconfigures the analyzer with a new FFT size and/or window type.
     *
     * <p>Because an analyzer's FFT/window configuration is fixed, this method creates a
     * new instance with the given parameters and the sample rate from the
     * original {@link #initialize(PluginContext)} call. Must be called after
     * {@code initialize()} and before {@code dispose()}.</p>
     *
     * @param fftSize    new FFT size (must be a power of two)
     * @param windowType new window function
     * @throws IllegalStateException if the plugin has not been initialized
     */
    public void reconfigure(int fftSize, WindowType windowType) {
        if (context == null) {
            throw new IllegalStateException("Plugin has not been initialized");
        }
        Objects.requireNonNull(windowType, "windowType must not be null");
        analyzer = new SpectrumAnalyzer(
                fftSize,
                context.getSampleRate(),
                DEFAULT_SMOOTHING,
                windowType,
                true,
                DEFAULT_PEAK_DECAY_DB
        );
        analysisRevision++;
        latestSpectrum = null;
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

    /**
     * {@inheritDoc}
     *
     * <p>Story 302 (Plugin View Design Book §8.3 item 5): returns the
     * immersive {@link SpectrumAnalyzerEditor} canvas, replacing the legacy
     * {@code SpectrumDisplayWindow} route. The old window's FFT-size /
     * window-type toolbar is intentionally not part of the immersive surface
     * (§5.D — the canvas owns every pixel);
     * {@link #reconfigure(int, WindowType)} remains available to automation
     * and future stories. The docked spectrum panel ({@code PANEL_SPECTRUM},
     * the app-side {@code SpectrumDisplay} fed by the app's metering pipeline)
     * is a separate app-side surface. The editor renders this plugin's latest
     * host-fed spectrum snapshot; no renderer is duplicated across the module boundary.</p>
     */
    @Override
    public PluginEditorFactory editorFactory() {
        return new SpectrumAnalyzerEditor(this);
    }
}
