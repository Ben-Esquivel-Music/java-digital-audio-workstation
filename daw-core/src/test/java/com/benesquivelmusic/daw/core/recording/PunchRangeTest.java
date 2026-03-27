package com.benesquivelmusic.daw.core.recording;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PunchRangeTest {

    @Test
    void shouldCreateValidPunchRange() {
        PunchRange range = new PunchRange(4.0, 8.0);

        assertThat(range.punchInBeat()).isEqualTo(4.0);
        assertThat(range.punchOutBeat()).isEqualTo(8.0);
    }

    @Test
    void shouldComputeDuration() {
        PunchRange range = new PunchRange(4.0, 12.0);

        assertThat(range.durationBeats()).isEqualTo(8.0);
    }

    @Test
    void shouldContainBeatWithinRange() {
        PunchRange range = new PunchRange(4.0, 8.0);

        assertThat(range.contains(4.0)).isTrue();
        assertThat(range.contains(6.0)).isTrue();
        assertThat(range.contains(7.999)).isTrue();
    }

    @Test
    void shouldNotContainBeatOutsideRange() {
        PunchRange range = new PunchRange(4.0, 8.0);

        assertThat(range.contains(3.999)).isFalse();
        assertThat(range.contains(8.0)).isFalse();
        assertThat(range.contains(10.0)).isFalse();
    }

    @Test
    void shouldRejectNegativePunchIn() {
        assertThatThrownBy(() -> new PunchRange(-1.0, 4.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("punchInBeat");
    }

    @Test
    void shouldRejectPunchOutEqualToPunchIn() {
        assertThatThrownBy(() -> new PunchRange(4.0, 4.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("punchOutBeat");
    }

    @Test
    void shouldRejectPunchOutBeforePunchIn() {
        assertThatThrownBy(() -> new PunchRange(8.0, 4.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("punchOutBeat");
    }

    @Test
    void shouldAllowZeroPunchIn() {
        PunchRange range = new PunchRange(0.0, 4.0);

        assertThat(range.punchInBeat()).isZero();
        assertThat(range.durationBeats()).isEqualTo(4.0);
    }

    @Test
    void shouldSupportEquality() {
        PunchRange a = new PunchRange(4.0, 8.0);
        PunchRange b = new PunchRange(4.0, 8.0);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void shouldSupportInequality() {
        PunchRange a = new PunchRange(4.0, 8.0);
        PunchRange b = new PunchRange(4.0, 12.0);

        assertThat(a).isNotEqualTo(b);
    }
}
