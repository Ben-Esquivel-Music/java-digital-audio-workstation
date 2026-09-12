package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.core.plugin.TunerPlugin.TuningResult;
import com.benesquivelmusic.daw.sdk.visualization.*;
import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.image.PixelFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
class LiveAnalyzerIdleDisplayTest {
    @Test
    void allDockAnalyzersRestoreStationaryHonestIdlePixels() throws Exception {
        onFx(() -> {
            var spectrum = new SpectrumDisplay();
            float[] bins = new float[2048];
            java.util.Arrays.fill(bins, -24);
            check(spectrum, () -> spectrum.updateSpectrum(new SpectrumData(bins, 4096, 48_000)),
                    () -> spectrum.updateSpectrum(null));
            var wave = new WaveformDisplay();
            check(wave, () -> wave.setWaveformData(new WaveformData(new float[]{-.5f, -.8f},
                    new float[]{.5f, .8f}, new float[]{.3f, .5f}, 2)), () -> wave.setWaveformData(null));
            var correlation = new CorrelationDisplay();
            assertThat(correlation.getAccessibleText()).isEqualTo("No signal").doesNotContain("1.00");
            check(correlation, () -> correlation.update(new CorrelationData(1, -12, -120, 0)),
                    () -> correlation.update(null));
            assertThat(correlation.getAccessibleText()).isEqualTo("No signal");
            var loudness = new LoudnessDisplay();
            check(loudness, () -> loudness.update(new LoudnessData(-18, -19, -20, 4, -2)),
                    () -> loudness.update(null));
            assertThat(loudness.getAccessibleText()).isEqualTo("M: --- S: --- I: ---");
            var tuner = new TunerDisplay();
            check(tuner, () -> tuner.update(new TuningResult("A", 4, 440, 0, true)),
                    () -> tuner.update(null));
            assertThat(tuner.getAccessibleText()).isEqualTo("No signal");
            var levels = new LevelMeterDisplay();
            check(levels, () -> levels.update(-6, -12, false), () -> levels.update(-120, -120, false));
        });
    }

    private static void check(GpuCanvasView display, Runnable signal, Runnable silence) {
        try {
            var root = new Group(display);
            root.setAutoSizeChildren(false);
            new Scene(root, 480, 300);
            display.setPrefSize(480, 300);
            display.resize(480, 300);
            display.applyCss();
            display.layout();
            int[] initial = pixels(display);
            signal.run();
            assertThat(java.util.Arrays.equals(initial, pixels(display))).as(display.getClass().getSimpleName() + " renders live data").isFalse();
            silence.run();
            if (display instanceof LevelMeterDisplay) {
                for (int frame = 0; frame < 300; frame++) display.gpuCanvas().requestRender();
            }
            int[] idle = pixels(display);
            assertThat(java.util.Arrays.equals(idle, initial)).as(display.getClass().getSimpleName() + " restores its initial no-signal pixels").isTrue();
            assertThat(java.util.Arrays.equals(pixels(display), idle)).as("idle does not move on a later render").isTrue();
        } finally { display.dispose(); }
    }

    private static int[] pixels(GpuCanvasView display) {
        display.gpuCanvas().requestRender();
        var image = display.snapshot(null, null);
        assertThat(image.getWidth()).isEqualTo(480);
        assertThat(image.getHeight()).isEqualTo(300);
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        int[] pixels = new int[width * height];
        image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
        return pixels;
    }

    private static void onFx(Runnable action) throws Exception {
        var done = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        Platform.runLater(() -> {
            try { action.run(); } catch (Throwable error) { failure.set(error); } finally { done.countDown(); }
        });
        assertThat(done.await(15, TimeUnit.SECONDS)).isTrue();
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
}
