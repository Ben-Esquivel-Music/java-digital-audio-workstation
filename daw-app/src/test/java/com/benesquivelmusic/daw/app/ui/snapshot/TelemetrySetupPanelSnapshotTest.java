package com.benesquivelmusic.daw.app.ui.snapshot;

import com.benesquivelmusic.daw.app.ui.TelemetrySetupPanel;
import com.benesquivelmusic.daw.app.ui.theme.ThemeRegistry;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

/**
 * Visual-regression snapshot tests for {@link TelemetrySetupPanel}.
 */
class TelemetrySetupPanelSnapshotTest extends FxSnapshotTest {

    static Stream<String> bundledThemes() {
        return ThemeRegistry.BUNDLED_IDS.stream();
    }

    @Disabled("Goldens captured on Linux CI; JavaFX font-metric drift on other "
            + "platforms exceeds ImageDiff's default shift radius (line-height "
            + "differences compound down long forms). Re-enable once snapshot "
            + "scenes use a bundled deterministic font, or once per-OS goldens "
            + "are introduced.")
    @ParameterizedTest(name = "[{index}] theme={0}")
    @MethodSource("bundledThemes")
    void emptyTelemetrySetup(String themeId) {
        TelemetrySetupPanel panel = runOnFxThread(TelemetrySetupPanel::new);
        assertMatchesSnapshot(panel, "emptyTelemetrySetup", themeId);
    }
}
