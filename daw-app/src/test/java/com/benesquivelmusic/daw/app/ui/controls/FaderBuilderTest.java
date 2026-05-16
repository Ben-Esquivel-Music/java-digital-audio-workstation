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
 * The fluent {@link Fader.Builder} and the no-arg constructor are two
 * equivalent, independently usable construction paths.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class FaderBuilderTest {

    @Test
    void builderProducesAFaderWithEveryPropertyMirrored() {
        Function<Double, String> fmt =
                v -> String.format(Locale.ROOT, "%.2f", v);
        Fader f = runOnFxThread(() -> Fader.create()
                .min(-96).max(12).defaultValue(0).value(-6)
                .curve(Fader.TravelCurve.LOG_DB)
                .showMeter(true).animated(false)
                .unit("dB").size("mixer")
                .valueFormatter(fmt)
                .build());

        assertThat(f.getMin()).isEqualTo(-96.0);
        assertThat(f.getMax()).isEqualTo(12.0);
        assertThat(f.getDefaultValue()).isEqualTo(0.0);
        assertThat(f.getValue()).isEqualTo(-6.0);
        assertThat(f.getCurve()).isEqualTo(Fader.TravelCurve.LOG_DB);
        assertThat(f.isShowMeter()).isTrue();
        assertThat(f.isAnimated()).isFalse();
        assertThat(f.getUnit()).isEqualTo("dB");
        assertThat(f.getValueFormatter()).isSameAs(fmt);
        assertThat(f.getStyleClass()).contains("fader", "size-mixer");
    }

    @Test
    void builderDefaultsValueToDefaultValueWhenNotSpecified() {
        Fader f = runOnFxThread(() -> Fader.create()
                .min(-10).max(10).defaultValue(3).build());
        assertThat(f.getValue()).isEqualTo(3.0);
    }

    @Test
    void builderRejectsUnsupportedSize() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> Fader.create().size("micro"));
    }

    @Test
    void builderRejectsInvertedRange() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> Fader.create()
                        .min(10).max(-10).defaultValue(0).build());
    }

    @Test
    void noArgConstructorAndBuilderAreIndependent() {
        Fader built = runOnFxThread(() -> Fader.create()
                .min(-1).max(1).defaultValue(0).build());
        Fader manual = runOnFxThread(() -> {
            Fader x = new Fader();
            x.setMin(-1);
            x.setMax(1);
            return x;
        });
        assertThat(built.getMin()).isEqualTo(manual.getMin());
        assertThat(built.getMax()).isEqualTo(manual.getMax());
        runOnFxThread(() -> { manual.setValue(0.5); return null; });
        assertThat(built.getValue())
                .as("mutating one fader must not affect the other")
                .isEqualTo(0.0);
    }

    @Test
    void getUserAgentStylesheetReturnsTheFaderCss() {
        Fader f = runOnFxThread(Fader::new);
        assertThat(f.getUserAgentStylesheet()).endsWith("fader.css");
    }

    @Test
    void defaultBuilderUsesStandardMixerRange() {
        Fader f = runOnFxThread(() -> Fader.create().build());
        assertThat(f.getMin()).isEqualTo(-96.0);
        assertThat(f.getMax()).isEqualTo(12.0);
        assertThat(f.getCurve()).isEqualTo(Fader.TravelCurve.LOG_DB);
    }
}
