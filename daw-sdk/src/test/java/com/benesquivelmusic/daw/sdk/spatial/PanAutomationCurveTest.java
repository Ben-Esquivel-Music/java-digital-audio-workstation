package com.benesquivelmusic.daw.sdk.spatial;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class PanAutomationCurveTest {

    @Test
    void shouldCreateCurveWithSortedPoints() {
        var curve = new PanAutomationCurve(List.of(
                new PanAutomationPoint(0.0, 0, 0, 1.0),
                new PanAutomationPoint(4.0, 90, 0, 1.0)
        ));
        assertThat(curve.points()).hasSize(2);
        assertThat(curve.hasPoints()).isTrue();
    }

    @Test
    void shouldRejectUnsortedPoints() {
        assertThatThrownBy(() -> new PanAutomationCurve(List.of(
                new PanAutomationPoint(4.0, 0, 0, 1.0),
                new PanAutomationPoint(2.0, 90, 0, 1.0)
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sorted");
    }

    @Test
    void shouldRejectNullPoints() {
        assertThatThrownBy(() -> new PanAutomationCurve(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldReturnFirstPointBeforeStart() {
        var curve = new PanAutomationCurve(List.of(
                new PanAutomationPoint(4.0, 90, 30, 2.0),
                new PanAutomationPoint(8.0, 180, 0, 1.0)
        ));
        var pos = curve.positionAt(0.0);
        assertThat(pos.azimuthDegrees()).isCloseTo(90.0, within(1e-10));
        assertThat(pos.elevationDegrees()).isCloseTo(30.0, within(1e-10));
        assertThat(pos.distanceMeters()).isCloseTo(2.0, within(1e-10));
    }

    @Test
    void shouldReturnLastPointAfterEnd() {
        var curve = new PanAutomationCurve(List.of(
                new PanAutomationPoint(0.0, 0, 0, 1.0),
                new PanAutomationPoint(4.0, 180, 45, 3.0)
        ));
        var pos = curve.positionAt(100.0);
        assertThat(pos.azimuthDegrees()).isCloseTo(180.0, within(1e-10));
        assertThat(pos.elevationDegrees()).isCloseTo(45.0, within(1e-10));
        assertThat(pos.distanceMeters()).isCloseTo(3.0, within(1e-10));
    }

    @Test
    void shouldInterpolateAtMidpoint() {
        var curve = new PanAutomationCurve(List.of(
                new PanAutomationPoint(0.0, 0, 0, 1.0),
                new PanAutomationPoint(4.0, 90, 30, 3.0)
        ));
        var pos = curve.positionAt(2.0);
        assertThat(pos.azimuthDegrees()).isCloseTo(45.0, within(1e-10));
        assertThat(pos.elevationDegrees()).isCloseTo(15.0, within(1e-10));
        assertThat(pos.distanceMeters()).isCloseTo(2.0, within(1e-10));
    }

    @Test
    void shouldInterpolateAtQuarterPoint() {
        var curve = new PanAutomationCurve(List.of(
                new PanAutomationPoint(0.0, 0, 0, 2.0),
                new PanAutomationPoint(8.0, 180, 0, 6.0)
        ));
        var pos = curve.positionAt(2.0); // t = 0.25
        assertThat(pos.azimuthDegrees()).isCloseTo(45.0, within(1e-10));
        assertThat(pos.distanceMeters()).isCloseTo(3.0, within(1e-10));
    }

    @Test
    void shouldInterpolateAcrossMultipleSegments() {
        var curve = new PanAutomationCurve(List.of(
                new PanAutomationPoint(0.0, 0, 0, 1.0),
                new PanAutomationPoint(4.0, 90, 0, 1.0),
                new PanAutomationPoint(8.0, 180, 0, 1.0)
        ));
        // At beat 6 → in second segment, t = 0.5
        var pos = curve.positionAt(6.0);
        assertThat(pos.azimuthDegrees()).isCloseTo(135.0, within(1e-10));
    }

    @Test
    void shouldHandleSinglePoint() {
        var curve = new PanAutomationCurve(List.of(
                new PanAutomationPoint(4.0, 45, 20, 1.5)
        ));
        var pos = curve.positionAt(0.0);
        assertThat(pos.azimuthDegrees()).isCloseTo(45.0, within(1e-10));
        assertThat(pos.elevationDegrees()).isCloseTo(20.0, within(1e-10));
    }

    @Test
    void shouldThrowOnEmptyCurveQuery() {
        var curve = new PanAutomationCurve(List.of());
        assertThatThrownBy(() -> curve.positionAt(0.0))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldReturnExactPointAtKeyframe() {
        var curve = new PanAutomationCurve(List.of(
                new PanAutomationPoint(0.0, 0, 0, 1.0),
                new PanAutomationPoint(4.0, 90, 45, 2.0),
                new PanAutomationPoint(8.0, 180, 0, 1.0)
        ));
        var pos = curve.positionAt(4.0);
        assertThat(pos.azimuthDegrees()).isCloseTo(90.0, within(1e-10));
        assertThat(pos.elevationDegrees()).isCloseTo(45.0, within(1e-10));
        assertThat(pos.distanceMeters()).isCloseTo(2.0, within(1e-10));
    }

    @Test
    void shouldReportHasPointsForNonEmptyCurve() {
        var curve = new PanAutomationCurve(List.of(
                new PanAutomationPoint(0.0, 0, 0, 1.0)
        ));
        assertThat(curve.hasPoints()).isTrue();
    }

    @Test
    void shouldReportNoPointsForEmptyCurve() {
        var curve = new PanAutomationCurve(List.of());
        assertThat(curve.hasPoints()).isFalse();
    }

    @Test
    void shouldMakeDefensiveCopyOfPoints() {
        var points = new java.util.ArrayList<>(List.of(
                new PanAutomationPoint(0.0, 0, 0, 1.0),
                new PanAutomationPoint(4.0, 90, 0, 1.0)
        ));
        var curve = new PanAutomationCurve(points);
        points.clear();
        assertThat(curve.points()).hasSize(2);
    }
}
