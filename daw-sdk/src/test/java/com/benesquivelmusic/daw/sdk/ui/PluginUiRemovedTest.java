package com.benesquivelmusic.daw.sdk.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the removal of the deprecated {@code PluginUI} interface (Plugin View
 * Design Book §8.5.1, story 304): the type must no longer resolve. Its
 * replacement seam is {@link com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory}
 * — §9 rejection #1 mandates that exactly one plugin-GUI seam remains.
 */
class PluginUiRemovedTest {

    @Test
    void pluginUiNoLongerResolves() {
        assertThatThrownBy(() -> Class.forName("com.benesquivelmusic.daw.sdk.ui.PluginUI"))
                .as("PluginUI was removed by story 304 (Plugin View Design Book"
                        + " §8.5.1); PluginEditorFactory is the sole plugin-GUI seam")
                .isInstanceOf(ClassNotFoundException.class);
    }
}
