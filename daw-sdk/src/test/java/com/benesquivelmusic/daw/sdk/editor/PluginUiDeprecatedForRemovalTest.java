package com.benesquivelmusic.daw.sdk.editor;

import org.junit.jupiter.api.Test;

import com.benesquivelmusic.daw.sdk.ui.PluginUI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the deprecation contract of the orphaned {@code PluginUI}: it must be
 * annotated {@code @Deprecated(forRemoval = true)} now that the typed
 * {@link PluginEditorFactory} supersedes it (Plugin View Design Book §1.2, §4.3).
 * Story 304 removes it after two release cycles.
 */
class PluginUiDeprecatedForRemovalTest {

    @Test
    void pluginUiIsDeprecatedForRemoval() {
        Deprecated deprecated = PluginUI.class.getAnnotation(Deprecated.class);

        assertThat(deprecated)
                .as("PluginUI must be @Deprecated in favour of PluginEditorFactory")
                .isNotNull();
        assertThat(deprecated.forRemoval())
                .as("PluginUI deprecation must be forRemoval = true")
                .isTrue();
    }
}
