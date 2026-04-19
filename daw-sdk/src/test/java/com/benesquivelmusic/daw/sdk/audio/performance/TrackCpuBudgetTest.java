package com.benesquivelmusic.daw.sdk.audio.performance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrackCpuBudgetTest {

    @Test
    void constructsWithValidArguments() {
        TrackCpuBudget b = new TrackCpuBudget(0.3, new DegradationPolicy.BypassExpensive());
        assertThat(b.maxFractionOfBlock()).isEqualTo(0.3);
        assertThat(b.onOverBudget()).isInstanceOf(DegradationPolicy.BypassExpensive.class);
    }

    @Test
    void rejectsFractionBelowOrEqualToZero() {
        assertThatThrownBy(() -> new TrackCpuBudget(0.0, new DegradationPolicy.DoNothing()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrackCpuBudget(-0.01, new DegradationPolicy.DoNothing()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsFractionAboveOne() {
        assertThatThrownBy(() -> new TrackCpuBudget(1.01, new DegradationPolicy.DoNothing()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNaNFraction() {
        assertThatThrownBy(() -> new TrackCpuBudget(Double.NaN, new DegradationPolicy.DoNothing()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullPolicy() {
        assertThatThrownBy(() -> new TrackCpuBudget(0.5, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void isOverBudgetUsesStrictInequality() {
        TrackCpuBudget b = new TrackCpuBudget(0.25, new DegradationPolicy.DoNothing());
        assertThat(b.isOverBudget(0.25)).isFalse();
        assertThat(b.isOverBudget(0.2501)).isTrue();
        assertThat(b.isOverBudget(0.1)).isFalse();
    }

    @Test
    void unlimitedDefaultHasDoNothingPolicy() {
        assertThat(TrackCpuBudget.UNLIMITED.maxFractionOfBlock()).isEqualTo(1.0);
        assertThat(TrackCpuBudget.UNLIMITED.onOverBudget())
                .isInstanceOf(DegradationPolicy.DoNothing.class);
    }
}
