package com.benesquivelmusic.daw.core.automation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class AutomationLaneTest {

    @Test
    void shouldCreateLaneWithParameter() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);

        assertThat(lane.getParameter()).isEqualTo(AutomationParameter.VOLUME);
        assertThat(lane.getPoints()).isEmpty();
        assertThat(lane.getPointCount()).isZero();
        assertThat(lane.isVisible()).isTrue();
    }

    @Test
    void shouldRejectNullParameter() {
        assertThatThrownBy(() -> new AutomationLane(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldToggleVisibility() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        lane.setVisible(false);
        assertThat(lane.isVisible()).isFalse();
        lane.setVisible(true);
        assertThat(lane.isVisible()).isTrue();
    }

    // ── Add/remove points ──────────────────────────────────────────────────

    @Test
    void shouldAddPoint() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        AutomationPoint point = new AutomationPoint(1.0, 0.5);

        lane.addPoint(point);

        assertThat(lane.getPointCount()).isEqualTo(1);
        assertThat(lane.getPoints()).containsExactly(point);
    }

    @Test
    void shouldRejectNullPoint() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        assertThatThrownBy(() -> lane.addPoint(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectPointWithValueOutOfRange() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        AutomationPoint outOfRange = new AutomationPoint(0.0, 1.5);

        assertThatThrownBy(() -> lane.addPoint(outOfRange))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldMaintainSortedOrder() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        AutomationPoint p3 = new AutomationPoint(8.0, 0.3);
        AutomationPoint p1 = new AutomationPoint(0.0, 1.0);
        AutomationPoint p2 = new AutomationPoint(4.0, 0.5);

        lane.addPoint(p3);
        lane.addPoint(p1);
        lane.addPoint(p2);

        assertThat(lane.getPoints()).containsExactly(p1, p2, p3);
    }

    @Test
    void shouldRemovePoint() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        AutomationPoint point = new AutomationPoint(1.0, 0.5);
        lane.addPoint(point);

        boolean removed = lane.removePoint(point);

        assertThat(removed).isTrue();
        assertThat(lane.getPoints()).isEmpty();
    }

    @Test
    void shouldReturnFalseWhenRemovingAbsentPoint() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        AutomationPoint point = new AutomationPoint(1.0, 0.5);

        assertThat(lane.removePoint(point)).isFalse();
    }

    @Test
    void shouldClearAllPoints() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        lane.addPoint(new AutomationPoint(0.0, 1.0));
        lane.addPoint(new AutomationPoint(4.0, 0.5));

        lane.clearPoints();

        assertThat(lane.getPoints()).isEmpty();
    }

    @Test
    void shouldReturnUnmodifiablePointsList() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        lane.addPoint(new AutomationPoint(0.0, 1.0));

        assertThatThrownBy(() -> lane.getPoints().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ── Interpolation ──────────────────────────────────────────────────────

    @Test
    void shouldReturnDefaultValueWhenEmpty() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);

        assertThat(lane.getValueAtTime(0.0)).isEqualTo(1.0);
        assertThat(lane.getValueAtTime(10.0)).isEqualTo(1.0);
    }

    @Test
    void shouldReturnDefaultValueForPanWhenEmpty() {
        AutomationLane lane = new AutomationLane(AutomationParameter.PAN);
        assertThat(lane.getValueAtTime(5.0)).isEqualTo(0.0);
    }

    @Test
    void shouldReturnFirstPointValueBeforeFirstPoint() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        lane.addPoint(new AutomationPoint(4.0, 0.8));

        assertThat(lane.getValueAtTime(0.0)).isEqualTo(0.8);
        assertThat(lane.getValueAtTime(3.9)).isEqualTo(0.8);
    }

    @Test
    void shouldReturnLastPointValueAfterLastPoint() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        lane.addPoint(new AutomationPoint(0.0, 1.0));
        lane.addPoint(new AutomationPoint(4.0, 0.5));

        assertThat(lane.getValueAtTime(4.0)).isEqualTo(0.5);
        assertThat(lane.getValueAtTime(100.0)).isEqualTo(0.5);
    }

    @Test
    void shouldInterpolateLinearly() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        lane.addPoint(new AutomationPoint(0.0, 1.0));
        lane.addPoint(new AutomationPoint(4.0, 0.0));

        assertThat(lane.getValueAtTime(0.0)).isCloseTo(1.0, within(0.001));
        assertThat(lane.getValueAtTime(1.0)).isCloseTo(0.75, within(0.001));
        assertThat(lane.getValueAtTime(2.0)).isCloseTo(0.5, within(0.001));
        assertThat(lane.getValueAtTime(3.0)).isCloseTo(0.25, within(0.001));
        assertThat(lane.getValueAtTime(4.0)).isCloseTo(0.0, within(0.001));
    }

    @Test
    void shouldInterpolateCurved() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        lane.addPoint(new AutomationPoint(0.0, 0.0, InterpolationMode.CURVED));
        lane.addPoint(new AutomationPoint(4.0, 1.0));

        // Smoothstep at t=0.5: 3*(0.5)^2 - 2*(0.5)^3 = 0.5
        assertThat(lane.getValueAtTime(2.0)).isCloseTo(0.5, within(0.001));

        // Smoothstep at t=0.25: 3*(0.25)^2 - 2*(0.25)^3 = 0.15625
        assertThat(lane.getValueAtTime(1.0)).isCloseTo(0.15625, within(0.001));

        // Smoothstep at t=0.75: 3*(0.75)^2 - 2*(0.75)^3 = 0.84375
        assertThat(lane.getValueAtTime(3.0)).isCloseTo(0.84375, within(0.001));
    }

    @Test
    void shouldInterpolateBetweenMultiplePoints() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        lane.addPoint(new AutomationPoint(0.0, 1.0));
        lane.addPoint(new AutomationPoint(4.0, 0.5));
        lane.addPoint(new AutomationPoint(8.0, 0.0));

        assertThat(lane.getValueAtTime(2.0)).isCloseTo(0.75, within(0.001));
        assertThat(lane.getValueAtTime(6.0)).isCloseTo(0.25, within(0.001));
    }

    @Test
    void shouldReturnExactValueAtPoint() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        lane.addPoint(new AutomationPoint(0.0, 1.0));
        lane.addPoint(new AutomationPoint(4.0, 0.5));

        assertThat(lane.getValueAtTime(0.0)).isEqualTo(1.0);
        assertThat(lane.getValueAtTime(4.0)).isEqualTo(0.5);
    }

    @Test
    void shouldHandleSinglePoint() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        lane.addPoint(new AutomationPoint(2.0, 0.7));

        assertThat(lane.getValueAtTime(0.0)).isEqualTo(0.7);
        assertThat(lane.getValueAtTime(2.0)).isEqualTo(0.7);
        assertThat(lane.getValueAtTime(10.0)).isEqualTo(0.7);
    }

    @Test
    void shouldResortAfterExternalModification() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        AutomationPoint p1 = new AutomationPoint(0.0, 1.0);
        AutomationPoint p2 = new AutomationPoint(4.0, 0.5);
        lane.addPoint(p1);
        lane.addPoint(p2);

        // Move p2 to before p1
        p2.setTimeInBeats(0.0);
        p1.setTimeInBeats(4.0);
        lane.sortPoints();

        assertThat(lane.getPoints().get(0)).isEqualTo(p2);
        assertThat(lane.getPoints().get(1)).isEqualTo(p1);
    }

    @Test
    void shouldWorkWithPanParameter() {
        AutomationLane lane = new AutomationLane(AutomationParameter.PAN);
        lane.addPoint(new AutomationPoint(0.0, -1.0));
        lane.addPoint(new AutomationPoint(4.0, 1.0));

        assertThat(lane.getValueAtTime(2.0)).isCloseTo(0.0, within(0.001));
    }
}
