package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Value-clamp behaviour and construction-time {@code defaultValue}
 * validation for {@link Knob}.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class KnobValueClampTest {

    @Test
    void valuesBelowMinAreClamped() {
        Knob k = runOnFxThread(Knob::new);
        runOnFxThread(() -> { k.setMin(-1.0); k.setMax(1.0); return null; });
        runOnFxThread(() -> { k.setValue(-5.0); return null; });
        assertThat(k.getValue()).isEqualTo(-1.0);
    }

    @Test
    void valuesAboveMaxAreClamped() {
        Knob k = runOnFxThread(Knob::new);
        runOnFxThread(() -> { k.setMin(-1.0); k.setMax(1.0); return null; });
        runOnFxThread(() -> { k.setValue(7.5); return null; });
        assertThat(k.getValue()).isEqualTo(1.0);
    }

    @Test
    void rangeShrinkRetainsTheInvariant() {
        Knob k = runOnFxThread(Knob::new);
        runOnFxThread(() -> { k.setMin(-10.0); k.setMax(10.0); k.setValue(8.0); return null; });
        // Now tighten the range — the existing value should be re-clamped.
        runOnFxThread(() -> { k.setMax(5.0); return null; });
        assertThat(k.getValue()).isEqualTo(5.0);
    }

    @Test
    void defaultValueOutOfRangeAtConstructionThrows() {
        // Builder path — the canonical construction-time validation seam.
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> Knob.create()
                        .min(0).max(1).defaultValue(5).build());
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> Knob.create()
                        .min(0).max(1).defaultValue(-5).build());
    }

    @Test
    void defaultValueOutOfRangeViaSetterAlsoThrows() {
        Knob k = runOnFxThread(Knob::new);
        // Default range is [0,1]; 2 is outside.
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> runOnFxThread(() -> {
                    k.setDefaultValue(2.0);
                    return null;
                }));
    }
}
