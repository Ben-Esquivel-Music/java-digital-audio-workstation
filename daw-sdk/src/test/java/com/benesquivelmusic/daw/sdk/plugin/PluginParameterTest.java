package com.benesquivelmusic.daw.sdk.plugin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginParameterTest {

    @Test
    void shouldCreateParameterWithValidArguments() {
        PluginParameter param = new PluginParameter(42, "Cutoff", 20.0, 20000.0, 1000.0);

        assertThat(param.id()).isEqualTo(42);
        assertThat(param.name()).isEqualTo("Cutoff");
        assertThat(param.minValue()).isEqualTo(20.0);
        assertThat(param.maxValue()).isEqualTo(20000.0);
        assertThat(param.defaultValue()).isEqualTo(1000.0);
    }

    @Test
    void shouldAllowEqualMinAndMax() {
        PluginParameter param = new PluginParameter(0, "Toggle", 0.0, 0.0, 0.0);
        assertThat(param.minValue()).isEqualTo(0.0);
        assertThat(param.maxValue()).isEqualTo(0.0);
    }

    @Test
    void shouldAllowDefaultAtMin() {
        PluginParameter param = new PluginParameter(1, "Level", 0.0, 1.0, 0.0);
        assertThat(param.defaultValue()).isEqualTo(0.0);
    }

    @Test
    void shouldAllowDefaultAtMax() {
        PluginParameter param = new PluginParameter(1, "Level", 0.0, 1.0, 1.0);
        assertThat(param.defaultValue()).isEqualTo(1.0);
    }

    @Test
    void shouldRejectNullName() {
        assertThatThrownBy(() -> new PluginParameter(0, null, 0.0, 1.0, 0.5))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("name");
    }

    @Test
    void shouldRejectBlankName() {
        assertThatThrownBy(() -> new PluginParameter(0, "  ", 0.0, 1.0, 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void shouldRejectMinGreaterThanMax() {
        assertThatThrownBy(() -> new PluginParameter(0, "Bad", 10.0, 5.0, 7.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minValue");
    }

    @Test
    void shouldRejectDefaultBelowMin() {
        assertThatThrownBy(() -> new PluginParameter(0, "Bad", 1.0, 10.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultValue");
    }

    @Test
    void shouldRejectDefaultAboveMax() {
        assertThatThrownBy(() -> new PluginParameter(0, "Bad", 0.0, 1.0, 2.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultValue");
    }

    @Test
    void shouldImplementEqualsAndHashCode() {
        PluginParameter a = new PluginParameter(1, "Gain", 0.0, 1.0, 0.5);
        PluginParameter b = new PluginParameter(1, "Gain", 0.0, 1.0, 0.5);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void shouldImplementToString() {
        PluginParameter param = new PluginParameter(7, "Resonance", 0.0, 100.0, 50.0);
        assertThat(param.toString()).contains("Resonance", "7");
    }

    @Test
    void shouldAllowNegativeValues() {
        PluginParameter param = new PluginParameter(0, "Pan", -1.0, 1.0, 0.0);
        assertThat(param.minValue()).isEqualTo(-1.0);
        assertThat(param.maxValue()).isEqualTo(1.0);
    }
}
