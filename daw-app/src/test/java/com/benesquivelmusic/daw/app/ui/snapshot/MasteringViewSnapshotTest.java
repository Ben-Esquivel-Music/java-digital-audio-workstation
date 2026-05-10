package com.benesquivelmusic.daw.app.ui.snapshot;

import com.benesquivelmusic.daw.app.ui.MasteringView;
import com.benesquivelmusic.daw.app.ui.theme.ThemeRegistry;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

/**
 * Visual-regression snapshot tests for {@link MasteringView} (the
 * "MasteringChainView" referenced in the original story — class is
 * named {@code MasteringView} in this codebase).
 */
class MasteringViewSnapshotTest extends FxSnapshotTest {

    static Stream<String> bundledThemes() {
        return ThemeRegistry.BUNDLED_IDS.stream();
    }

    @ParameterizedTest(name = "[{index}] theme={0}")
    @MethodSource("bundledThemes")
    void defaultMasteringChain(String themeId) {
        MasteringView view = runOnFxThread(MasteringView::new);
        assertMatchesSnapshot(view, "defaultMasteringChain", themeId);
    }
}
