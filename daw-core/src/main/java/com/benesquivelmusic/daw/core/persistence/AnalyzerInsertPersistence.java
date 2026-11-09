package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.OfflineCause;
import com.benesquivelmusic.daw.core.plugin.AudioFormatPluginContext;
import com.benesquivelmusic.daw.core.plugin.LiveAnalyzerPlugin;
import com.benesquivelmusic.daw.core.plugin.SoundWaveTelemetryPlugin;
import com.benesquivelmusic.daw.core.plugin.SpectrumAnalyzerPlugin;
import com.benesquivelmusic.daw.core.plugin.TunerPlugin;
import com.benesquivelmusic.daw.sdk.analysis.WindowType;
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

    /**
     * Whether {@code analyzerId} names an analyzer this build has: the ids
     * {@link #read} rebuilds.
     *
     * @param analyzerId an {@code analyzer-plugin-id} as saved
     * @return {@code true} for a known analyzer
     */
    static boolean knows(String analyzerId) {
        return switch (analyzerId) {
            case SpectrumAnalyzerPlugin.PLUGIN_ID, TunerPlugin.PLUGIN_ID, SoundWaveTelemetryPlugin.PLUGIN_ID -> true;
            default -> false;
        };
    }

    /**
     * Logs an analyzer insert this build does not know, which the reader keeps
     * offline at its place, as saved (story 334, studio ruling E6 (b)):
     * {@code <strip>: insert <k> '<name>' analyzer '<id>' is not an analyzer
     * this build knows; it is kept as saved and passes audio through}, the
     * name and the id quoted by {@link UnreadProjectItem#logText}. Logged
     * only, not counted: an analyzer changes nothing the engineer hears
     * (story 330, studio ruling D1).
     *
     * @param strip       the mixer strip the insert is saved on, as the log names it
     * @param insertIndex the insert's zero-based position among the strip's saved inserts
     * @param name        the name the offline insert shows
     * @param analyzerId  the {@code analyzer-plugin-id} as saved
     */
    static void logUnknown(UnreadProjectItem.Location strip, int insertIndex, String name, String analyzerId) {
        LOGGER.log(System.Logger.Level.WARNING, strip.describe() + ": insert " + (insertIndex + 1) + " '"
                + UnreadProjectItem.logText(name) + "' "
                + OfflineInsertDetails.detail(new OfflineCause.UnknownAnalyzer(analyzerId))
                + "; it is kept as saved and passes audio through");
    }

    /**
     * Rebuilds the analyzer insert {@code element} saves; the reader asks
     * {@link #knows} first.
     *
     * @param element     the {@code <insert>} element
     * @param format      the project's audio format, which the analyzer is
     *                    initialized with
     * @param strip       the mixer strip the insert is saved on, as the log
     *                    names it: the owning track for a track channel,
     *                    {@code Mixer master}, {@code Mixer return bus <n>
     *                    '<name>'}, or {@code Mixer channel <n>} for a
     *                    channel no track owns
     * @param insertIndex the insert's zero-based position among the strip's
     *                    saved {@code <insert>} elements (the log numbers it
     *                    from 1)
     * @return the insert slot
     * @throws IllegalArgumentException for an analyzer this build does not
     *                                  {@linkplain #knows know}
     */
    static InsertSlot read(Element element, AudioFormat format, UnreadProjectItem.Location strip, int insertIndex) {
        String analyzerId = element.getAttribute("analyzer-plugin-id");
        LiveAnalyzerPlugin plugin = switch (analyzerId) {
            case SpectrumAnalyzerPlugin.PLUGIN_ID -> new SpectrumAnalyzerPlugin();
            case TunerPlugin.PLUGIN_ID -> new TunerPlugin();
            case SoundWaveTelemetryPlugin.PLUGIN_ID -> new SoundWaveTelemetryPlugin();
            default -> throw new IllegalArgumentException("not an analyzer this build knows: " + analyzerId);
        };
        plugin.initialize(new AudioFormatPluginContext(format));
        restoreConfiguration(element, plugin, strip.describe() + ": insert " + (insertIndex + 1) + " analyzer "
                + plugin.getDescriptor().id());
        if (!element.hasAttribute("analyzer-active")
                || Boolean.parseBoolean(element.getAttribute("analyzer-active"))) {
            plugin.activate();
        }
        String name = element.hasAttribute("name") ? element.getAttribute("name") : plugin.getDescriptor().name();
        return new InsertSlot(name, plugin.asAudioProcessor().orElseThrow(), null, plugin);
    }

    /**
     * Applies the saved configuration, or keeps the analyzer's defaults whole
     * when a saved value cannot be used — a usable sibling setting is not
     * applied either — with a WARNING naming the insert, the attribute and
     * its value as saved ({@link #keepDefaults}).
     *
     * @param insert the insert as the log names it: {@code <strip>: insert
     *               <k> analyzer <plugin-id>}
     */
    private static void restoreConfiguration(Element element, LiveAnalyzerPlugin plugin, String insert) {
        if (plugin instanceof SpectrumAnalyzerPlugin spectrum) {
            int fftSize = spectrum.getAnalyzer().getFftSize();
            if (element.hasAttribute("fft-size")) {
                String raw = element.getAttribute("fft-size");
                try {
                    fftSize = Integer.parseInt(raw);
                } catch (NumberFormatException e) {
                    keepDefaults(insert, "fft-size", raw, "is not an integer");
                    return;
                }
                if (fftSize <= 0 || fftSize > MAX_PERSISTED_FFT_SIZE || (fftSize & (fftSize - 1)) != 0) {
                    keepDefaults(insert, "fft-size", raw, "is not a power of two up to " + MAX_PERSISTED_FFT_SIZE);
                    return;
                }
            }
            WindowType window = spectrum.getAnalyzer().getWindowType();
            if (element.hasAttribute("window-type")) {
                String raw = element.getAttribute("window-type");
                try {
                    window = WindowType.valueOf(raw);
                } catch (IllegalArgumentException e) {
                    keepDefaults(insert, "window-type", raw, "is not a window type");
                    return;
                }
            }
            spectrum.reconfigure(fftSize, window);
        } else if (plugin instanceof TunerPlugin tuner && element.hasAttribute("reference-pitch-hz")) {
            String raw = element.getAttribute("reference-pitch-hz");
            double reference;
            try {
                reference = Double.parseDouble(raw);
            } catch (NumberFormatException e) {
                keepDefaults(insert, "reference-pitch-hz", raw, "is not a number");
                return;
            }
            // Also false for NaN, which the tuner's own range check lets through.
            if (!(reference >= TunerPlugin.MIN_REFERENCE_PITCH_HZ && reference <= TunerPlugin.MAX_REFERENCE_PITCH_HZ)) {
                keepDefaults(insert, "reference-pitch-hz", raw, "is not between " + TunerPlugin.MIN_REFERENCE_PITCH_HZ
                        + " and " + TunerPlugin.MAX_REFERENCE_PITCH_HZ + " Hz");
                return;
            }
            tuner.setReferencePitchHz(reference);
        }
    }

    /**
     * Logs {@code <strip>: insert <k> analyzer <plugin-id> <attribute> '<raw>'
     * <reason>; the analyzer's saved settings are not applied and its
     * defaults are used}, the raw value quoted by
     * {@link UnreadProjectItem#logText} — never a parse exception's message,
     * which would carry the file's text as is. Logged only: an analyzer's
     * settings are view preferences, not saved content the open reports.
     */
    private static void keepDefaults(String insert, String attribute, String raw, String reason) {
        LOGGER.log(System.Logger.Level.WARNING, insert + " " + attribute + " '" + UnreadProjectItem.logText(raw)
                + "' " + reason + "; the analyzer's saved settings are not applied and its defaults are used");
    }
}
