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
 * Smoke tests for {@link ClipWaveformRenderer} — verifies that the
 * renderer handles empty, short, visible, and off-screen inputs without
 * throwing. Full-pixel waveform verification would require a pixel-reader
 * test fixture, which is out of scope for this extraction.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ClipWaveformRendererTest {

    @Test
    void shouldHandleNullAndEmptyAudioData() throws Exception {
        runOnFxThread(gc -> {
            ClipWaveformRenderer.draw(gc, null, 0, 0, 100, 60, 400);
            ClipWaveformRenderer.draw(gc, new float[0][], 0, 0, 100, 60, 400);
            ClipWaveformRenderer.draw(gc, new float[][]{new float[0]}, 0, 0, 100, 60, 400);
        });
    }

    @Test
    void shouldSkipRenderingBelowMinimumWidth() throws Exception {
        runOnFxThread(gc ->
                ClipWaveformRenderer.draw(gc, mkSine(512),
                        0, 0, 2, 60, 400));
    }

    @Test
    void shouldRenderVisibleClipWithoutError() throws Exception {
        runOnFxThread(gc ->
                ClipWaveformRenderer.draw(gc, mkSine(4096),
                        10, 10, 200, 60, 400));
    }

    @Test
    void shouldHandleOffScreenClip() throws Exception {
        runOnFxThread(gc ->
                ClipWaveformRenderer.draw(gc, mkSine(4096),
                        -300, 10, 200, 60, 400));
    }

    private static float[][] mkSine(int samples) {
        float[] data = new float[samples];
        for (int i = 0; i < samples; i++) {
            data[i] = (float) Math.sin(i * 0.01);
        }
        return new float[][]{data};
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
