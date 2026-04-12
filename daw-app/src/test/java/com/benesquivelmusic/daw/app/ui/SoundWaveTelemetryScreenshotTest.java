package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.telemetry.RoomConfiguration;
import com.benesquivelmusic.daw.core.telemetry.SoundWaveTelemetryEngine;
import com.benesquivelmusic.daw.sdk.telemetry.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Captures high-definition screenshots of the SoundWaveTelemetry plugin
 * in both setup state and display state.
 *
 * <p>Screenshots are saved to {@code docs/screenshots/} in the project root.</p>
 *
 * <p>Requires a display (or {@code xvfb-run}) to render JavaFX UI.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class SoundWaveTelemetryScreenshotTest {

    // High-definition screenshot dimensions (1920×1080)
    private static final int WIDTH  = 1920;
    private static final int HEIGHT = 1080;

    private static final Path SCREENSHOTS_DIR = Paths.get(
            System.getProperty("user.dir"), "..", "docs", "screenshots").normalize();

    // ── Helpers ──────────────────────────────────────────────────────

    private TelemetryView createOnFxThread() throws Exception {
        AtomicReference<TelemetryView> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(new TelemetryView());
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        return ref.get();
    }

    private void runOnFxThread(Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * Renders a {@link Region} into a {@link WritableImage} at the given
     * dimensions using JavaFX's scene snapshot mechanism.
     */
    private WritableImage snapshotOnFxThread(Region region, int w, int h) throws Exception {
        AtomicReference<WritableImage> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                region.setPrefSize(w, h);
                region.setMinSize(w, h);
                region.setMaxSize(w, h);

                Scene scene = new Scene(region, w, h, Color.BLACK);
                scene.getStylesheets().add(DarkThemeHelper.getStylesheetUrl());

                // Layout pass to ensure all nodes are sized and positioned
                region.applyCss();
                region.layout();

                WritableImage image = scene.snapshot(null);
                ref.set(image);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
        return ref.get();
    }

    /**
     * Converts a JavaFX {@link WritableImage} to a {@link BufferedImage} and
     * saves it as a PNG file without requiring the {@code javafx.swing} module.
     */
    private static void saveAsPng(WritableImage fxImage, Path dest) throws Exception {
        int w = (int) fxImage.getWidth();
        int h = (int) fxImage.getHeight();
        javafx.scene.image.PixelReader pr = fxImage.getPixelReader();

        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                bi.setRGB(x, y, pr.getArgb(x, y));
            }
        }

        dest.getParent().toFile().mkdirs();
        ImageIO.write(bi, "png", dest.toFile());
    }

    // ── Screenshot tests ─────────────────────────────────────────────

    /**
     * Captures the SoundWaveTelemetry plugin in <em>setup state</em>.
     *
     * <p>Shows the room configuration form with default STUDIO preset
     * values pre-populated (width, length, height, wall material, empty
     * source/mic lists).</p>
     */
    @Test
    void screenshotSetupState() throws Exception {
        TelemetryView view = createOnFxThread();

        WritableImage image = snapshotOnFxThread(view, WIDTH, HEIGHT);

        Path dest = SCREENSHOTS_DIR.resolve("sound-wave-telemetry-setup.png");
        saveAsPng(image, dest);

        assertThat(dest.toFile()).exists().isFile();
        System.out.println("[Screenshot] Setup state saved to: " + dest.toAbsolutePath());
    }

    /**
     * Captures the SoundWaveTelemetry plugin in <em>display state</em>.
     *
     * <p>A concert-hall room (20 m × 15 m × 6 m, CONCRETE walls) is
     * configured with two sound sources and two microphones.
     * {@link SoundWaveTelemetryEngine#compute} is called to generate full
     * telemetry, and the view transitions to the animated display canvas.</p>
     */
    @Test
    void screenshotDisplayState() throws Exception {
        TelemetryView view = createOnFxThread();

        runOnFxThread(() -> {
            // Build a realistic room: concert-hall style
            RoomDimensions dims = new RoomDimensions(20.0, 15.0, 6.0);
            RoomConfiguration config = new RoomConfiguration(dims, WallMaterial.CONCRETE);

            // Two sound sources (front-of-stage speakers)
            config.addSoundSource(new SoundSource("Left Speaker",
                    new Position3D(4.0, 2.0, 3.5), 90.0));
            config.addSoundSource(new SoundSource("Right Speaker",
                    new Position3D(16.0, 2.0, 3.5), 90.0));

            // Two microphones (front-of-house position)
            config.addMicrophone(new MicrophonePlacement("FOH Mic L",
                    new Position3D(6.0, 10.0, 1.5), 0.0, 0.0));
            config.addMicrophone(new MicrophonePlacement("FOH Mic R",
                    new Position3D(14.0, 10.0, 1.5), 0.0, 0.0));

            // Three audience members spread across the room
            config.addAudienceMember(new AudienceMember("Row A Center",
                    new Position3D(10.0, 5.0, 0.0)));
            config.addAudienceMember(new AudienceMember("Row B Left",
                    new Position3D(5.0, 8.0, 0.0)));
            config.addAudienceMember(new AudienceMember("Row B Right",
                    new Position3D(15.0, 8.0, 0.0)));

            RoomTelemetryData data = SoundWaveTelemetryEngine.compute(config);
            view.setTelemetryData(data);
        });

        WritableImage image = snapshotOnFxThread(view, WIDTH, HEIGHT);

        Path dest = SCREENSHOTS_DIR.resolve("sound-wave-telemetry-display.png");
        saveAsPng(image, dest);

        assertThat(dest.toFile()).exists().isFile();
        System.out.println("[Screenshot] Display state saved to: " + dest.toAbsolutePath());
    }

    /**
     * Captures the SoundWaveTelemetry plugin in <em>display state</em> with a
     * compact studio room configuration (wood panels, one source, one mic).
     *
     * <p>Demonstrates the visualization with a simpler, less-reverberant room.</p>
     */
    @Test
    void screenshotDisplayStateStudio() throws Exception {
        TelemetryView view = createOnFxThread();

        runOnFxThread(() -> {
            // Build a small recording studio
            RoomDimensions dims = new RoomDimensions(8.0, 6.0, 2.8);
            RoomConfiguration config = new RoomConfiguration(dims, WallMaterial.WOOD);

            config.addSoundSource(new SoundSource("Studio Monitor",
                    new Position3D(4.0, 1.2, 1.4), 85.0));

            config.addMicrophone(new MicrophonePlacement("Recording Mic",
                    new Position3D(4.0, 3.5, 1.4), 0.0, 0.0));

            RoomTelemetryData data = SoundWaveTelemetryEngine.compute(config);
            view.setTelemetryData(data);
        });

        WritableImage image = snapshotOnFxThread(view, WIDTH, HEIGHT);

        Path dest = SCREENSHOTS_DIR.resolve("sound-wave-telemetry-display-studio.png");
        saveAsPng(image, dest);

        assertThat(dest.toFile()).exists().isFile();
        System.out.println("[Screenshot] Studio display state saved to: " + dest.toAbsolutePath());
    }
}
