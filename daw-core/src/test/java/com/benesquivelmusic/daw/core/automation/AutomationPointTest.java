package com.benesquivelmusic.daw.core.automation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutomationPointTest {

    @Test
    void shouldCreatePointWithDefaults() {
        AutomationPoint point = new AutomationPoint(4.0, 0.75);

        assertThat(point.getTimeInBeats()).isEqualTo(4.0);
        assertThat(point.getValue()).isEqualTo(0.75);
        assertThat(point.getInterpolationMode()).isEqualTo(InterpolationMode.LINEAR);
    }

    @Test
    void shouldCreatePointWithInterpolationMode() {
        AutomationPoint point = new AutomationPoint(2.0, 0.5, InterpolationMode.CURVED);

        assertThat(point.getTimeInBeats()).isEqualTo(2.0);
        assertThat(point.getValue()).isEqualTo(0.5);
        assertThat(point.getInterpolationMode()).isEqualTo(InterpolationMode.CURVED);
    }

    @Test
    void shouldRejectNegativeTime() {
        assertThatThrownBy(() -> new AutomationPoint(-1.0, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullInterpolationMode() {
        assertThatThrownBy(() -> new AutomationPoint(0.0, 0.5, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldSetTimeInBeats() {
        AutomationPoint point = new AutomationPoint(0.0, 0.5);
        point.setTimeInBeats(8.0);
        assertThat(point.getTimeInBeats()).isEqualTo(8.0);
    }

    @Test
    void shouldRejectNegativeTimeOnSet() {
        AutomationPoint point = new AutomationPoint(0.0, 0.5);
        assertThatThrownBy(() -> point.setTimeInBeats(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldSetValue() {
        AutomationPoint point = new AutomationPoint(0.0, 0.5);
        point.setValue(0.9);
        assertThat(point.getValue()).isEqualTo(0.9);
    }

    @Test
    void shouldSetInterpolationMode() {
        AutomationPoint point = new AutomationPoint(0.0, 0.5);
        point.setInterpolationMode(InterpolationMode.CURVED);
        assertThat(point.getInterpolationMode()).isEqualTo(InterpolationMode.CURVED);
    }

    @Test
    void shouldRejectNullInterpolationModeOnSet() {
        AutomationPoint point = new AutomationPoint(0.0, 0.5);
        assertThatThrownBy(() -> point.setInterpolationMode(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldCompareByTime() {
        AutomationPoint early = new AutomationPoint(1.0, 0.5);
        AutomationPoint late = new AutomationPoint(4.0, 0.5);

        assertThat(early.compareTo(late)).isNegative();
        assertThat(late.compareTo(early)).isPositive();
        assertThat(early.compareTo(early)).isZero();
    }

    @Test
    void shouldSupportEquality() {
        AutomationPoint a = new AutomationPoint(2.0, 0.5, InterpolationMode.LINEAR);
        AutomationPoint b = new AutomationPoint(2.0, 0.5, InterpolationMode.LINEAR);
        AutomationPoint c = new AutomationPoint(3.0, 0.5, InterpolationMode.LINEAR);

        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void shouldHaveToString() {
        AutomationPoint point = new AutomationPoint(1.0, 0.8, InterpolationMode.CURVED);
        assertThat(point.toString()).contains("1.0").contains("0.8").contains("CURVED");
    }

    @Test
    void shouldAllowZeroTime() {
        AutomationPoint point = new AutomationPoint(0.0, 1.0);
        assertThat(point.getTimeInBeats()).isEqualTo(0.0);
    }
}
