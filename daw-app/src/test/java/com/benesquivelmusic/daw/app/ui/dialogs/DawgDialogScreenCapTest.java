package com.benesquivelmusic.daw.app.ui.dialogs;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.app.ui.theme.UiScale;

import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;
import javafx.stage.Window;

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
 * Story 335 (studio ruling R6; amendment A22) — a {@link DawgDialog} window
 * never extends past its screen: on showing, and again when the UI scale
 * changes while it is open, its width and height are capped to the screen's
 * visual bounds minus {@link DawgDialog#SCREEN_MARGIN} and its position is
 * clamped inside them. The width band is the §5.9 width times the scale.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class DawgDialogScreenCapTest {

    private static final Rectangle2D SCREEN = new Rectangle2D(100, 50, 900, 600);

    private Preferences prefs;
    private ThemeManager appearance;
    private DawgDialog<ButtonType> dialog;

    @BeforeEach
    void isolateTheThemeManager() {
        prefs = Preferences.userRoot().node("dawgDialogScreenCap_" + System.nanoTime());
        appearance = new ThemeManager(prefs);
        ThemeManager.setDefaultForTest(appearance);
    }

    @AfterEach
    void closeAndRestore() {
        try {
            runOnFxThread(() -> {
                if (dialog != null) {
                    dialog.close();
                }
                return null;
            });
        } finally {
            // Even when the FX teardown fails, the override never reaches the next class in the fork.
            ThemeManager.setDefaultForTest(null);
            try {
                prefs.removeNode();
            } catch (BackingStoreException | IllegalStateException alreadyGone) {
                // never flushed
            }
        }
    }

    @Test
    void aTallWideDialogIsCappedAndClampedIntoItsScreenAndItsContentScrolls() {
        double[] window = runOnFxThread(() -> {
            dialog = tallDialog();
            dialog.capBounds(SCREEN);
            dialog.show();
            Window stage = dialog.getDialogPane().getScene().getWindow();
            return new double[] {stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()};
        });

        assertInside(window);
        assertThat(window[3]).as("the 2000 px content is capped to the screen height minus the margins")
                .isCloseTo(SCREEN.getHeight() - 2 * DawgDialog.SCREEN_MARGIN, within(0.5));
    }

    @Test
    void theWidthBandIsTheBandTimesTheScaleWhenItFits() {
        double[] widths = runOnFxThread(() -> {
            dialog = tallDialog();
            dialog.sized(DawgDialog.Size.MEDIUM);
            dialog.capBounds(new Rectangle2D(0, 0, 1000, 1000));
            dialog.show();
            double atOneHundred = dialog.getDialogPane().getPrefWidth();
            appearance.setUiScale(UiScale.P150);
            Window stage = dialog.getDialogPane().getScene().getWindow();
            return new double[] {atOneHundred, dialog.getDialogPane().getPrefWidth(), stage.getWidth()};
        });

        assertThat(widths[0]).isEqualTo(640);
        assertThat(widths[1]).as("640 × 1.5").isEqualTo(960);
        assertThat(widths[2]).as("the window re-sizes to the wider pane").isGreaterThanOrEqualTo(960);
    }

    @Test
    void aScaleChangeWhileOpenRefitsTheWindowIntoItsScreenAndBackOut() {
        double[][] windows = runOnFxThread(() -> {
            dialog = tallDialog();
            dialog.sized(DawgDialog.Size.MEDIUM);
            dialog.capBounds(SCREEN);
            dialog.show();
            Window stage = dialog.getDialogPane().getScene().getWindow();
            appearance.setUiScale(UiScale.P200);
            double[] atTwoHundred = {stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()};
            appearance.setUiScale(UiScale.P100);
            double[] backAtOneHundred = {stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()};
            return new double[][] {atTwoHundred, backAtOneHundred};
        });

        assertInside(windows[0]);
        assertThat(windows[0][2]).as("the 1280 px band is capped to the screen width minus the margins")
                .isCloseTo(SCREEN.getWidth() - 2 * DawgDialog.SCREEN_MARGIN, within(0.5));
        assertInside(windows[1]);
        assertThat(windows[1][2]).as("back at 100%% the 640 px band fits again").isCloseTo(640, within(0.5));
    }

    /**
     * Review 2b-java S1 — {@code showAndWait()}, the way Settings opens in
     * production, fires {@code DIALOG_SHOWN} before its window exists; the
     * fit (decorations measured, X/Y clamped) still runs once the window has
     * shown. The window is read from a runnable queued before the call, which
     * runs inside {@code showAndWait}'s nested event loop and then closes it.
     */
    @Test
    void showAndWaitClampsTheShownWindowIntoItsScreen() {
        double[] window = runOnFxThread(() -> {
            dialog = tallDialog();
            dialog.sized(DawgDialog.Size.MEDIUM);
            dialog.capBounds(SCREEN);
            double[] seen = new double[4];
            Platform.runLater(() -> {
                Window stage = dialog.getDialogPane().getScene().getWindow();
                seen[0] = stage.getX();
                seen[1] = stage.getY();
                seen[2] = stage.getWidth();
                seen[3] = stage.getHeight();
                dialog.close();
            });
            dialog.showAndWait();
            return seen;
        });

        assertThat(window[2]).as("the window was shown").isPositive();
        assertInside(window);
    }

    /**
     * Review 2b-java N3 — a preferred size a caller sets while the dialog is
     * capped is not replaced by the stale pre-cap size at the next re-fit.
     */
    @Test
    void aSizeSetWhileCappedSurvivesTheNextRefit() {
        double[] heights = runOnFxThread(() -> {
            dialog = tallDialog();
            dialog.capBounds(SCREEN);
            dialog.show();
            double capped = dialog.getDialogPane().getPrefHeight();
            dialog.getDialogPane().setPrefHeight(300);
            appearance.setUiScale(UiScale.P110);
            return new double[] {capped, dialog.getDialogPane().getPrefHeight()};
        });

        assertThat(heights[0]).as("capped on show").isCloseTo(SCREEN.getHeight() - 2 * DawgDialog.SCREEN_MARGIN,
                within(0.5));
        assertThat(heights[1]).as("the caller's 300 px, not the pre-cap size").isEqualTo(300);
    }

    private static DawgDialog<ButtonType> tallDialog() {
        DawgDialog<ButtonType> dialog = new DawgDialog<>();
        dialog.setTitle("Tall");
        Region tall = new Region();
        tall.setPrefSize(400, 2000);
        dialog.getDialogPane().setContent(new ScrollPane(tall));
        dialog.getDialogPane().getButtonTypes().add(new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE));
        return dialog;
    }

    private static void assertInside(double[] window) {
        double margin = DawgDialog.SCREEN_MARGIN;
        assertThat(window[0]).as("x").isGreaterThanOrEqualTo(SCREEN.getMinX() + margin - 0.5);
        assertThat(window[1]).as("y").isGreaterThanOrEqualTo(SCREEN.getMinY() + margin - 0.5);
        assertThat(window[0] + window[2]).as("right edge").isLessThanOrEqualTo(SCREEN.getMaxX() - margin + 0.5);
        assertThat(window[1] + window[3]).as("bottom edge").isLessThanOrEqualTo(SCREEN.getMaxY() - margin + 0.5);
    }
}
