package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.automation.AutomationLane;
import com.benesquivelmusic.daw.core.automation.AutomationParameter;
import com.benesquivelmusic.daw.core.automation.AutomationPoint;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for {@link AutomationLaneRenderer} coordinate conversion and
 * hit-testing logic. These tests exercise only the static utility methods
 * and do not require a JavaFX toolkit.
 */
class AutomationLaneRendererTest {

    // ── beatToX / xToBeat ──────────────────────────────────────────────────

    @Test
    void shouldConvertBeatToX() {
        double ppb = 40.0;
        double scroll = 0.0;
        assertThat(AutomationLaneRenderer.beatToX(0.0, ppb, scroll)).isEqualTo(0.0);
        assertThat(AutomationLaneRenderer.beatToX(4.0, ppb, scroll)).isEqualTo(160.0);
    }

    @Test
    void shouldConvertBeatToXWithScroll() {
        double ppb = 40.0;
        double scroll = 2.0;
        assertThat(AutomationLaneRenderer.beatToX(2.0, ppb, scroll)).isEqualTo(0.0);
        assertThat(AutomationLaneRenderer.beatToX(6.0, ppb, scroll)).isEqualTo(160.0);
    }

    @Test
    void shouldConvertXToBeat() {
        double ppb = 40.0;
        double scroll = 0.0;
        assertThat(AutomationLaneRenderer.xToBeat(0.0, ppb, scroll)).isEqualTo(0.0);
        assertThat(AutomationLaneRenderer.xToBeat(160.0, ppb, scroll)).isEqualTo(4.0);
    }

    @Test
    void shouldConvertXToBeatWithScroll() {
        double ppb = 40.0;
        double scroll = 2.0;
        assertThat(AutomationLaneRenderer.xToBeat(0.0, ppb, scroll)).isEqualTo(2.0);
        assertThat(AutomationLaneRenderer.xToBeat(80.0, ppb, scroll)).isEqualTo(4.0);
    }

    @Test
    void shouldRoundTripBeatToXAndBack() {
        double ppb = 60.0;
        double scroll = 5.0;
        double beat = 12.5;
        double x = AutomationLaneRenderer.beatToX(beat, ppb, scroll);
        assertThat(AutomationLaneRenderer.xToBeat(x, ppb, scroll)).isCloseTo(beat, within(0.0001));
    }

    // ── valueToY / yToValue ────────────────────────────────────────────────

    @Test
    void shouldMapMaxValueToTopOfLane() {
        AutomationParameter param = AutomationParameter.VOLUME; // 0.0–1.0
        double laneY = 100.0;
        double laneHeight = 60.0;
        double y = AutomationLaneRenderer.valueToY(1.0, param, laneY, laneHeight);
        // Max value → top inset
        assertThat(y).isCloseTo(laneY + 6.0, within(0.001));
    }

    @Test
    void shouldMapMinValueToBottomOfLane() {
        AutomationParameter param = AutomationParameter.VOLUME;
        double laneY = 100.0;
        double laneHeight = 60.0;
        double y = AutomationLaneRenderer.valueToY(0.0, param, laneY, laneHeight);
        // Min value → bottom inset
        assertThat(y).isCloseTo(laneY + laneHeight - 6.0, within(0.001));
    }

    @Test
    void shouldMapMidValueToCenter() {
        AutomationParameter param = AutomationParameter.VOLUME;
        double laneY = 100.0;
        double laneHeight = 60.0;
        double y = AutomationLaneRenderer.valueToY(0.5, param, laneY, laneHeight);
        assertThat(y).isCloseTo(laneY + laneHeight / 2.0, within(0.001));
    }

    @Test
    void shouldRoundTripValueToYAndBack() {
        AutomationParameter param = AutomationParameter.VOLUME;
        double laneY = 50.0;
        double laneHeight = 80.0;
        double value = 0.73;
        double y = AutomationLaneRenderer.valueToY(value, param, laneY, laneHeight);
        double result = AutomationLaneRenderer.yToValue(y, param, laneY, laneHeight);
        assertThat(result).isCloseTo(value, within(0.001));
    }

    @Test
    void shouldClampYToValueAboveRange() {
        AutomationParameter param = AutomationParameter.VOLUME;
        // Y well above the lane → value should clamp to max
        double value = AutomationLaneRenderer.yToValue(-100.0, param, 50.0, 60.0);
        assertThat(value).isEqualTo(param.getMaxValue());
    }

    @Test
    void shouldClampYToValueBelowRange() {
        AutomationParameter param = AutomationParameter.VOLUME;
        // Y well below the lane → value should clamp to min
        double value = AutomationLaneRenderer.yToValue(999.0, param, 50.0, 60.0);
        assertThat(value).isEqualTo(param.getMinValue());
    }

    @Test
    void shouldHandlePanParameterRange() {
        AutomationParameter param = AutomationParameter.PAN; // -1.0–1.0
        double laneY = 0.0;
        double laneHeight = 60.0;
        double y = AutomationLaneRenderer.valueToY(0.0, param, laneY, laneHeight);
        double result = AutomationLaneRenderer.yToValue(y, param, laneY, laneHeight);
        assertThat(result).isCloseTo(0.0, within(0.001));
    }

    // ── hitTestBreakpoint ──────────────────────────────────────────────────

    @Test
    void shouldHitBreakpointWithinTolerance() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        AutomationPoint point = new AutomationPoint(4.0, 0.5);
        lane.addPoint(point);

        double ppb = 40.0;
        double scroll = 0.0;
        double laneY = 100.0;
        double laneH = 60.0;

        double px = AutomationLaneRenderer.beatToX(4.0, ppb, scroll);
        double py = AutomationLaneRenderer.valueToY(0.5, AutomationParameter.VOLUME, laneY, laneH);

        AutomationPoint hit = AutomationLaneRenderer.hitTestBreakpoint(
                lane, px + 2, py - 1, AutomationParameter.VOLUME,
                laneY, laneH, ppb, scroll);
        assertThat(hit).isSameAs(point);
    }

    @Test
    void shouldMissBreakpointOutsideTolerance() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        lane.addPoint(new AutomationPoint(4.0, 0.5));

        double ppb = 40.0;
        double scroll = 0.0;
        double laneY = 100.0;
        double laneH = 60.0;

        // Click far from the point
        AutomationPoint hit = AutomationLaneRenderer.hitTestBreakpoint(
                lane, 0.0, 0.0, AutomationParameter.VOLUME,
                laneY, laneH, ppb, scroll);
        assertThat(hit).isNull();
    }

    @Test
    void shouldReturnNullForEmptyLane() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);

        AutomationPoint hit = AutomationLaneRenderer.hitTestBreakpoint(
                lane, 50.0, 130.0, AutomationParameter.VOLUME,
                100.0, 60.0, 40.0, 0.0);
        assertThat(hit).isNull();
    }

    @Test
    void shouldHitClosestBreakpointAmongMultiple() {
        AutomationLane lane = new AutomationLane(AutomationParameter.VOLUME);
        AutomationPoint p1 = new AutomationPoint(2.0, 0.8);
        AutomationPoint p2 = new AutomationPoint(4.0, 0.2);
        lane.addPoint(p1);
        lane.addPoint(p2);

        double ppb = 40.0;
        double scroll = 0.0;
        double laneY = 100.0;
        double laneH = 60.0;

        // Click close to p1
        double p1x = AutomationLaneRenderer.beatToX(2.0, ppb, scroll);
        double p1y = AutomationLaneRenderer.valueToY(0.8, AutomationParameter.VOLUME, laneY, laneH);

        AutomationPoint hit = AutomationLaneRenderer.hitTestBreakpoint(
                lane, p1x + 1, p1y, AutomationParameter.VOLUME,
                laneY, laneH, ppb, scroll);
        assertThat(hit).isSameAs(p1);
    }

    // ── Constants ──────────────────────────────────────────────────────────

    @Test
    void shouldHavePositiveLaneHeight() {
        assertThat(AutomationLaneRenderer.AUTOMATION_LANE_HEIGHT).isGreaterThan(0);
    }

    @Test
    void shouldHavePositiveBreakpointRadius() {
        assertThat(AutomationLaneRenderer.BREAKPOINT_RADIUS).isGreaterThan(0);
    }

    @Test
    void shouldHavePositiveHitTolerance() {
        assertThat(AutomationLaneRenderer.HIT_TOLERANCE)
                .isGreaterThanOrEqualTo(AutomationLaneRenderer.BREAKPOINT_RADIUS);
    }
}
