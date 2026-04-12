package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.plugin.clap.ClapException;
import com.benesquivelmusic.daw.core.plugin.clap.ClapPluginHost;
import com.benesquivelmusic.daw.core.plugin.clap.ClapPluginScanner;
import com.benesquivelmusic.daw.sdk.plugin.ExternalPluginHost;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the discovery, loading, and lifecycle of CLAP plugins for
 * use in mixer channel insert slots.
 *
 * <p>This class bridges the {@link ClapPluginScanner} (discovery) and
 * {@link ClapPluginHost} (hosting) subsystems, exposing a high-level API
 * that the mixer UI can use to scan for installed plugins, load them into
 * channel insert slots, and manage their lifecycle.</p>
 *
 * <h2>Usage</h2>
 * <ol>
 *   <li>{@link #scanForPlugins()} — discovers all installed CLAP plugins</li>
 *   <li>{@link #getAvailablePlugins()} — lists discovered plugin paths</li>
 *   <li>{@link #loadPlugin(Path, int, PluginContext)} — loads and initializes
 *       a CLAP plugin, returning an {@link InsertSlot} ready for use in a
 *       mixer channel</li>
 * </ol>
 */
public final class ClapPluginManager {

    private static final Logger LOGGER = Logger.getLogger(ClapPluginManager.class.getName());

    private final ClapPluginScanner scanner;
    private List<Path> availablePlugins;

    /**
     * Creates a manager using the default system CLAP plugin scanner.
     */
    public ClapPluginManager() {
        this(new ClapPluginScanner());
    }

    /**
     * Creates a manager using the given scanner.
     *
     * @param scanner the CLAP plugin scanner to use for discovery
     */
    public ClapPluginManager(ClapPluginScanner scanner) {
        this.scanner = Objects.requireNonNull(scanner, "scanner must not be null");
        this.availablePlugins = List.of();
    }

    /**
     * Scans all configured search paths for CLAP plugins.
     *
     * @return the number of plugins discovered
     */
    public int scanForPlugins() {
        availablePlugins = scanner.scan();
        LOGGER.log(Level.INFO, "CLAP plugin scan found {0} plugins", availablePlugins.size());
        return availablePlugins.size();
    }

    /**
     * Returns an unmodifiable list of discovered CLAP plugin file paths.
     *
     * <p>Call {@link #scanForPlugins()} first to populate this list.</p>
     *
     * @return the list of discovered plugin paths
     */
    public List<Path> getAvailablePlugins() {
        return Collections.unmodifiableList(availablePlugins);
    }

    /**
     * Returns the underlying scanner.
     *
     * @return the CLAP plugin scanner
     */
    public ClapPluginScanner getScanner() {
        return scanner;
    }

    /**
     * Loads a CLAP plugin from the given library path at the given plugin index,
     * initializes and activates it, and returns an {@link InsertSlot} that can
     * be inserted into a mixer channel's effects chain.
     *
     * <p>The returned {@link InsertSlot} uses a {@link ClapInsertEffect} as its
     * processor, which provides crash-resilient audio processing.</p>
     *
     * @param libraryPath  path to the {@code .clap} shared library
     * @param pluginIndex  index of the plugin within the library's factory
     * @param context      the plugin context providing sample rate and buffer size
     * @return an {@link InsertSlot} wrapping the loaded CLAP plugin
     * @throws ClapException if the plugin fails to load, initialize, or activate
     */
    public InsertSlot loadPlugin(Path libraryPath, int pluginIndex, PluginContext context) {
        Objects.requireNonNull(libraryPath, "libraryPath must not be null");
        Objects.requireNonNull(context, "context must not be null");

        ClapPluginHost host = new ClapPluginHost(libraryPath, pluginIndex, 2, 2);
        try {
            host.initialize(context);
            host.activate();
        } catch (ClapException e) {
            safeDispose(host);
            throw e;
        } catch (Exception e) {
            safeDispose(host);
            throw new ClapException("Failed to load CLAP plugin: " + e.getMessage(), e);
        }

        return InsertEffectFactory.createSlotFromPlugin(host)
                .orElseThrow(() -> {
                    safeDispose(host);
                    return new ClapException(
                            "CLAP plugin does not provide an AudioProcessor: "
                                    + host.getDescriptor().name());
                });
    }

    /**
     * Loads a CLAP plugin using the first plugin in the library (index 0).
     *
     * @param libraryPath path to the {@code .clap} shared library
     * @param context     the plugin context
     * @return an {@link InsertSlot} wrapping the loaded CLAP plugin
     * @throws ClapException if the plugin fails to load
     */
    public InsertSlot loadPlugin(Path libraryPath, PluginContext context) {
        return loadPlugin(libraryPath, 0, context);
    }

    /**
     * Disposes of a CLAP plugin that was loaded into an insert slot.
     *
     * <p>If the slot's processor is a {@link ClapInsertEffect}, this method
     * deactivates and disposes the underlying {@link ClapPluginHost}. For
     * non-CLAP insert slots, this method is a no-op.</p>
     *
     * @param slot the insert slot to dispose
     */
    public void disposePlugin(InsertSlot slot) {
        Objects.requireNonNull(slot, "slot must not be null");
        if (slot.getProcessor() instanceof ClapInsertEffect clapEffect) {
            safeDispose(clapEffect.getPluginHost());
        }
    }

    /**
     * Saves the state of a CLAP plugin in the given insert slot.
     *
     * @param slot the insert slot containing a CLAP plugin
     * @return the serialized plugin state, or an empty array if not a CLAP plugin
     */
    public byte[] savePluginState(InsertSlot slot) {
        Objects.requireNonNull(slot, "slot must not be null");
        if (slot.getProcessor() instanceof ClapInsertEffect clapEffect) {
            return clapEffect.getPluginHost().saveState();
        }
        return new byte[0];
    }

    /**
     * Loads state into a CLAP plugin in the given insert slot.
     *
     * @param slot  the insert slot containing a CLAP plugin
     * @param state the serialized state to restore
     */
    public void loadPluginState(InsertSlot slot, byte[] state) {
        Objects.requireNonNull(slot, "slot must not be null");
        Objects.requireNonNull(state, "state must not be null");
        if (slot.getProcessor() instanceof ClapInsertEffect clapEffect) {
            clapEffect.getPluginHost().loadState(state);
        }
    }

    /**
     * Returns whether the given insert slot contains a CLAP plugin.
     *
     * @param slot the insert slot to check
     * @return {@code true} if the slot's processor is a CLAP plugin
     */
    public static boolean isClapInsert(InsertSlot slot) {
        Objects.requireNonNull(slot, "slot must not be null");
        return slot.getProcessor() instanceof ClapInsertEffect;
    }

    /**
     * Returns the {@link ExternalPluginHost} from the given insert slot, or
     * {@code null} if the slot does not contain a CLAP plugin.
     *
     * @param slot the insert slot to inspect
     * @return the external plugin host, or {@code null}
     */
    public static ExternalPluginHost getClapHost(InsertSlot slot) {
        Objects.requireNonNull(slot, "slot must not be null");
        if (slot.getProcessor() instanceof ClapInsertEffect clapEffect) {
            return clapEffect.getPluginHost();
        }
        return null;
    }

    private static void safeDispose(ExternalPluginHost host) {
        try {
            host.dispose();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error disposing CLAP plugin host", e);
        }
    }
}
