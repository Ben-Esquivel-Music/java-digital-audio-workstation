package com.benesquivelmusic.daw.app.ui.theme;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Manual screenshot harness — generates PNGs of the {@link ThemePickerDialog}
 * for documentation and visual review. Disabled by default; enable with
 * {@code -Dtheme.screenshots=true}.
 */
@ExtendWith(JavaFxToolkitExtension.class)
@EnabledIfSystemProperty(named = "theme.screenshots", matches = "true")
class ThemePickerScreenshotIT {

    @Test
    void captureScreenshots() throws Exception {
        Path tmp = Files.createTempDirectory("theme-shot-user");
        Path outDir = Path.of(System.getProperty("theme.screenshots.dir",
                System.getProperty("java.io.tmpdir")));
        Files.createDirectories(outDir);

        ThemeRegistry registry = new ThemeRegistry(tmp);

        String[] ids = { "dark-accessible", "high-contrast", "light-accessible" };
        for (String id : ids) {
            CountDownLatch shown = new CountDownLatch(1);
            CountDownLatch shot = new CountDownLatch(1);
            AtomicReference<Exception> error = new AtomicReference<>();
            final ThemePickerDialog[] holder = new ThemePickerDialog[1];
            final Stage[] hostHolder = new Stage[1];
            Platform.runLater(() -> {
                try {
                    Stage host = new Stage();
                    host.setScene(new Scene(new StackPane(), 1, 1));
                    host.show();
                    hostHolder[0] = host;
                    holder[0] = new ThemePickerDialog(registry, id);
                    holder[0].show();
                } catch (Exception e) {
                    error.set(e);
                } finally {
                    shown.countDown();
                }
            });
            assertThat(shown.await(5, TimeUnit.SECONDS))
                    .as("Timed out waiting for dialog to show for theme: %s", id)
                    .isTrue();
            if (error.get() != null) {
                throw error.get();
            }
            // Allow layout to settle.
            Thread.sleep(400);
            Platform.runLater(() -> {
                try {
                    WritableImage img = holder[0].getDialogPane().snapshot(null, null);
                    File out = outDir.resolve("theme-picker-" + id + ".png").toFile();
                    ImageIO.write(toBuffered(img), "png", out);
                } catch (Exception e) {
                    error.set(e);
                } finally {
                    holder[0].close();
                    if (hostHolder[0] != null) {
                        hostHolder[0].close();
                    }
                    shot.countDown();
                }
            });
            assertThat(shot.await(5, TimeUnit.SECONDS))
                    .as("Timed out waiting for screenshot of theme: %s", id)
                    .isTrue();
            if (error.get() != null) {
                throw error.get();
            }
        }
    }

    private static BufferedImage toBuffered(WritableImage img) {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader pr = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, pr.getArgb(x, y));
            }
        }
        return out;
    }
}
