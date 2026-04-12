package com.benesquivelmusic.daw.app.ui;

import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for {@link TransportOverlayRenderer}. Each overlay draw
 * method is exercised with both visible and off-screen inputs so the
 * early-return code paths are covered.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class TransportOverlayRendererTest {

    @Test
    void shouldDrawPlayheadInViewport() throws Exception {
        runOnFxThread(gc -> TransportOverlayRenderer.drawPlayhead(gc, 2.0, 0.0, 40.0, 400, 200));
    }

    @Test
    void shouldSkipPlayheadWhenHiddenOrOutside() throws Exception {
        runOnFxThread(gc -> {
            TransportOverlayRenderer.drawPlayhead(gc, -1.0, 0.0, 40.0, 400, 200);
            TransportOverlayRenderer.drawPlayhead(gc, 100.0, 0.0, 40.0, 400, 200);
        });
    }

    @Test
    void shouldDrawLoopHighlightWhenEnabled() throws Exception {
        runOnFxThread(gc -> {
            TransportOverlayRenderer.drawLoopHighlight(gc, true, 1.0, 5.0, 0.0, 40.0, 400, 200);
            TransportOverlayRenderer.drawLoopHighlight(gc, false, 1.0, 5.0, 0.0, 40.0, 400, 200);
        });
    }

    @Test
    void shouldDrawSelectionHighlightWithHandles() throws Exception {
        runOnFxThread(gc -> TransportOverlayRenderer.drawSelectionHighlight(
                gc, true, 1.0, 3.0, 0.0, 40.0, 400, 200));
    }

    @Test
    void shouldSkipSelectionWhenInactiveOrEmpty() throws Exception {
        runOnFxThread(gc -> {
            TransportOverlayRenderer.drawSelectionHighlight(gc, false, 0, 4, 0, 40, 400, 200);
            TransportOverlayRenderer.drawSelectionHighlight(gc, true, 4, 4, 0, 40, 400, 200);
            TransportOverlayRenderer.drawSelectionHighlight(gc, true, 5, 3, 0, 40, 400, 200);
        });
    }

    @Test
    void shouldDrawRubberBandRectangle() throws Exception {
        runOnFxThread(gc -> {
            TransportOverlayRenderer.drawRubberBand(gc, true, 10, 10, 150, 100, 400, 200);
            TransportOverlayRenderer.drawRubberBand(gc, true, 150, 100, 10, 10, 400, 200);
        });
    }

    @Test
    void shouldSkipRubberBandWhenInactive() throws Exception {
        runOnFxThread(gc -> TransportOverlayRenderer.drawRubberBand(
                gc, false, 10, 10, 150, 100, 400, 200));
    }

    private static void runOnFxThread(java.util.function.Consumer<GraphicsContext> action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Canvas canvas = new Canvas(400, 200);
                action.accept(canvas.getGraphicsContext2D());
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    }
}
