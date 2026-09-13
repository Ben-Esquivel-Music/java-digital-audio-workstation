package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.plugin.ExternalPluginEntry;
import com.benesquivelmusic.daw.core.plugin.ExternalPluginLoader;
import com.benesquivelmusic.daw.core.plugin.PluginLoadException;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import java.util.logging.Logger;

/** Loads a fresh graph instance; the installed registry entry remains a catalogue item. */
public final class PluginSlotLoader {
    private static final Logger LOG = Logger.getLogger(PluginSlotLoader.class.getName());
    private PluginSlotLoader() { }

    public static InsertSlot load(ExternalPluginEntry entry, AudioFormat format) {
        ExternalPluginLoader.LoadResult loaded;
        try {
            loaded = ExternalPluginLoader.loadWithClassLoader(entry);
        } catch (PluginLoadException failure) {
            throw new IllegalStateException("Could not load plugin: " + failure.getMessage(), failure);
        }
        try {
            var plugin = loaded.plugin();
            plugin.initialize(new PluginContext() {
                @Override public double getSampleRate() { return format.sampleRate(); }
                @Override public int getBufferSize() { return format.bufferSize(); }
                @Override public int getAudioChannels() { return format.channels(); }
                @Override public void log(String message) { LOG.info(message); }
            });
            InsertSlot slot = InsertEffectFactory.createSlotFromPlugin(plugin).orElseThrow(
                    () -> new IllegalArgumentException("The plugin exposes no audio processor"));
            plugin.activate();
            slot.setDisposal(() -> dispose(loaded));
            return slot;
        } catch (RuntimeException | Error failure) {
            try { dispose(loaded); } catch (RuntimeException | Error disposalFailure) {
                failure.addSuppressed(disposalFailure);
            }
            throw failure;
        }
    }

    private static void dispose(ExternalPluginLoader.LoadResult loaded) {
        try { loaded.plugin().dispose(); }
        finally { ExternalPluginLoader.closeQuietly(loaded.classLoader()); }
    }
}
