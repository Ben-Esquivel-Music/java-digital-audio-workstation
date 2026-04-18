package com.benesquivelmusic.daw.core.automation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutomationLanePluginTargetTest {

    private static final PluginParameterTarget TARGET = new PluginParameterTarget(
            "compressor#1", 0, "Threshold", -60.0, 0.0, -20.0, "dB");

    @Test
    void shouldConstructWithPluginParameterTarget() {
        AutomationLane lane = new AutomationLane(TARGET);

        assertThat(lane.getTarget()).isSameAs(TARGET);
        assertThat(lane.getPointCount()).isZero();
    }

    @Test
    void shouldReturnTargetDefaultWhenEmpty() {
        AutomationLane lane = new AutomationLane(TARGET);

        assertThat(lane.getValueAtTime(0.0)).isEqualTo(-20.0);
        assertThat(lane.getValueAtTime(10.0)).isEqualTo(-20.0);
    }

    @Test
    void shouldValidateValueAgainstTargetRange() {
        AutomationLane lane = new AutomationLane(TARGET);

        // Out-of-range value for threshold (max is 0.0 dB).
        assertThatThrownBy(() -> lane.addPoint(new AutomationPoint(1.0, 5.0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the valid range");
    }

    @Test
    void getParameterShouldThrowForPluginTarget() {
        AutomationLane lane = new AutomationLane(TARGET);

        assertThatThrownBy(lane::getParameter)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("plugin parameter");
    }

    @Test
    void shouldInterpolateBetweenPluginPoints() {
        AutomationLane lane = new AutomationLane(TARGET);
        lane.addPoint(new AutomationPoint(0.0, -40.0));
        lane.addPoint(new AutomationPoint(4.0, -20.0));

        assertThat(lane.getValueAtTime(2.0)).isEqualTo(-30.0);
    }
}
