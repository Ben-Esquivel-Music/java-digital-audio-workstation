package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Locale;
import java.util.function.Function;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The fluent {@link Knob.Builder} and the no-arg constructor are two
 * equivalent, independently usable construction paths.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class KnobBuilderTest {

    @Test
    void builderProducesAKnobWithEveryPropertyMirrored() {
        Function<Double, String> fmt =
                v -> String.format(Locale.ROOT, "%.2f%%", v * 100);
        Knob k = runOnFxThread(() -> Knob.create()
                .min(-1.0).max(1.0).defaultValue(0.0).value(-0.5)
                .bipolar(true).unit("L/R").size(28).animated(false)
                .valueFormatter(fmt)
                .build());

        assertThat(k.getMin()).isEqualTo(-1.0);
        assertThat(k.getMax()).isEqualTo(1.0);
        assertThat(k.getDefaultValue()).isEqualTo(0.0);
        assertThat(k.getValue()).isEqualTo(-0.5);
        assertThat(k.isBipolar()).isTrue();
        assertThat(k.getUnit()).isEqualTo("L/R");
        assertThat(k.isAnimated()).isFalse();
        assertThat(k.getValueFormatter()).isSameAs(fmt);
        assertThat(k.getStyleClass()).contains("knob", "size-28");
    }

    @Test
    void builderDefaultsValueToDefaultValueWhenNotSpecified() {
        Knob k = runOnFxThread(() -> Knob.create()
                .min(0).max(10).defaultValue(7).build());
        assertThat(k.getValue()).isEqualTo(7);
    }

    @Test
    void builderRejectsUnsupportedSize() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> Knob.create().size(40));
    }

    @Test
    void noArgConstructorAndBuilderAreIndependent() {
        Knob built = runOnFxThread(() -> Knob.create().min(-1).max(1).build());
        Knob manual = runOnFxThread(() -> {
            Knob x = new Knob();
            x.setMin(-1);
            x.setMax(1);
            return x;
        });
        assertThat(built.getMin()).isEqualTo(manual.getMin());
        assertThat(built.getMax()).isEqualTo(manual.getMax());
        runOnFxThread(() -> { manual.setValue(0.5); return null; });
        assertThat(built.getValue())
                .as("mutating one knob must not affect the other")
                .isEqualTo(0.0);
    }

    @Test
    void getUserAgentStylesheetReturnsTheKnobCss() {
        Knob k = runOnFxThread(Knob::new);
        assertThat(k.getUserAgentStylesheet()).endsWith("knob.css");
    }
}
