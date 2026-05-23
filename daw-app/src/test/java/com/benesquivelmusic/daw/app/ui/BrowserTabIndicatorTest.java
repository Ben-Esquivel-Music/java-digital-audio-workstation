package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.BrowserPanel.BrowserSection;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * Story 275 / UI Design Book §5.5, §7.3 — the active browser tab carries
 * a DRAWN {@code -accent} 2 px under-text {@link Rectangle}, not a CSS
 * border and not a layout-shifting border swap.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class BrowserTabIndicatorTest {

    private <T> T onFx(java.util.concurrent.Callable<T> action) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Exception> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(action.call());
            } catch (Exception e) {
                err.set(e);
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
        if (err.get() != null) {
            throw err.get();
        }
        return ref.get();
    }

    @Test
    void activeTabHasAccentUnderBarPreviousDoesNot() throws Exception {
        Object[] bars = onFx(() -> {
            BrowserPanel panel = new BrowserPanel();
            StackPane root = new StackPane(panel);
            root.getStyleClass().add("root-pane");
            Scene scene = new Scene(root, 320, 600);
            ThemeManager.getDefault().applyTo(scene);
            root.applyCss();
            root.layout();
            // Force a full CSS + raster pass so scene-stylesheet rules
            // that reference .root-pane looked-up colours resolve (a
            // plain applyCss() leaves them unresolved in a fresh headless
            // scene — see memory: JavaFX headless pitfalls).
            root.snapshot(null, null);
            root.applyCss();
            root.layout();

            panel.selectSection(BrowserSection.SAMPLES);
            root.applyCss();
            root.layout();
            root.snapshot(null, null);

            // Sibling probe for the resolved -accent token value
            // (palette-swap safe — no hex literal pinned; survives the
            // story-277 theme work, unlike a hard-coded #7C8CFF).
            javafx.scene.layout.Region probe = new javafx.scene.layout.Region();
            probe.setStyle("-fx-background-color: -accent;");
            root.getChildren().add(probe);
            root.applyCss();
            root.layout();
            Color accent = (Color) probe.getBackground().getFills().getFirst().getFill();

            return new Object[] {
                    panel.getTabIndicator(BrowserSection.SAMPLES),
                    panel.getTabIndicator(BrowserSection.FILES),
                    accent
            };
        });

        Rectangle active = (Rectangle) bars[0];
        Rectangle inactive = (Rectangle) bars[1];
        Color accent = (Color) bars[2];

        // §7.3 — a drawn 2 px Rectangle, unmanaged so it never reflows.
        assertThat(active).as("active tab indicator exists").isNotNull();
        assertThat(active.getHeight()).as("indicator height is 2 px").isEqualTo(2.0);
        assertThat(active.isManaged())
                .as("indicator is unmanaged so it never perturbs layout")
                .isFalse();
        assertThat(active.isVisible())
                .as("only the active tab's indicator is visible")
                .isTrue();
        assertThat(active.getWidth())
                .as("indicator width hugs the tab label text (> 0)")
                .isGreaterThan(0.0);

        // The resolved fill must be the -accent token (resolved via the
        // sibling probe, not a pinned hex) and explicitly NOT the JavaFX
        // default Rectangle fill (BLACK) — proving the CSS cascade
        // drives the fill, not a coincidence.
        assertThat(active.getFill()).isInstanceOf(Color.class);
        Color fill = (Color) active.getFill();
        assertThat(fill.getRed()).as("indicator fill red == -accent")
                .isCloseTo(accent.getRed(), offset(0.01));
        assertThat(fill.getGreen()).as("indicator fill green == -accent")
                .isCloseTo(accent.getGreen(), offset(0.01));
        assertThat(fill.getBlue()).as("indicator fill blue == -accent")
                .isCloseTo(accent.getBlue(), offset(0.01));
        assertThat(fill)
                .as("indicator fill must NOT be the JavaFX default Rectangle BLACK")
                .isNotEqualTo(Color.BLACK);

        // The previously-active tab (FILES) no longer shows its bar.
        assertThat(inactive.isVisible())
                .as("previously active tab's indicator is hidden")
                .isFalse();
    }
}
