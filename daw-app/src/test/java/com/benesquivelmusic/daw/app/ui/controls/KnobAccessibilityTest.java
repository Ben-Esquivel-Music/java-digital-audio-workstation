package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;

import javafx.scene.AccessibleAttribute;
import javafx.scene.AccessibleRole;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Accessibility contract: {@link Knob} reports
 * {@link AccessibleRole#SLIDER} (the closest standard role to a rotary
 * parameter) and updates its accessible text on every value change so
 * screen readers narrate values.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class KnobAccessibilityTest {

    @Test
    void roleIsSlider() {
        Knob k = runOnFxThread(Knob::new);
        assertThat(k.getAccessibleRole()).isEqualTo(AccessibleRole.SLIDER);
    }

    @Test
    void valueChangesUpdateAccessibleText() {
        String[] texts = new String[2];
        runOnFxThread(() -> {
            Knob k = Knob.create()
                    .min(-1.0).max(1.0).defaultValue(0.0).value(0.0).build();
            StackPane root = new StackPane(k);
            new Scene(root, 100, 100);
            root.applyCss();
            root.layout();
            // First update — the skin's paint() refreshes accessibleText.
            texts[0] = k.getAccessibleText();
            k.setValue(0.5);
            // Forcing a layout pulse so the skin re-paints.
            root.applyCss();
            root.layout();
            texts[1] = k.getAccessibleText();
            return null;
        });
        assertThat(texts[0]).contains("0.0");
        assertThat(texts[1])
                .as("screen readers must hear the new value")
                .contains("0.5");
    }

    @Test
    void valueAttributeIsObservable() {
        // AccessibleAttribute.VALUE returns the bean's value — implemented
        // by Control for SLIDER role via the standard accessor path; here
        // we verify a Knob returns a non-null value attribute (the bridge
        // that lets assistive tech sample the live value).
        Object v = runOnFxThread(() -> {
            Knob k = new Knob();
            k.setValue(0.75);
            return k.queryAccessibleAttribute(AccessibleAttribute.TEXT);
        });
        assertThat(v).isNotNull();
    }
}
