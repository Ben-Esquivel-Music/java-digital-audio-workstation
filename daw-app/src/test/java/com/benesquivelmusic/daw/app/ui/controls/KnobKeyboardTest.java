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
 * Verifies the UI Design Book §2.8 keyboard-parity contract for
 * {@link Knob}: arrow keys, fine adjust, PgUp/PgDn, Home/End, 0 → reset.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class KnobKeyboardTest {

    private static Knob attachedKnob() {
        return runOnFxThread(() -> {
            Knob k = Knob.create()
                    .min(-1.0).max(1.0).defaultValue(0.25).value(0.0)
                    .build();
            StackPane root = new StackPane(k);
            Scene scene = new Scene(root, 100, 100);
            // A Stage isn't required to dispatch synthetic key events;
            // applyCss + layout is enough to install the skin.
            root.applyCss();
            root.layout();
            k.requestFocus();
            return k;
        });
    }

    private static void fire(Knob k, KeyCode code, boolean shift) {
        runOnFxThread(() -> {
            KeyEvent ev = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
                    shift, false, false, false);
            Event.fireEvent(k, ev);
            return null;
        });
    }

    @Test
    void upArrowIncreasesByOnePercentOfRange() {
        Knob k = attachedKnob();
        // Range = 2.0, 1% = 0.02
        runOnFxThread(() -> { k.setValue(0.0); return null; });
        fire(k, KeyCode.UP, false);
        assertThat(k.getValue()).isCloseTo(0.02, within(1e-9));
    }

    @Test
    void downArrowDecreasesByOnePercentOfRange() {
        Knob k = attachedKnob();
        runOnFxThread(() -> { k.setValue(0.0); return null; });
        fire(k, KeyCode.DOWN, false);
        assertThat(k.getValue()).isCloseTo(-0.02, within(1e-9));
    }

    @Test
    void rightArrowAndLeftArrowMirrorUpAndDown() {
        Knob k = attachedKnob();
        runOnFxThread(() -> { k.setValue(0.0); return null; });
        fire(k, KeyCode.RIGHT, false);
        assertThat(k.getValue()).isCloseTo(0.02, within(1e-9));
        fire(k, KeyCode.LEFT, false);
        assertThat(k.getValue()).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void shiftArrowIsTenTimesFiner() {
        Knob k = attachedKnob();
        runOnFxThread(() -> { k.setValue(0.0); return null; });
        fire(k, KeyCode.UP, true);
        // 0.02 / 10 == 0.002
        assertThat(k.getValue()).isCloseTo(0.002, within(1e-9));
    }

    @Test
    void pageUpAndPageDownAreTenTimesCoarser() {
        Knob k = attachedKnob();
        runOnFxThread(() -> { k.setValue(0.0); return null; });
        fire(k, KeyCode.PAGE_UP, false);
        // 0.02 * 10 == 0.2
        assertThat(k.getValue()).isCloseTo(0.2, within(1e-9));
        fire(k, KeyCode.PAGE_DOWN, false);
        assertThat(k.getValue()).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void homeAndEndJumpToMinAndMax() {
        Knob k = attachedKnob();
        runOnFxThread(() -> { k.setValue(0.0); return null; });
        fire(k, KeyCode.HOME, false);
        assertThat(k.getValue()).isEqualTo(-1.0);
        fire(k, KeyCode.END, false);
        assertThat(k.getValue()).isEqualTo(1.0);
    }

    @Test
    void zeroKeyResetsToDefaultValue() {
        Knob k = attachedKnob();
        runOnFxThread(() -> { k.setValue(0.75); return null; });
        fire(k, KeyCode.DIGIT0, false);
        assertThat(k.getValue()).isEqualTo(0.25);
    }
}
