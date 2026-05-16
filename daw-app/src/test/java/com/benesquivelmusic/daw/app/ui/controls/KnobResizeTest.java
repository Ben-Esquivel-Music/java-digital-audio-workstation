package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.controls.skin.KnobSkin;

import javafx.scene.canvas.Canvas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resize behaviour: {@link KnobSkin} derives every internal dimension
 * from {@code size = Math.min(w, h)} so the knob remains circular and
 * proportioned at any cell size, not just the CSS default.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class KnobResizeTest {

    @Test
    void canvasIsAlwaysSquareInsideNonSquareCell() {
        double[] dims = new double[2];
        runOnFxThread(() -> {
            Knob k = Knob.create().size(28).build();
            // Force a non-square host size — 60 × 40. The logical dial
            // sizes to min(w, h) = 40; the canvas is slightly larger to
            // include the focus-ring band.
            javafx.scene.layout.Pane root = new javafx.scene.layout.Pane(k);
            new javafx.scene.Scene(root, 80, 60);
            root.applyCss();
            k.resizeRelocate(0, 0, 60, 40);
            k.layout();
            KnobSkin skin = (KnobSkin) k.getSkin();
            Canvas c = skin.canvas();
            dims[0] = c.getWidth();
            dims[1] = c.getHeight();
            return null;
        });
        // Canvas must be square.
        assertThat(dims[0])
                .as("canvas height equals canvas width — knob is square")
                .isEqualTo(dims[1]);
        // Canvas must be >= min(cell w, cell h) = 40 (larger because of
        // the focus-ring band).
        assertThat(dims[0])
                .as("canvas width should be at least the logical dial size 40")
                .isGreaterThanOrEqualTo(40.0);
    }

    @Test
    void canvasShrinksProportionallyWhenCellShrinks() {
        double[] dims = new double[2];
        runOnFxThread(() -> {
            Knob k = Knob.create().size(48).build();
            javafx.scene.layout.Pane root = new javafx.scene.layout.Pane(k);
            new javafx.scene.Scene(root, 40, 40);
            root.applyCss();
            k.resizeRelocate(0, 0, 20, 30);
            k.layout();
            KnobSkin skin = (KnobSkin) k.getSkin();
            Canvas c = skin.canvas();
            dims[0] = c.getWidth();
            dims[1] = c.getHeight();
            return null;
        });
        // min(20, 30) = 20 — the dial honours the smaller axis.
        // Canvas is larger than 20 due to the focus-ring band.
        assertThat(dims[0]).isGreaterThanOrEqualTo(20.0);
        assertThat(dims[1]).isEqualTo(dims[0]);
    }
}
