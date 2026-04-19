package com.benesquivelmusic.daw.sdk.audio.performance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrackPerformanceEventTest {

    private static final TrackCpuBudget B =
            new TrackCpuBudget(0.3, new DegradationPolicy.BypassExpensive());

    @Test
    void trackDegradedCarriesFields() {
        TrackPerformanceEvent.TrackDegraded e = new TrackPerformanceEvent.TrackDegraded(
                "t1", 0.42, B, new DegradationPolicy.BypassExpensive());
        assertThat(e.trackId()).isEqualTo("t1");
        assertThat(e.measuredFraction()).isEqualTo(0.42);
        assertThat(e.budget()).isSameAs(B);
        assertThat(e.appliedPolicy()).isInstanceOf(DegradationPolicy.BypassExpensive.class);
    }

    @Test
    void trackDegradedRejectsNulls() {
        assertThatThrownBy(() -> new TrackPerformanceEvent.TrackDegraded(
                null, 0.1, B, new DegradationPolicy.DoNothing()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TrackPerformanceEvent.TrackDegraded(
                "t", 0.1, null, new DegradationPolicy.DoNothing()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TrackPerformanceEvent.TrackDegraded(
                "t", 0.1, B, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void trackDegradedRejectsInvalidFraction() {
        assertThatThrownBy(() -> new TrackPerformanceEvent.TrackDegraded(
                "t", -0.1, B, new DegradationPolicy.DoNothing()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrackPerformanceEvent.TrackDegraded(
                "t", Double.NaN, B, new DegradationPolicy.DoNothing()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrackPerformanceEvent.TrackDegraded(
                "t", Double.POSITIVE_INFINITY, B, new DegradationPolicy.DoNothing()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrackPerformanceEvent.TrackDegraded(
                "t", Double.NEGATIVE_INFINITY, B, new DegradationPolicy.DoNothing()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void trackRestoredCarriesFields() {
        TrackPerformanceEvent.TrackRestored e =
                new TrackPerformanceEvent.TrackRestored("t1", 0.1);
        assertThat(e.trackId()).isEqualTo("t1");
        assertThat(e.measuredFraction()).isEqualTo(0.1);
    }

    @Test
    void trackRestoredRejectsInvalidArguments() {
        assertThatThrownBy(() -> new TrackPerformanceEvent.TrackRestored(null, 0.1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TrackPerformanceEvent.TrackRestored("t", -0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrackPerformanceEvent.TrackRestored("t", Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrackPerformanceEvent.TrackRestored("t", Double.NEGATIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void patternMatchingExhaustiveOverSealedHierarchy() {
        TrackPerformanceEvent e = new TrackPerformanceEvent.TrackRestored("t1", 0.1);
        String tag = switch (e) {
            case TrackPerformanceEvent.TrackDegraded d -> "degraded-" + d.trackId();
            case TrackPerformanceEvent.TrackRestored r -> "restored-" + r.trackId();
        };
        assertThat(tag).isEqualTo("restored-t1");
    }
}
