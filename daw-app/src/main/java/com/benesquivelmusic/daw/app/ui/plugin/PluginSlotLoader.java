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

    /**
     * Loads a fresh instance of the plugin {@code entry} names, initialized for {@code format} and
     * activated, in an insert slot that owns its release. Whatever the plugin's own code throws
     * while it is made, initialized, put in a slot or activated — a checked exception it does not
     * declare included — the load keeps nothing of it (an instance that was made is disposed once,
     * and its class loader is closed) and fails unchecked: a checked exception it throws once it is
     * made becomes the cause of an {@link IllegalStateException} whose message is the exception's
     * message or, when it has none, its class's simple name (its full name for an anonymous class,
     * or when its simple name cannot be read),
     * after {@code Could not load plugin:} for a {@link PluginLoadException} from the plugin's
     * {@code initialize()}, which the load takes for the loader's own.
     * A failure of the disposal is suppressed into the plugin's failure, unless it is that very
     * exception. The loader's own {@link PluginLoadException}, which reports that it could not make
     * the plugin, is the cause of an {@code IllegalStateException} whose message starts
     * {@code Could not load plugin:}.
     *
     * @param entry  the installed plugin to load a fresh instance of
     * @param format the session's audio format, which the plugin is initialized for
     * @return the activated plugin's insert slot, which owns its release
     */
    public static InsertSlot load(ExternalPluginEntry entry, AudioFormat format) {
        LoadedPlugin loaded;
        try {
            loaded = ExternalPluginLoader.instantiate(entry, new AudioFormatPluginContext(format));
        } catch (PluginLoadException failure) {
            throw new IllegalStateException(
                    "Could not load plugin: " + PluginText.failureName(failure), failure);
        } catch (Throwable failure) {
            // Its class or its initialize() threw: nothing of it is kept (its class loader is
            // closed, and an instance that was made is disposed).
            throw unchecked(failure);
        }
        try {
            InsertSlot slot = InsertEffectFactory.createSlotFromPlugin(loaded.plugin()).orElseThrow(
                    () -> new IllegalArgumentException("The plugin exposes no audio processor"));
            loaded.plugin().activate();
            slot.setDisposal(loaded.disposal());
            return slot;
        } catch (Throwable failure) {
            // As ExternalPluginLoader.instantiate releases a plugin whose initialize() throws.
            try {
                loaded.disposal().run();
            } catch (Throwable disposalFailure) {
                if (disposalFailure != failure) {
                    failure.addSuppressed(disposalFailure);
                }
            }
            throw unchecked(failure);
        }
    }

    /**
     * {@code failure} itself when it is unchecked (an {@link Error} is thrown from here); a checked
     * exception a plugin does not declare, as the cause of an {@link IllegalStateException} named by
     * {@link PluginText#failureName}.
     */
    private static RuntimeException unchecked(Throwable failure) {
        return switch (failure) {
            case RuntimeException runtime -> runtime;
            case Error error -> throw error;
            default -> new IllegalStateException(PluginText.failureName(failure), failure);
        };
    }
}
