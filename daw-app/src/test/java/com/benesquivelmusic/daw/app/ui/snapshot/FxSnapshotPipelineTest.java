package com.benesquivelmusic.daw.app.ui.snapshot;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;

import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sanity checks for {@link FxSnapshotTest} that do <strong>not</strong>
 * involve the golden-PNG file system. They exercise the rendering
 * pipeline (FX-thread marshalling, scene composition, theme
 * application, FX-image → BufferedImage conversion) and verify that
 * the produced pixels match the expected colours.
 *
 * <p>Kept separate from view-specific snapshot tests so a broken
 * pipeline shows up here as a small, fast failure rather than as
 * cascading mismatches across every view.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class FxSnapshotPipelineTest {

    private static final class SnapshotProbe extends FxSnapshotTest {
        BufferedImage doRender(int w, int h, String themeId) {
            // Small rectangle in the centre so the corners reveal the
            // scene's theme-driven background fill.
            Rectangle r = new Rectangle(w / 4.0, h / 4.0, Color.RED);
            r.setLayoutX(w * 0.375);
            r.setLayoutY(h * 0.375);
            Pane pane = new Pane(r);
            pane.setPrefSize(w, h);
            return render(pane, w, h, themeId);
        }
    }

    @Test
    void renderProducesImageOfRequestedSize() {
        BufferedImage img = new SnapshotProbe().doRender(80, 60, null);
        assertThat(img.getWidth()).isEqualTo(80);
        assertThat(img.getHeight()).isEqualTo(60);
    }

    @Test
    void renderPaintsFillColourIntoPixels() {
        BufferedImage img = new SnapshotProbe().doRender(40, 40, null);
        // Center pixel should be solid red — covered by the centred
        // Rectangle filled with Color.RED.
        int center = img.getRGB(20, 20);
        assertThat((center >>> 16) & 0xff).isGreaterThan(200);  // R
        assertThat((center >>>  8) & 0xff).isLessThan(40);      // G
        assertThat((center       ) & 0xff).isLessThan(40);      // B
        assertThat((center >>> 24) & 0xff).isGreaterThan(200);  // alpha
    }

    @Test
    void bundledThemeBackgroundDiffersFromDefault() {
        BufferedImage dark   = new SnapshotProbe().doRender(20, 20, "dark-accessible");
        BufferedImage light  = new SnapshotProbe().doRender(20, 20, "light-accessible");
        // The themes should produce visibly different background pixels
        // at a corner (outside the centred Rectangle is filled by the
        // scene's fill colour, derived from the theme).
        assertThat(dark.getRGB(0, 0)).isNotEqualTo(light.getRGB(0, 0));
    }
}
