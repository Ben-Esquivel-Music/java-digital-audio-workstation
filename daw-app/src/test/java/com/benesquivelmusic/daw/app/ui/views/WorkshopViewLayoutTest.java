package com.benesquivelmusic.daw.app.ui.views;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.inspector.InspectorSelectionModel;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
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

    // ── Harness ───────────────────────────────────────────────────────────

    private static WorkshopView newWorkshopView() {
        ResourceBundle messages = ResourceBundle.getBundle(
                "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);
        return new WorkshopView(messages, new InspectorSelectionModel());
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
