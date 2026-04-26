package com.benesquivelmusic.daw.sdk.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutomationLaneTest {

    @Test
    void of_createsEmptyLane() {
        AutomationLane l = AutomationLane.of("track.volume");
        assertThat(l.parameterName()).isEqualTo("track.volume");
        assertThat(l.points()).isEmpty();
    }

    @Test
    void pointsList_isImmutable() {
        AutomationLane l = AutomationLane.of("p")
                .withPoints(List.of(new AutomationPoint(0.0, 0.5)));
        assertThatThrownBy(() -> l.points().add(new AutomationPoint(1.0, 0.6)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void pointWithers() {
        AutomationPoint a = new AutomationPoint(0.0, 0.5);
        AutomationPoint b = a.withBeat(2.0).withValue(0.75);
        assertThat(a.beat()).isEqualTo(0.0);
        assertThat(b.beat()).isEqualTo(2.0);
        assertThat(b.value()).isEqualTo(0.75);
    }

    @Test
    void negativeBeat_throws() {
        assertThatThrownBy(() -> new AutomationPoint(-0.01, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
