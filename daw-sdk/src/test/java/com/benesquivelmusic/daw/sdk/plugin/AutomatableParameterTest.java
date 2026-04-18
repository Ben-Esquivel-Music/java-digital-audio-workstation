package com.benesquivelmusic.daw.sdk.plugin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutomatableParameterTest {

    @Test
    void shouldCreateWithUnit() {
        AutomatableParameter p = new AutomatableParameter(
                1, "Threshold", -60.0, 0.0, -20.0, "dB");

        assertThat(p.id()).isEqualTo(1);
        assertThat(p.displayName()).isEqualTo("Threshold");
        assertThat(p.minValue()).isEqualTo(-60.0);
        assertThat(p.maxValue()).isEqualTo(0.0);
        assertThat(p.defaultValue()).isEqualTo(-20.0);
        assertThat(p.unit()).isEqualTo("dB");
    }

    @Test
    void shouldCreateWithoutUnit() {
        AutomatableParameter p = new AutomatableParameter(
                0, "Ratio", 1.0, 20.0, 4.0);

        assertThat(p.unit()).isEmpty();
    }

    @Test
    void shouldRejectBlankName() {
        assertThatThrownBy(() -> new AutomatableParameter(
                0, "  ", 0.0, 1.0, 0.5, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("displayName");
    }

    @Test
    void shouldRejectNullUnit() {
        assertThatThrownBy(() -> new AutomatableParameter(
                0, "Gain", 0.0, 1.0, 0.5, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectInvertedRange() {
        assertThatThrownBy(() -> new AutomatableParameter(
                0, "X", 1.0, 0.0, 0.5, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minValue");
    }

    @Test
    void shouldRejectDefaultOutsideRange() {
        assertThatThrownBy(() -> new AutomatableParameter(
                0, "X", 0.0, 1.0, 2.0, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultValue");
    }

    @Test
    void shouldConvertFromPluginParameter() {
        PluginParameter src = new PluginParameter(5, "Attack", 0.01, 100.0, 10.0);

        AutomatableParameter derived = AutomatableParameter.from(src);

        assertThat(derived.id()).isEqualTo(5);
        assertThat(derived.displayName()).isEqualTo("Attack");
        assertThat(derived.minValue()).isEqualTo(0.01);
        assertThat(derived.maxValue()).isEqualTo(100.0);
        assertThat(derived.defaultValue()).isEqualTo(10.0);
        assertThat(derived.unit()).isEmpty();
    }
}
