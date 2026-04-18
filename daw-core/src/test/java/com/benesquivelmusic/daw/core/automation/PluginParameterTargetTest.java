package com.benesquivelmusic.daw.core.automation;

import com.benesquivelmusic.daw.sdk.plugin.AutomatableParameter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginParameterTargetTest {

    @Test
    void shouldExposeAutomationTargetContract() {
        PluginParameterTarget target = new PluginParameterTarget(
                "compressor#1", 3, "Attack", 0.01, 100.0, 10.0, "ms");

        assertThat(target).isInstanceOf(AutomationTarget.class);
        assertThat(target.getMinValue()).isEqualTo(0.01);
        assertThat(target.getMaxValue()).isEqualTo(100.0);
        assertThat(target.getDefaultValue()).isEqualTo(10.0);
        assertThat(target.displayName()).isEqualTo("Attack");
        assertThat(target.unit()).isEqualTo("ms");
        assertThat(target.isValidValue(5.0)).isTrue();
        assertThat(target.isValidValue(500.0)).isFalse();
    }

    @Test
    void shouldBuildFromSdkDescriptor() {
        AutomatableParameter descriptor = new AutomatableParameter(
                7, "Threshold", -60.0, 0.0, -20.0, "dB");

        PluginParameterTarget target = PluginParameterTarget.of(
                "compressor#track-a/slot-0", descriptor);

        assertThat(target.pluginInstanceId()).isEqualTo("compressor#track-a/slot-0");
        assertThat(target.parameterId()).isEqualTo(7);
        assertThat(target.displayName()).isEqualTo("Threshold");
        assertThat(target.minValue()).isEqualTo(-60.0);
        assertThat(target.maxValue()).isEqualTo(0.0);
        assertThat(target.defaultValue()).isEqualTo(-20.0);
        assertThat(target.unit()).isEqualTo("dB");
    }

    @Test
    void shouldRejectBlankInstanceId() {
        assertThatThrownBy(() -> new PluginParameterTarget(
                " ", 0, "X", 0.0, 1.0, 0.5, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pluginInstanceId");
    }

    @Test
    void shouldBeEqualWhenInstanceIdAndParameterIdMatch() {
        PluginParameterTarget a = new PluginParameterTarget(
                "plugin#1", 0, "Gain", 0.0, 1.0, 0.5, "");
        PluginParameterTarget b = new PluginParameterTarget(
                "plugin#1", 0, "Gain", 0.0, 1.0, 0.5, "");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }
}
