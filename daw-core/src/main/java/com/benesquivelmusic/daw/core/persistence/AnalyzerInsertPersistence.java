package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.plugin.LiveAnalyzerPlugin;
import com.benesquivelmusic.daw.core.plugin.SoundWaveTelemetryPlugin;
import com.benesquivelmusic.daw.core.plugin.SpectrumAnalyzerPlugin;
import com.benesquivelmusic.daw.core.plugin.TunerPlugin;
import com.benesquivelmusic.daw.sdk.analysis.WindowType;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import org.w3c.dom.Element;

/** Stable identities and configuration for transparent, hosted analyzer inserts. */
final class AnalyzerInsertPersistence {
    private static final System.Logger LOGGER = System.getLogger(AnalyzerInsertPersistence.class.getName());
    private static final int MAX_PERSISTED_FFT_SIZE = 65_536;

    private AnalyzerInsertPersistence() { }

    static void write(Element element, InsertSlot slot) {
        if (!(slot.getPlugin() instanceof LiveAnalyzerPlugin plugin)) return;
        element.setAttribute("analyzer-plugin-id", plugin.getDescriptor().id());
        element.setAttribute("analyzer-active", Boolean.toString(plugin.isActive()));
        if (plugin instanceof SpectrumAnalyzerPlugin spectrum && spectrum.getAnalyzer() != null) {
            element.setAttribute("fft-size", Integer.toString(spectrum.getAnalyzer().getFftSize()));
            element.setAttribute("window-type", spectrum.getAnalyzer().getWindowType().name());
        } else if (plugin instanceof TunerPlugin tuner) {
            element.setAttribute("reference-pitch-hz", Double.toString(tuner.getReferencePitchHz()));
        }
    }

    static InsertSlot read(Element element, AudioFormat format) {
        LiveAnalyzerPlugin plugin = switch (element.getAttribute("analyzer-plugin-id")) {
            case SpectrumAnalyzerPlugin.PLUGIN_ID -> new SpectrumAnalyzerPlugin();
            case TunerPlugin.PLUGIN_ID -> new TunerPlugin();
            case SoundWaveTelemetryPlugin.PLUGIN_ID -> new SoundWaveTelemetryPlugin();
            default -> null;
        };
        if (plugin == null) return null;
        plugin.initialize(new PluginContext() {
            @Override public double getSampleRate() { return format.sampleRate(); }
            @Override public int getBufferSize() { return format.bufferSize(); }
            @Override public int getAudioChannels() { return format.channels(); }
            @Override public void log(String message) { LOGGER.log(System.Logger.Level.INFO, message); }
        });
        restoreConfiguration(element, plugin);
        if (!element.hasAttribute("analyzer-active")
                || Boolean.parseBoolean(element.getAttribute("analyzer-active"))) {
            plugin.activate();
        }
        String name = element.hasAttribute("name") ? element.getAttribute("name") : plugin.getDescriptor().name();
        return new InsertSlot(name, plugin.asAudioProcessor().orElseThrow(), null, plugin);
    }

    private static void restoreConfiguration(Element element, LiveAnalyzerPlugin plugin) {
        try {
            if (plugin instanceof SpectrumAnalyzerPlugin spectrum) {
                int fftSize = element.hasAttribute("fft-size")
                        ? Integer.parseInt(element.getAttribute("fft-size")) : spectrum.getAnalyzer().getFftSize();
                if (fftSize <= 0 || fftSize > MAX_PERSISTED_FFT_SIZE || (fftSize & (fftSize - 1)) != 0) {
                    throw new IllegalArgumentException("FFT size must be a power of two up to " + MAX_PERSISTED_FFT_SIZE);
                }
                WindowType window = element.hasAttribute("window-type")
                        ? WindowType.valueOf(element.getAttribute("window-type")) : spectrum.getAnalyzer().getWindowType();
                spectrum.reconfigure(fftSize, window);
            } else if (plugin instanceof TunerPlugin tuner && element.hasAttribute("reference-pitch-hz")) {
                double reference = Double.parseDouble(element.getAttribute("reference-pitch-hz"));
                if (!Double.isFinite(reference)) throw new IllegalArgumentException("Reference pitch must be finite");
                tuner.setReferencePitchHz(reference);
            }
        } catch (IllegalArgumentException error) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Invalid analyzer configuration for {0}; retaining defaults: {1}",
                    plugin.getDescriptor().id(), error.getMessage());
        }
    }
}
