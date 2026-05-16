package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.controls.skin.KnobSkin;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reduce Motion (story 279): with {@link Knob#isAnimated()} {@code false},
 * the {@code 0} / Ctrl+click reset snaps without instantiating a
 * transition timeline.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class KnobReducedMotionTest {

    @Test
    void resetWithAnimatedFalseSnapsAndCreatesNoTimeline() {
        boolean[] running = new boolean[1];
        double[] finalValue = new double[1];
        runOnFxThread(() -> {
            Knob k = Knob.create()
                    .min(-1.0).max(1.0).defaultValue(0.0)
                    .animated(false).build();
            k.setValue(0.75);
            StackPane root = new StackPane(k);
            new Scene(root, 100, 100);
            root.applyCss();
            root.layout();
            KnobSkin skin = (KnobSkin) k.getSkin();
            skin.resetToDefault();
            running[0] = skin.isDetentAnimationRunning();
            finalValue[0] = k.getValue();
            return null;
        });
        assertThat(running[0])
                .as("no detent animation should be running with animated=false")
                .isFalse();
        assertThat(finalValue[0])
                .as("value should snap to default immediately")
                .isEqualTo(0.0);
    }

    @Test
    void resetWithAnimatedTrueStartsAnimationAndStillSnapsModelImmediately() {
        boolean[] running = new boolean[1];
        double[] finalValue = new double[1];
        runOnFxThread(() -> {
            Knob k = Knob.create()
                    .min(-1.0).max(1.0).defaultValue(0.0)
                    .animated(true).build();
            k.setValue(0.75);
            StackPane root = new StackPane(k);
            new Scene(root, 100, 100);
            root.applyCss();
            root.layout();
            KnobSkin skin = (KnobSkin) k.getSkin();
            skin.resetToDefault();
            running[0] = skin.isDetentAnimationRunning();
            finalValue[0] = k.getValue();
            return null;
        });
        assertThat(running[0])
                .as("detent timeline should be running with animated=true")
                .isTrue();
        assertThat(finalValue[0])
                .as("model value still snaps immediately; only the visual animates")
                .isEqualTo(0.0);
    }
}
