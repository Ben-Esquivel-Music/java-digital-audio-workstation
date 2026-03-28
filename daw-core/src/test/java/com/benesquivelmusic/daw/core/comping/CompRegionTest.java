package com.benesquivelmusic.daw.core.comping;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompRegionTest {

    @Test
    void shouldCreateCompRegion() {
        CompRegion region = new CompRegion(0, 4.0, 8.0);

        assertThat(region.takeIndex()).isEqualTo(0);
        assertThat(region.startBeat()).isEqualTo(4.0);
        assertThat(region.durationBeats()).isEqualTo(8.0);
    }

    @Test
    void shouldCalculateEndBeat() {
        CompRegion region = new CompRegion(1, 4.0, 8.0);

        assertThat(region.endBeat()).isEqualTo(12.0);
    }

    @Test
    void shouldRejectNegativeTakeIndex() {
        assertThatThrownBy(() -> new CompRegion(-1, 0.0, 4.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeStartBeat() {
        assertThatThrownBy(() -> new CompRegion(0, -1.0, 4.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectZeroDuration() {
        assertThatThrownBy(() -> new CompRegion(0, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeDuration() {
        assertThatThrownBy(() -> new CompRegion(0, 0.0, -1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldDetectOverlap() {
        CompRegion region = new CompRegion(0, 4.0, 8.0); // [4, 12)

        assertThat(region.overlaps(6.0, 10.0)).isTrue();  // inside
        assertThat(region.overlaps(0.0, 5.0)).isTrue();    // partial start
        assertThat(region.overlaps(10.0, 16.0)).isTrue();  // partial end
        assertThat(region.overlaps(0.0, 20.0)).isTrue();   // enclosing
    }

    @Test
    void shouldNotOverlapWhenDisjoint() {
        CompRegion region = new CompRegion(0, 4.0, 8.0); // [4, 12)

        assertThat(region.overlaps(0.0, 4.0)).isFalse();  // adjacent before
        assertThat(region.overlaps(12.0, 16.0)).isFalse(); // adjacent after
        assertThat(region.overlaps(0.0, 3.0)).isFalse();   // before
        assertThat(region.overlaps(13.0, 20.0)).isFalse(); // after
    }

    @Test
    void shouldSupportEquality() {
        CompRegion a = new CompRegion(0, 4.0, 8.0);
        CompRegion b = new CompRegion(0, 4.0, 8.0);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void shouldNotBeEqualForDifferentValues() {
        CompRegion a = new CompRegion(0, 4.0, 8.0);
        CompRegion b = new CompRegion(1, 4.0, 8.0);

        assertThat(a).isNotEqualTo(b);
    }
}
