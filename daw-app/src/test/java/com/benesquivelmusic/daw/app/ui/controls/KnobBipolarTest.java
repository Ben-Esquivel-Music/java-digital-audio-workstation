package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.controls.skin.KnobSkin;

import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the §5.8 bipolar travel-arc contract: with
 * {@code bipolar = true} and {@code value < defaultValue}, the accent
 * overlay renders from the centre detent (12 o'clock) counter-clockwise
 * — and the symmetric pixel on the clockwise side is NOT accent.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class KnobBipolarTest {

    /** Accent token value from knob.css / Palette A. */
    private static final Color ACCENT = Color.web("#7C8CFF");

    @Test
    void bipolarArcRendersFromCentreCounterClockwiseWhenValueIsNegative() {
        WritableImage img = runOnFxThread(() -> {
            Knob k = Knob.create()
                    .min(-1.0).max(1.0).defaultValue(0.0)
                    .bipolar(true).size(48).build();
            k.setValue(-0.5);
            StackPane root = new StackPane(k);
            root.setStyle("-fx-background-color: black;");
            Scene scene = new Scene(root, 96, 96);
            root.applyCss();
            root.layout();
            KnobSkin skin = (KnobSkin) k.getSkin();
            // Snapshot the canvas directly so we read exactly the pixels
            // the skin painted (no scene-graph effects to interfere).
            return skin.canvas().snapshot(new SnapshotParameters(), null);
        });

        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        PixelReader pr = img.getPixelReader();

        // The dial centre.
        int cx = w / 2;
        int cy = h / 2;
        // Pick two pixels roughly on the outer arc — one CCW (left) of
        // 12 o'clock (this side should be ACCENT for value=-0.5) and one
        // CW (right) (this side should NOT be ACCENT — only the track
        // underlay shows there).
        // Outer-arc radius is ~ size/2 - arcStroke ~ 46/2 - 2.5 ~ 20.
        int r = Math.max(2, Math.min(w, h) / 2 - 4);
        // Counter-clockwise: about 11 o'clock — angle 120° from +x axis.
        double angCcwRad = Math.toRadians(120);
        int xCcw = cx + (int) Math.round(Math.cos(angCcwRad) * r);
        int yCcw = cy - (int) Math.round(Math.sin(angCcwRad) * r);
        // Clockwise: about 1 o'clock — angle 60°.
        double angCwRad = Math.toRadians(60);
        int xCw = cx + (int) Math.round(Math.cos(angCwRad) * r);
        int yCw = cy - (int) Math.round(Math.sin(angCwRad) * r);

        Color ccwPx = findNearestAccent(pr, xCcw, yCcw, w, h);
        Color cwPx = findNearestAccent(pr, xCw, yCw, w, h);

        // The accent arc is present on the CCW side …
        assertThat(isAccent(ccwPx))
                .as("CCW pixel near 11 o'clock should be the accent arc: %s", ccwPx)
                .isTrue();
        // … and absent on the CW side (only the track underlay there).
        assertThat(isAccent(cwPx))
                .as("CW pixel near 1 o'clock should NOT be accent: %s", cwPx)
                .isFalse();
    }

    /** Sample a 5×5 window for the closest accent-ish pixel. */
    private static Color findNearestAccent(PixelReader pr, int x, int y,
            int w, int h) {
        Color best = pr.getColor(clamp(x, 0, w - 1), clamp(y, 0, h - 1));
        double bestDist = colorDist(best, ACCENT);
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int xx = clamp(x + dx, 0, w - 1);
                int yy = clamp(y + dy, 0, h - 1);
                Color c = pr.getColor(xx, yy);
                double d = colorDist(c, ACCENT);
                if (d < bestDist) {
                    bestDist = d;
                    best = c;
                }
            }
        }
        return best;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static boolean isAccent(Color c) {
        return colorDist(c, ACCENT) < 0.20;
    }

    private static double colorDist(Color a, Color b) {
        double dr = a.getRed() - b.getRed();
        double dg = a.getGreen() - b.getGreen();
        double db = a.getBlue() - b.getBlue();
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }
}
