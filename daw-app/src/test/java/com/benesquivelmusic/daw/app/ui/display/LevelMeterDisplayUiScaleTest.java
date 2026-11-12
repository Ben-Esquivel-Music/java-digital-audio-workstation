package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.app.ui.theme.UiScale;

import javafx.scene.Group;
import javafx.scene.Scene;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 335 (studio ruling R6; review engineer N3) — what the user sees of
 * {@link LevelMeterDisplay} at a larger UI scale: its clip bar (the latched
 * LED, painted and clickable) and its dB-scale ticks are their 100&nbsp;%
 * size times the factor, on a live scale change. The display draws no text,
 * so a font assertion would pin nothing.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class LevelMeterDisplayUiScaleTest {

    private Preferences prefs;
    private ThemeManager appearance;

    @BeforeEach
    void isolateTheThemeManager() {
        prefs = Preferences.userRoot().node("levelMeterDisplayUiScale_" + System.nanoTime());
        appearance = new ThemeManager(prefs);
        ThemeManager.setDefaultForTest(appearance);
    }

    @AfterEach
    void restore() throws BackingStoreException {
        ThemeManager.setDefaultForTest(null);
        prefs.removeNode();
    }

    @Test
    void theClipBarAndTheTicksFollowALiveScaleChange() {
        double[] measured = runOnFxThread(() -> {
            LevelMeterDisplay display = new LevelMeterDisplay(true);
            display.setPrefSize(24, 160);
            Group root = new Group(display);
            Scene scene = new Scene(root, 200, 300);
            appearance.applyTo(scene);
            root.applyCss();
            root.layout();
            double clipAtOneHundred = display.clipBarPx();
            double tickAtOneHundred = display.tickLengthPx();
            boolean belowTheBarAtOneHundred = display.isInClipZone(12, 5.5);
            appearance.setUiScale(UiScale.P150);
            double[] values = {clipAtOneHundred, tickAtOneHundred, belowTheBarAtOneHundred ? 1 : 0,
                    display.clipBarPx(), display.tickLengthPx(), display.isInClipZone(12, 5.5) ? 1 : 0};
            display.dispose();
            return values;
        });

        assertThat(measured[0]).as("clip bar at 100%%").isEqualTo(LevelMeterDisplay.CLIP_BAR_PX);
        assertThat(measured[3]).as("clip bar at 150%%").isCloseTo(LevelMeterDisplay.CLIP_BAR_PX * 1.5, within(1e-9));
        assertThat(measured[1]).as("tick length at 100%%").isPositive();
        assertThat(measured[4]).as("tick length at 150%%").isCloseTo(measured[1] * 1.5, within(1e-9));
        assertThat(measured[2]).as("5.5 px from the top is past the 4 px bar at 100%%").isZero();
        assertThat(measured[5]).as("5.5 px from the top is on the 6 px bar at 150%%").isEqualTo(1.0);
    }
}
