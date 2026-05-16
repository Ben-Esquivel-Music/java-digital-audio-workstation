package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Value-clamp behaviour and construction-time {@code defaultValue}
 * validation for {@link Fader} — mirrors {@link KnobValueClampTest}.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class FaderValueClampTest {

    @Test
    void valuesBelowMinAreClamped() {
        Fader f = runOnFxThread(Fader::new);
        runOnFxThread(() -> { f.setMin(-96); f.setMax(12); return null; });
        runOnFxThread(() -> { f.setValue(-200); return null; });
        assertThat(f.getValue()).isEqualTo(-96.0);
    }

    @Test
    void valuesAboveMaxAreClamped() {
        Fader f = runOnFxThread(Fader::new);
        runOnFxThread(() -> { f.setMin(-96); f.setMax(12); return null; });
        runOnFxThread(() -> { f.setValue(99); return null; });
        assertThat(f.getValue()).isEqualTo(12.0);
    }

    @Test
    void negativeInfinityClampsToMin() {
        Fader f = runOnFxThread(Fader::new);
        runOnFxThread(() -> { f.setMin(-96); f.setMax(12); return null; });
        runOnFxThread(() -> { f.setValue(Double.NEGATIVE_INFINITY); return null; });
        assertThat(f.getValue()).isEqualTo(-96.0);
    }

    @Test
    void rangeShrinkRetainsTheInvariant() {
        Fader f = runOnFxThread(Fader::new);
        runOnFxThread(() -> {
            f.setMin(-96); f.setMax(12); f.setValue(8.0); return null;
        });
        runOnFxThread(() -> { f.setMax(5.0); return null; });
        assertThat(f.getValue()).isEqualTo(5.0);
    }

    @Test
    void defaultValueOutOfRangeAtConstructionThrows() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> Fader.create()
                        .min(-10).max(0).defaultValue(5).build());
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> Fader.create()
                        .min(0).max(12).defaultValue(-5).build());
    }
}
