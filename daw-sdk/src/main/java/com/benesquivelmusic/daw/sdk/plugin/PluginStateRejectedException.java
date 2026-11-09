package com.benesquivelmusic.daw.sdk.plugin;

import java.io.Serial;

/**
 * Thrown by {@link ExternalPluginHost#loadState(byte[])} when the plugin
 * itself reports that it did not take the state, or has no state support but
 * was given a non-empty state: the plugin rejecting its state (story 334,
 * studio ruling E4), as opposed to the call failing, which a host reports
 * with another exception.
 */
public final class PluginStateRejectedException extends IllegalArgumentException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what the plugin did with the state, for the log
     */
    public PluginStateRejectedException(String message) {
        super(message);
    }
}
