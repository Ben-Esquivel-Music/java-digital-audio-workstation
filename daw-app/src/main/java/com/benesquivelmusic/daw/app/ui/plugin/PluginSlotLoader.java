package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.plugin.AudioFormatPluginContext;
import com.benesquivelmusic.daw.core.plugin.ExternalPluginEntry;
import com.benesquivelmusic.daw.core.plugin.ExternalPluginLoader;
import com.benesquivelmusic.daw.core.plugin.LoadedPlugin;
import com.benesquivelmusic.daw.core.plugin.PluginLoadException;

/** Loads a fresh graph instance; the installed registry entry remains a catalogue item. */
public final class PluginSlotLoader {
    private PluginSlotLoader() { }

    public static InsertSlot load(ExternalPluginEntry entry, AudioFormat format) {
        LoadedPlugin loaded;
        try {
            loaded = ExternalPluginLoader.instantiate(entry, new AudioFormatPluginContext(format));
        } catch (PluginLoadException failure) {
            throw new IllegalStateException("Could not load plugin: " + failure.getMessage(), failure);
        }
        try {
            InsertSlot slot = InsertEffectFactory.createSlotFromPlugin(loaded.plugin()).orElseThrow(
                    () -> new IllegalArgumentException("The plugin exposes no audio processor"));
            loaded.plugin().activate();
            slot.setDisposal(loaded.disposal());
            return slot;
        } catch (RuntimeException | Error failure) {
            try { loaded.disposal().run(); } catch (RuntimeException | Error disposalFailure) {
                failure.addSuppressed(disposalFailure);
            }
            throw failure;
        }
    }
}
