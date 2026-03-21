package com.benesquivelmusic.daw.core.plugin;

/**
 * Thrown when an external plugin cannot be loaded or instantiated.
 */
public final class PluginLoadException extends Exception {

    public PluginLoadException(String message) {
        super(message);
    }

    public PluginLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
