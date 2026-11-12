package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;
import com.benesquivelmusic.daw.app.ui.status.SessionStatusStrip;
import com.benesquivelmusic.daw.app.ui.status.StatusStripCell;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.app.ui.theme.UiScale;

import javafx.scene.Scene;
import javafx.scene.layout.VBox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 335 review (engineer S1) — the Session Status Strip's §4.4 collapse
 * thresholds are 100&nbsp;% pixels, compared with the strip's width divided
 * by the UI-scale factor (the A16 rule of the toolbar overflow), and the rule
 * re-runs when the scale changes: a 1280&nbsp;px strip shows its two full rows
 * at 100&nbsp;%, and at 200&nbsp;% (640 unscaled px) it collapses to one row
 * with every collapsible cell in the overflow popover.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class SessionStatusStripUiScaleTest {

    private Preferences prefs;
    private ThemeManager appearance;

    @BeforeEach
    void isolateTheThemeManager() {
        prefs = Preferences.userRoot().node("sessionStatusStripUiScale_" + System.nanoTime());
        appearance = new ThemeManager(prefs);
        ThemeManager.setDefaultForTest(appearance);
    }

    @AfterEach
    void restore() throws BackingStoreException {
        ThemeManager.setDefaultForTest(null);
        prefs.removeNode();
    }

    /** What the strip shows: whether the overflow button is shown, and the collapsed cells. */
    private record Layout(boolean overflowShown, List<StatusStripCell> collapsed, int collapsibleCells) {
    }

    @Test
    void aLiveScaleChangeReRunsTheCollapseRuleInUnscaledPixels() throws Exception {
        Layout[] layouts = Story307TestSupport.onFx(() -> {
            SessionStatusStrip strip = new SessionStatusStrip(
                    new ProjectOperationProgress(new FxDispatcher()), MotionManager.getDefault());
            VBox root = new VBox(strip);
            Scene scene = new Scene(root, 1280, 200);
            appearance.applyTo(scene);
            root.applyCss();
            root.layout();
            Layout atOneHundred = layout(strip);
            appearance.setUiScale(UiScale.P200);
            root.applyCss();
            root.layout();
            Layout atTwoHundred = layout(strip);
            appearance.setUiScale(UiScale.P100);
            root.applyCss();
            root.layout();
            Layout backAtOneHundred = layout(strip);
            return new Layout[] {atOneHundred, atTwoHundred, backAtOneHundred};
        }, 30, TimeUnit.SECONDS);

        assertThat(layouts[0].overflowShown()).as("1280 px at 100%%: two full rows, no overflow").isFalse();
        assertThat(layouts[0].collapsed()).as("nothing collapsed at 100%%").isEmpty();
        assertThat(layouts[1].overflowShown()).as("1280 px at 200%% is 640 unscaled px: overflow shown").isTrue();
        assertThat(layouts[1].collapsed()).as("every collapsible cell is in the popover at 640 unscaled px")
                .hasSize(layouts[1].collapsibleCells());
        assertThat(layouts[2].overflowShown()).as("back at 100%% the two rows return").isFalse();
    }

    @Test
    void aStripBuiltAtNinetyPercentKeepsItsTwoRowsWhereOneHundredPercentWould() throws Exception {
        Layout layout = Story307TestSupport.onFx(() -> {
            appearance.setUiScale(UiScale.P90);
            SessionStatusStrip strip = new SessionStatusStrip(
                    new ProjectOperationProgress(new FxDispatcher()), MotionManager.getDefault());
            VBox root = new VBox(strip);
            Scene scene = new Scene(root, 1100, 200);
            appearance.applyTo(scene);
            root.applyCss();
            root.layout();
            return layout(strip);
        }, 30, TimeUnit.SECONDS);

        assertThat(layout.overflowShown()).as("1100 px at 90%% is 1222 unscaled px: two full rows").isFalse();
    }

    private static Layout layout(SessionStatusStrip strip) {
        List<StatusStripCell> collapsible = List.of(strip.workspaceCell(), strip.schemaCell(), strip.lockCell(),
                strip.diskCell(), strip.journalCell());
        return new Layout(strip.isOverflowButtonVisible(), List.copyOf(strip.overflowCells()), collapsible.size());
    }
}
