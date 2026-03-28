package com.benesquivelmusic.daw.core.plugin.parameter;

import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class PluginParameterStateTest {

    private static final PluginParameter GAIN = new PluginParameter(0, "Gain", -24.0, 24.0, 0.0);
    private static final PluginParameter FREQ = new PluginParameter(1, "Frequency", 20.0, 20000.0, 1000.0);
    private static final PluginParameter MIX = new PluginParameter(2, "Mix", 0.0, 1.0, 0.5);

    @Test
    void shouldInitializeWithDefaultValues() {
        PluginParameterState state = new PluginParameterState(List.of(GAIN, FREQ, MIX));

        assertThat(state.getValue(0)).isEqualTo(0.0);
        assertThat(state.getValue(1)).isEqualTo(1000.0);
        assertThat(state.getValue(2)).isEqualTo(0.5);
    }

    @Test
    void shouldReturnParameterDescriptors() {
        PluginParameterState state = new PluginParameterState(List.of(GAIN, FREQ));

        assertThat(state.getParameters()).hasSize(2);
        assertThat(state.getParameters().get(0).name()).isEqualTo("Gain");
        assertThat(state.getParameters().get(1).name()).isEqualTo("Frequency");
    }

    @Test
    void shouldSetAndGetValues() {
        PluginParameterState state = new PluginParameterState(List.of(GAIN, MIX));

        state.setValue(0, 6.0);
        state.setValue(2, 0.75);

        assertThat(state.getValue(0)).isEqualTo(6.0);
        assertThat(state.getValue(2)).isEqualTo(0.75);
    }

    @Test
    void shouldClampValueToMin() {
        PluginParameterState state = new PluginParameterState(List.of(MIX));

        state.setValue(2, -5.0);

        assertThat(state.getValue(2)).isEqualTo(0.0);
    }

    @Test
    void shouldClampValueToMax() {
        PluginParameterState state = new PluginParameterState(List.of(MIX));

        state.setValue(2, 10.0);

        assertThat(state.getValue(2)).isEqualTo(1.0);
    }

    @Test
    void shouldResetToDefault() {
        PluginParameterState state = new PluginParameterState(List.of(GAIN));

        state.setValue(0, 12.0);
        assertThat(state.getValue(0)).isEqualTo(12.0);

        state.resetToDefault(0);
        assertThat(state.getValue(0)).isEqualTo(0.0);
    }

    @Test
    void shouldResetAllToDefaults() {
        PluginParameterState state = new PluginParameterState(List.of(GAIN, FREQ, MIX));

        state.setValue(0, 10.0);
        state.setValue(1, 5000.0);
        state.setValue(2, 0.9);

        state.resetAllToDefaults();

        assertThat(state.getValue(0)).isEqualTo(0.0);
        assertThat(state.getValue(1)).isEqualTo(1000.0);
        assertThat(state.getValue(2)).isEqualTo(0.5);
    }

    @Test
    void shouldReturnAllValues() {
        PluginParameterState state = new PluginParameterState(List.of(GAIN, MIX));

        state.setValue(0, 3.0);
        Map<Integer, Double> all = state.getAllValues();

        assertThat(all).containsEntry(0, 3.0);
        assertThat(all).containsEntry(2, 0.5);
    }

    @Test
    void shouldLoadValues() {
        PluginParameterState state = new PluginParameterState(List.of(GAIN, MIX));

        state.loadValues(Map.of(0, 7.0, 2, 0.8));

        assertThat(state.getValue(0)).isEqualTo(7.0);
        assertThat(state.getValue(2)).isEqualTo(0.8);
    }

    @Test
    void shouldIgnoreUnknownIdsOnLoad() {
        PluginParameterState state = new PluginParameterState(List.of(GAIN));

        state.loadValues(Map.of(0, 5.0, 999, 1.0));

        assertThat(state.getValue(0)).isEqualTo(5.0);
    }

    @Test
    void shouldClampValuesOnLoad() {
        PluginParameterState state = new PluginParameterState(List.of(MIX));

        state.loadValues(Map.of(2, 99.0));

        assertThat(state.getValue(2)).isEqualTo(1.0);
    }

    @Test
    void shouldRejectNullParameters() {
        assertThatThrownBy(() -> new PluginParameterState(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectUnknownIdOnGet() {
        PluginParameterState state = new PluginParameterState(List.of(GAIN));

        assertThatThrownBy(() -> state.getValue(999))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999");
    }

    @Test
    void shouldRejectUnknownIdOnSet() {
        PluginParameterState state = new PluginParameterState(List.of(GAIN));

        assertThatThrownBy(() -> state.setValue(999, 1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999");
    }

    @Test
    void shouldRejectUnknownIdOnReset() {
        PluginParameterState state = new PluginParameterState(List.of(GAIN));

        assertThatThrownBy(() -> state.resetToDefault(999))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999");
    }

    @Test
    void shouldHandleEmptyParameterList() {
        PluginParameterState state = new PluginParameterState(List.of());

        assertThat(state.getParameters()).isEmpty();
        assertThat(state.getAllValues()).isEmpty();
    }
}
