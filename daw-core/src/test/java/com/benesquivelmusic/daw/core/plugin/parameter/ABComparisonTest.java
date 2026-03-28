package com.benesquivelmusic.daw.core.plugin.parameter;

import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ABComparisonTest {

    private static final PluginParameter GAIN = new PluginParameter(0, "Gain", -24.0, 24.0, 0.0);
    private static final PluginParameter MIX = new PluginParameter(1, "Mix", 0.0, 1.0, 0.5);

    @Test
    void shouldStartOnSlotA() {
        PluginParameterState state = new PluginParameterState(List.of(GAIN, MIX));
        ABComparison ab = new ABComparison(state);

        assertThat(ab.getActiveSlot()).isEqualTo(ABComparison.Slot.A);
    }

    @Test
    void shouldToggleToSlotB() {
        PluginParameterState state = new PluginParameterState(List.of(GAIN, MIX));
        ABComparison ab = new ABComparison(state);

        ab.toggle();

        assertThat(ab.getActiveSlot()).isEqualTo(ABComparison.Slot.B);
    }

    @Test
    void shouldToggleBackToSlotA() {
        PluginParameterState state = new PluginParameterState(List.of(GAIN, MIX));
        ABComparison ab = new ABComparison(state);

        ab.toggle(); // A -> B
        ab.toggle(); // B -> A

        assertThat(ab.getActiveSlot()).isEqualTo(ABComparison.Slot.A);
    }

    @Test
    void shouldPreserveStateOnToggle() {
        PluginParameterState state = new PluginParameterState(List.of(GAIN, MIX));
        ABComparison ab = new ABComparison(state);

        // Modify state while A is active
        state.setValue(0, 12.0);
        state.setValue(1, 0.9);

        // Toggle to B — should load defaults (B was initialized with defaults)
        ab.toggle();
        assertThat(state.getValue(0)).isEqualTo(0.0);
        assertThat(state.getValue(1)).isEqualTo(0.5);

        // Modify B
        state.setValue(0, -6.0);

        // Toggle back to A — should restore our A modifications
        ab.toggle();
        assertThat(state.getValue(0)).isEqualTo(12.0);
        assertThat(state.getValue(1)).isEqualTo(0.9);
    }

    @Test
    void shouldCopyActiveToInactive() {
        PluginParameterState state = new PluginParameterState(List.of(GAIN));
        ABComparison ab = new ABComparison(state);

        state.setValue(0, 10.0);
        ab.copyActiveToInactive();

        // Toggle to B
        ab.toggle();
        // B should now have the copied value
        assertThat(state.getValue(0)).isEqualTo(10.0);
    }

    @Test
    void shouldReturnSlotValues() {
        PluginParameterState state = new PluginParameterState(List.of(GAIN));
        ABComparison ab = new ABComparison(state);

        state.setValue(0, 5.0);

        // Active slot A should return live values
        Map<Integer, Double> slotAValues = ab.getSlotValues(ABComparison.Slot.A);
        assertThat(slotAValues).containsEntry(0, 5.0);

        // Inactive slot B should return initial snapshot
        Map<Integer, Double> slotBValues = ab.getSlotValues(ABComparison.Slot.B);
        assertThat(slotBValues).containsEntry(0, 0.0);
    }

    @Test
    void shouldRejectNullState() {
        assertThatThrownBy(() -> new ABComparison(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullSlotInGetValues() {
        PluginParameterState state = new PluginParameterState(List.of(GAIN));
        ABComparison ab = new ABComparison(state);

        assertThatThrownBy(() -> ab.getSlotValues(null))
                .isInstanceOf(NullPointerException.class);
    }
}
