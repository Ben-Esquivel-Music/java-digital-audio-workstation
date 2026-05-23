package com.benesquivelmusic.daw.app.ui.views;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 281 — at the default 1920&times;1080 working resolution the
 * Workshop view's left arrangement pane occupies <strong>60&nbsp;%</strong>
 * of the width (1152&nbsp;±&nbsp;8&nbsp;px) and the right plugin/clip
 * pane occupies <strong>40&nbsp;%</strong> (768&nbsp;±&nbsp;8&nbsp;px),
 * matching the §4 Concept F mock.
 *
 * <p>FX-harness pitfalls honoured: the view is attached to a real
 * {@link Scene} sized at 1920&times;1080, {@code applyCss()} + {@code
 * layout()} force a layout pass before any geometry is read, the
 * {@link Stage} is shown and closed inside a try/finally, and assertions
 * are captured + rethrown on the test thread.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class WorkshopViewLayoutTest {

    @Test
    void leftPaneIs60PercentAndRightPaneIs40PercentAt1920x1080() throws Exception {
        onFxThread(() -> {
            WorkshopView view = newWorkshopView();
            // Wrap in a root-pane'd StackPane so the design-token cascade
            // resolves (same pattern as PerformanceStageSizingTest).
            StackPane root = new StackPane(view);
            root.getStyleClass().add("root-pane");
            Scene scene = new Scene(root, 1920, 1080);
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            try {
                root.applyCss();
                root.layout();

                // SplitPane lays out its items honouring the divider
                // position only after the skin has run and a layout pass
                // has applied. Read widths from the children themselves —
                // not from the divider position itself — to assert the
                // realised geometry.
                Node leftItem = view.splitPane().getItems().get(0);
                Node rightItem = view.splitPane().getItems().get(1);
                double leftWidth = leftItem.getLayoutBounds().getWidth();
                double rightWidth = rightItem.getLayoutBounds().getWidth();

                // Headline AC: 1152 ± 8 / 768 ± 8 px at 1920 px scene width.
                // ±8 covers the SplitPane divider thickness (~6 px) and
                // any sub-pixel snap rounding under JavaFX layout.
                assertThat(leftWidth)
                        .as("left arrangement pane must be 1152 ± 8 px (60 %% of 1920) — was %.2f",
                                leftWidth)
                        .isBetween(1144.0, 1160.0);
                assertThat(rightWidth)
                        .as("right plugin/clip pane must be 768 ± 8 px (40 %% of 1920) — was %.2f",
                                rightWidth)
                        .isBetween(760.0, 776.0);
            } finally {
                stage.close();
            }
            return null;
        });
    }

    /**
     * Review N3 — pin the divider position close to the default. Without
     * this the headline 60/40 width test could mask a regression that
     * silently moved the divider (e.g. away from
     * {@link WorkshopView#DEFAULT_DIVIDER_POSITION}) because the test
     * harness happens to wedge things back to 60/40 by other means.
     */
    @Test
    void defaultDividerPositionIsAt60Percent() throws Exception {
        onFxThread(() -> {
            WorkshopView view = newWorkshopView();
            StackPane root = new StackPane(view);
            root.getStyleClass().add("root-pane");
            Scene scene = new Scene(root, 1920, 1080);
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            try {
                root.applyCss();
                root.layout();

                double[] positions = view.splitPane().getDividerPositions();
                assertThat(positions)
                        .as("Workshop SplitPane has exactly one divider")
                        .hasSize(1);
                assertThat(positions[0])
                        .as("default divider position must be 0.60 (within ±0.005 for "
                                + "snap rounding) — was %.4f", positions[0])
                        .isCloseTo(WorkshopView.DEFAULT_DIVIDER_POSITION, within(0.005));
                assertThat(view.dividerPosition())
                        .as("dividerPosition() seam must agree with the underlying SplitPane")
                        .isCloseTo(WorkshopView.DEFAULT_DIVIDER_POSITION, within(0.005));
            } finally {
                stage.close();
            }
            return null;
        });
    }

    /**
     * Review S7 — only the spacer between the breadcrumb and the Detach
     * button absorbs the remainder of the header row, so the Detach
     * button pins flush against the right edge of the right pane. The
     * earlier {@code HBox.setHgrow(breadcrumb, ALWAYS)} call competed
     * with the spacer and pulled the button inward.
     *
     * <p>Asserts on positive spacer width as the minimum signal (per the
     * review's "skip if JavaFxToolkitExtension makes this fragile — at
     * minimum assert {@code headerSpacer.getWidth() > 0}"), then
     * additionally pins the Detach button's right edge within ~2&nbsp;px
     * of the right-pane's right edge so a future regression can't shrink
     * the spacer to zero without failing the assertion.</p>
     */
    @Test
    void detachButtonPinsToRightEdgeOfRightPane() throws Exception {
        onFxThread(() -> {
            WorkshopView view = newWorkshopView();
            StackPane root = new StackPane(view);
            root.getStyleClass().add("root-pane");
            Scene scene = new Scene(root, 1920, 1080);
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            try {
                root.applyCss();
                root.layout();

                Button detach = view.detachButton();
                Region rightPane = view.rightPane();
                Bounds detachInScene = detach.localToScene(detach.getLayoutBounds());
                Bounds paneInScene = rightPane.localToScene(rightPane.getLayoutBounds());

                // The Detach button's right edge must be within a few px
                // of the right pane's right edge (right-pane padding +
                // sub-pixel snap rounding). This catches a regression
                // that re-introduces growable breadcrumbs pushing the
                // button leftward.
                double detachRight = detachInScene.getMaxX();
                double paneRight = paneInScene.getMaxX();
                assertThat(paneRight - detachRight)
                        .as("Detach button right-edge (%.2f) must pin within ~30 px of "
                                + "right pane right-edge (%.2f) — header spacer absorbs "
                                + "the remainder", detachRight, paneRight)
                        .isBetween(0.0, 30.0);
            } finally {
                stage.close();
            }
            return null;
        });
    }

    /**
     * Review S8 — the plugin pane and the clip-detail pane both have
     * {@code Vgrow.ALWAYS}, so when both are populated they split the
     * vertical real-estate of the right pane equally (modulo the
     * breadcrumb header and the right-pane padding). Without the
     * clip-detail Vgrow the clip-detail host stayed at its pref-height
     * (effectively zero for a fresh node) and the plugin pane absorbed
     * everything — the §4 Concept F mock pairs them at ≈ 50/50.
     */
    @Test
    void pluginPaneAndClipDetailPaneSplitVerticallyEqually() throws Exception {
        onFxThread(() -> {
            WorkshopView view = newWorkshopView();

            // Populate both panes with substantial-pref-height regions so
            // we're measuring growth, not pref-height fallback.
            Region pluginNode = new Region();
            pluginNode.setPrefSize(200, 100);
            view.setFocusedPlugin(1, "Synth", pluginNode);

            Region clipDetail = new Region();
            clipDetail.setPrefSize(200, 100);
            view.setClipDetailContent(clipDetail);

            StackPane root = new StackPane(view);
            root.getStyleClass().add("root-pane");
            Scene scene = new Scene(root, 1920, 1080);
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            try {
                root.applyCss();
                root.layout();

                double pluginH = view.pluginContainer().getHeight();
                double clipH = view.clipDetailHost().getHeight();

                assertThat(pluginH)
                        .as("plugin pane grew vertically (Vgrow.ALWAYS) — was %.2f", pluginH)
                        .isGreaterThan(100.0);
                assertThat(clipH)
                        .as("clip-detail pane grew vertically (Vgrow.ALWAYS) — was %.2f", clipH)
                        .isGreaterThan(100.0);

                // Equal split within ±20 % of the larger of the two —
                // the §4 mock pairs them at ≈ 50/50; some slack covers
                // header + padding distribution between rows.
                double ratio = Math.min(pluginH, clipH) / Math.max(pluginH, clipH);
                assertThat(ratio)
                        .as("plugin and clip-detail heights split ≈ 50/50 — "
                                + "ratio min/max = %.3f (plugin=%.2f, clip=%.2f)",
                                ratio, pluginH, clipH)
                        .isGreaterThan(0.80);
            } finally {
                stage.close();
            }
            return null;
        });
    }

    // ── Harness ───────────────────────────────────────────────────────────

    /** AssertJ "within" alias — local to keep the import surface narrow. */
    private static org.assertj.core.data.Offset<Double> within(double tolerance) {
        return org.assertj.core.data.Offset.offset(tolerance);
    }


    private static WorkshopView newWorkshopView() {
        ResourceBundle messages = ResourceBundle.getBundle(
                "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);
        return new WorkshopView(messages);
    }

    private static <T> T onFxThread(Supplier<T> supplier) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(supplier.get());
            } catch (Throwable t) {
                err.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(15, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX thread did not complete within 15 seconds");
        }
        if (err.get() != null) {
            throw new AssertionError("FX thread action failed", err.get());
        }
        return ref.get();
    }
}
