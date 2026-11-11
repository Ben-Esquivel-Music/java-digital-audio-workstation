package com.benesquivelmusic.daw.core.plugin;

import java.util.Objects;

/**
 * An entry the installed-plugin registry gave up — replaced by an install
 * of the same id or unregistered (story 334, critique J9). The registry only
 * takes it out; its caller runs {@code disposal} off the JavaFX application
 * thread and outside the registry: it disposes the entry's catalogue
 * instance, then closes the class loader that loaded it — nothing for an
 * entry that never loaded. Instances already running in an open project
 * are their slots' own and are not touched (studio ruling E2).
 *
 * @param plugin   the entry as it was installed
 * @param disposal disposes the catalogue instance and closes its loader
 */
public record RemovedPlugin(InstalledPlugin plugin, Runnable disposal) {

    public RemovedPlugin {
        Objects.requireNonNull(plugin, "plugin must not be null");
        Objects.requireNonNull(disposal, "disposal must not be null");
    }
}
