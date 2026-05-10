package com.benesquivelmusic.daw.app.ui.snapshot;

import com.benesquivelmusic.daw.app.ui.EditorView;
import com.benesquivelmusic.daw.app.ui.theme.ThemeRegistry;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

/**
 * Visual-regression snapshot tests for {@link EditorView}.
 *
 * <p>Renders the editor in its placeholder ("no clip selected") state —
 * the entry point used whenever the user has not yet picked an audio
 * or MIDI clip. Its layout/typography is fully driven by the active
 * theme, making it a useful canary for regressions in
 * {@code .editor-panel} / {@code .placeholder-label} CSS rules.</p>
 */
class EditorViewSnapshotTest extends FxSnapshotTest {

    static Stream<String> bundledThemes() {
        return ThemeRegistry.BUNDLED_IDS.stream();
    }

    @ParameterizedTest(name = "[{index}] theme={0}")
    @MethodSource("bundledThemes")
    void emptyEditorPlaceholder(String themeId) {
        EditorView view = runOnFxThread(EditorView::new);
        assertMatchesSnapshot(view, "emptyEditorPlaceholder", themeId);
    }
}
