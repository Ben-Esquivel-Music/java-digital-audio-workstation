package com.benesquivelmusic.daw.sdk.ui;

/**
 * Interface for plugins that provide a custom user interface.
 *
 * <p>Implementations create and manage the visual representation of a plugin
 * within the DAW window. The DAW calls {@link #createUI()} to obtain the
 * root UI node when the user opens the plugin editor.</p>
 *
 * @deprecated Superseded by the typed
 *     {@link com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory} editor
 *     contract (Plugin View Design Book §1.2, §1.3, §4.3). This interface was
 *     never consumed by the host and returns an untyped {@code Object} with no
 *     compile-time contract; a plugin now ships its GUI by overriding
 *     {@link com.benesquivelmusic.daw.sdk.plugin.DawPlugin#editorFactory()}.
 *     Scheduled for removal after two release cycles (Phase 5 / story 304).
 * @see com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory
 * @see com.benesquivelmusic.daw.sdk.plugin.DawPlugin#editorFactory()
 */
@Deprecated(forRemoval = true)
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
