package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.controls.skin.KnobSkin;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Verifies the §5.8 bipolar travel-arc contract via the deterministic
 * {@link KnobSkin#accentArcGeometry()} seam — no fragile pixel sampling.
 *
 * <p>With {@code bipolar = true} and {@code value < defaultValue}, the
 * accent arc must start at the 12 o'clock detent (centre of the sweep)
 * and extend counter-clockwise (positive extent in JavaFX arc convention).
 */
@ExtendWith(JavaFxToolkitExtension.class)
class KnobBipolarTest {

    @Test
    void bipolarArcStartsAtCentreAndExtendsCounterClockwiseForNegativeValue() {
        double[][] result = new double[1][];
        runOnFxThread(() -> {
            Knob k = Knob.create()
                    .min(-1.0).max(1.0).defaultValue(0.0)
                    .bipolar(true).size(48).build();
            k.setValue(-0.5);
            StackPane root = new StackPane(k);
            new Scene(root, 96, 96);
            root.applyCss();
            root.layout();
            KnobSkin skin = (KnobSkin) k.getSkin();
            result[0] = skin.accentArcGeometry();
            return null;
        });

        double startAngle = result[0][0];
        double extent = result[0][1];

        // Centre detent at 12 o'clock: START_DEG (225) - 270 * 0.5 = 90°.
        assertThat(startAngle).isCloseTo(90.0, within(0.01));
        // value = -0.5 → normalised = 0.25, extent = -270 * (0.25 - 0.5) = +67.5°.
        // Positive extent = counter-clockwise from centre — correct for a
        // negative-value bipolar knob (arc goes left of 12 o'clock).
        assertThat(extent).isCloseTo(67.5, within(0.01));
        assertThat(extent).isGreaterThan(0.0);
    }

    @Test
    void bipolarArcExtendsClockwiseForPositiveValue() {
        double[][] result = new double[1][];
        runOnFxThread(() -> {
            Knob k = Knob.create()
                    .min(-1.0).max(1.0).defaultValue(0.0)
                    .bipolar(true).size(48).build();
            k.setValue(0.5);
            StackPane root = new StackPane(k);
            new Scene(root, 96, 96);
            root.applyCss();
            root.layout();
            KnobSkin skin = (KnobSkin) k.getSkin();
            result[0] = skin.accentArcGeometry();
            return null;
        });

        double startAngle = result[0][0];
        double extent = result[0][1];

        assertThat(startAngle).isCloseTo(90.0, within(0.01));
        // value = 0.5 → normalised = 0.75, extent = -270 * (0.75 - 0.5) = -67.5°.
        // Negative extent = clockwise from centre.
        assertThat(extent).isCloseTo(-67.5, within(0.01));
        assertThat(extent).isLessThan(0.0);
    }

    @Test
    void unipolarArcStartsAtSweepOriginAndExtendsClockwise() {
        double[][] result = new double[1][];
        runOnFxThread(() -> {
            Knob k = Knob.create()
                    .min(0).max(1).defaultValue(0).build();
            k.setValue(0.5);
            StackPane root = new StackPane(k);
            new Scene(root, 96, 96);
            root.applyCss();
            root.layout();
            KnobSkin skin = (KnobSkin) k.getSkin();
            result[0] = skin.accentArcGeometry();
            return null;
        });

        double startAngle = result[0][0];
        double extent = result[0][1];

        // Unipolar: starts at 225° (7:30 position).
        assertThat(startAngle).isCloseTo(225.0, within(0.01));
        // value = 0.5 → normalised = 0.5, extent = -270 * 0.5 = -135°.
        assertThat(extent).isCloseTo(-135.0, within(0.01));
    }
}
