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
 * Smoke tests for {@link TrackLaneRenderer}. Verifies that lane
 * backgrounds, separators and the empty-area fill render without error
 * for empty, single-track, and multi-track inputs.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class TrackLaneRendererTest {

    @Test
    void shouldRenderEmptyTrackList() throws Exception {
        runOnFxThread(gc -> TrackLaneRenderer.draw(gc, 0,
                new double[0], 80.0, new double[0], 400, 200, 0));
    }

    @Test
    void shouldRenderAlternatingLanes() throws Exception {
        runOnFxThread(gc -> {
            double trackHeight = 80.0;
            double[] laneY = {0.0, 80.0, 160.0};
            double[] eff = {80.0, 80.0, 80.0};
            TrackLaneRenderer.draw(gc, 3, laneY, trackHeight, eff, 400, 400, 240);
        });
    }

    @Test
    void shouldSkipLanesFullyAboveViewport() throws Exception {
        runOnFxThread(gc -> {
            double trackHeight = 80.0;
            double[] laneY = {-200.0, -120.0, -40.0};
            double[] eff = {80.0, 80.0, 80.0};
            TrackLaneRenderer.draw(gc, 3, laneY, trackHeight, eff, 400, 400, -40);
        });
    }

    @Test
    void shouldFillEmptyAreaBelowLastTrack() throws Exception {
        runOnFxThread(gc -> {
            double[] laneY = {0.0};
            double[] eff = {80.0};
            TrackLaneRenderer.draw(gc, 1, laneY, 80.0, eff, 400, 400, 80);
        });
    }

    private static void runOnFxThread(java.util.function.Consumer<GraphicsContext> action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Canvas canvas = new Canvas(400, 400);
                action.accept(canvas.getGraphicsContext2D());
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    }
}
