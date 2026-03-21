package com.benesquivelmusic.daw.sdk.ui;

/**
 * Interface for plugins that provide a custom user interface.
 *
 * <p>Implementations create and manage the visual representation of a plugin
 * within the DAW window. The DAW calls {@link #createUI()} to obtain the
 * root UI node when the user opens the plugin editor.</p>
 */
public interface PluginUI {

    /**
     * Creates and returns the root UI component for this plugin.
     *
     * <p>The returned object is expected to be a JavaFX {@code Node} (or subclass).
     * The SDK declares this as {@code Object} to avoid a hard JavaFX dependency
     * in the SDK module itself.</p>
     *
     * @return the root UI node
     */
    Object createUI();

    /**
     * Disposes of UI resources. Called when the plugin editor window is closed.
     */
    void disposeUI();
}
