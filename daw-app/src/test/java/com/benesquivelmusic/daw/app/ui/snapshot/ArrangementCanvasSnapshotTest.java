package com.benesquivelmusic.daw.app.ui.snapshot;

import com.benesquivelmusic.daw.app.ui.ArrangementCanvas;
import com.benesquivelmusic.daw.app.ui.theme.ThemeRegistry;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

/**
 * Visual-regression snapshot tests for {@link ArrangementCanvas}.
 *
 * <p>Renders an empty arrangement canvas (no project) in each bundled
 * theme and compares the rendered image against a golden PNG. See
 * {@link FxSnapshotTest} for the rebaselining workflow.</p>
 */
class ArrangementCanvasSnapshotTest extends FxSnapshotTest {

    static Stream<String> bundledThemes() {
        return ThemeRegistry.BUNDLED_IDS.stream();
    }

    @ParameterizedTest(name = "[{index}] theme={0}")
    @MethodSource("bundledThemes")
    void emptyArrangementCanvas(String themeId) {
        ArrangementCanvas canvas = runOnFxThread(() -> {
            ArrangementCanvas c = new ArrangementCanvas();
            c.setPrefSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
            return c;
        });
        assertMatchesSnapshot(canvas, "emptyArrangementCanvas", themeId);
    }
}
