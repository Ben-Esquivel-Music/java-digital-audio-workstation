package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;

import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * §2.8 keyboard-parity contract for {@link Fader} — mirrors
 * {@link KnobKeyboardTest} for fader keys (Home is "max" / up, End is
 * "min" / down).
 */
@ExtendWith(JavaFxToolkitExtension.class)
class FaderKeyboardTest {

    private static Fader attached() {
        return runOnFxThread(() -> {
            Fader f = Fader.create()
                    .min(-100).max(100).defaultValue(25).value(0)
                    .curve(Fader.TravelCurve.LINEAR)
                    .showMeter(false)
                    .build();
            StackPane root = new StackPane(f);
            new Scene(root, 60, 200);
            root.applyCss();
            root.layout();
            f.requestFocus();
            return f;
        });
    }

    private static void fire(Fader f, KeyCode code, boolean shift) {
        runOnFxThread(() -> {
            KeyEvent ev = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
                    shift, false, false, false);
            Event.fireEvent(f, ev);
            return null;
        });
    }

    @Test
    void upArrowIncreasesByOnePercentOfRange() {
        Fader f = attached();
        runOnFxThread(() -> { f.setValue(0); return null; });
        fire(f, KeyCode.UP, false);
        // Range = 200, 1% = 2.0
        assertThat(f.getValue()).isCloseTo(2.0, within(1e-9));
    }

    @Test
    void downArrowDecreasesByOnePercentOfRange() {
        Fader f = attached();
        runOnFxThread(() -> { f.setValue(0); return null; });
        fire(f, KeyCode.DOWN, false);
        assertThat(f.getValue()).isCloseTo(-2.0, within(1e-9));
    }

    @Test
    void shiftArrowIsTenTimesFiner() {
        Fader f = attached();
        runOnFxThread(() -> { f.setValue(0); return null; });
        fire(f, KeyCode.UP, true);
        assertThat(f.getValue()).isCloseTo(0.2, within(1e-9));
    }

    @Test
    void pageUpAndPageDownAreTenTimesCoarser() {
        Fader f = attached();
        runOnFxThread(() -> { f.setValue(0); return null; });
        fire(f, KeyCode.PAGE_UP, false);
        assertThat(f.getValue()).isCloseTo(20.0, within(1e-9));
        fire(f, KeyCode.PAGE_DOWN, false);
        assertThat(f.getValue()).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void homeJumpsToMaxAndEndJumpsToMin() {
        Fader f = attached();
        runOnFxThread(() -> { f.setValue(0); return null; });
        // For a fader, "up" is max, so Home → max, End → min.
        fire(f, KeyCode.HOME, false);
        assertThat(f.getValue()).isEqualTo(100.0);
        fire(f, KeyCode.END, false);
        assertThat(f.getValue()).isEqualTo(-100.0);
    }

    @Test
    void zeroKeyResetsToDefaultValue() {
        Fader f = attached();
        runOnFxThread(() -> { f.setValue(75); return null; });
        fire(f, KeyCode.DIGIT0, false);
        assertThat(f.getValue()).isEqualTo(25.0);
    }
}
