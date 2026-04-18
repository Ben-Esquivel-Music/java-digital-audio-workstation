package com.benesquivelmusic.daw.core.automation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutomationSimplifierTest {

    @Test
    void shouldKeepEndpointsAndInteriorKneeForLShapedCurve() {
        // Dense ramp then flat: the knee at (2.0, 1.0) should be preserved.
        List<AutomationPoint> dense = List.of(
                new AutomationPoint(0.0, 0.0),
                new AutomationPoint(0.5, 0.25),
                new AutomationPoint(1.0, 0.5),
                new AutomationPoint(1.5, 0.75),
                new AutomationPoint(2.0, 1.0),
                new AutomationPoint(2.5, 1.0),
                new AutomationPoint(3.0, 1.0),
                new AutomationPoint(3.5, 1.0),
                new AutomationPoint(4.0, 1.0));

        List<AutomationPoint> simplified = AutomationSimplifier.simplify(dense, 0.01);

        // Expect the two endpoints plus the (2.0, 1.0) corner to survive.
        assertThat(simplified).hasSize(3);
        assertThat(simplified.get(0).getTimeInBeats()).isEqualTo(0.0);
        assertThat(simplified.get(1).getTimeInBeats()).isEqualTo(2.0);
        assertThat(simplified.get(2).getTimeInBeats()).isEqualTo(4.0);
    }

    @Test
    void shouldCollapseColinearPointsToEndpointsOnly() {
        List<AutomationPoint> colinear = List.of(
                new AutomationPoint(0.0, 0.0),
                new AutomationPoint(1.0, 0.25),
                new AutomationPoint(2.0, 0.5),
                new AutomationPoint(3.0, 0.75),
                new AutomationPoint(4.0, 1.0));

        List<AutomationPoint> simplified = AutomationSimplifier.simplify(colinear, 0.01);

        assertThat(simplified).hasSize(2);
        assertThat(simplified.get(0).getTimeInBeats()).isEqualTo(0.0);
        assertThat(simplified.get(1).getTimeInBeats()).isEqualTo(4.0);
    }

    @Test
    void shouldReturnInputUnchangedWhenFewerThanThreePoints() {
        List<AutomationPoint> two = List.of(
                new AutomationPoint(0.0, 0.0),
                new AutomationPoint(1.0, 1.0));

        assertThat(AutomationSimplifier.simplify(two, 0.01)).hasSize(2);
        assertThat(AutomationSimplifier.simplify(List.of(), 0.01)).isEmpty();
    }

    @Test
    void shouldRejectZeroOrNegativeTolerance() {
        List<AutomationPoint> points = List.of(
                new AutomationPoint(0.0, 0.0),
                new AutomationPoint(1.0, 0.5),
                new AutomationPoint(2.0, 1.0));

        assertThatThrownBy(() -> AutomationSimplifier.simplify(points, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AutomationSimplifier.simplify(points, -0.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldThinLaneInPlaceAndReportRemovedCount() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        for (int i = 0; i <= 10; i++) {
            // Straight line from 0 to 1 over 10 beats.
            lane.addPoint(new AutomationPoint(i, i / 10.0));
        }

        int removed = AutomationSimplifier.simplifyLane(lane, 0.01);

        assertThat(removed).isEqualTo(9);
        assertThat(lane.getPointCount()).isEqualTo(2);
        assertThat(lane.getPoints().get(0).getTimeInBeats()).isEqualTo(0.0);
        assertThat(lane.getPoints().get(1).getTimeInBeats()).isEqualTo(10.0);
    }
}
