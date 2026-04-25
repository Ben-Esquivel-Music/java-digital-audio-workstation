package com.benesquivelmusic.daw.sdk.spatial;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonoAmbisonicPannerTest {

    @Test
    void shouldHoldAzimuthElevationAndOrder() {
        MonoAmbisonicPanner p = new MonoAmbisonicPanner(45.0, 30.0, AmbisonicOrder.FIRST);
        assertThat(p.azimuthDegrees()).isEqualTo(45.0);
        assertThat(p.elevationDegrees()).isEqualTo(30.0);
        assertThat(p.order()).isEqualTo(AmbisonicOrder.FIRST);
        assertThat(p.outputChannelCount()).isEqualTo(4);
    }

    @Test
    void horizontalShouldUseZeroElevation() {
        MonoAmbisonicPanner p = MonoAmbisonicPanner.horizontal(90.0, AmbisonicOrder.SECOND);
        assertThat(p.elevationDegrees()).isEqualTo(0.0);
        assertThat(p.outputChannelCount()).isEqualTo(9);
    }

    @Test
    void shouldRejectOutOfRangeAzimuth() {
        assertThatThrownBy(() -> new MonoAmbisonicPanner(181.0, 0.0, AmbisonicOrder.FIRST))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("azimuth");
        assertThatThrownBy(() -> new MonoAmbisonicPanner(-180.0, 0.0, AmbisonicOrder.FIRST))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("azimuth");
    }

    @Test
    void shouldRejectOutOfRangeElevation() {
        assertThatThrownBy(() -> new MonoAmbisonicPanner(0.0, 91.0, AmbisonicOrder.FIRST))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("elevation");
        assertThatThrownBy(() -> new MonoAmbisonicPanner(0.0, -91.0, AmbisonicOrder.FIRST))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("elevation");
    }

    @Test
    void shouldRejectNonFiniteValues() {
        assertThatThrownBy(() -> new MonoAmbisonicPanner(Double.NaN, 0.0, AmbisonicOrder.FIRST))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MonoAmbisonicPanner(0.0, Double.NaN, AmbisonicOrder.FIRST))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MonoAmbisonicPanner(Double.POSITIVE_INFINITY, 0.0, AmbisonicOrder.FIRST))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullOrder() {
        assertThatThrownBy(() -> new MonoAmbisonicPanner(0.0, 0.0, null))
                .isInstanceOf(NullPointerException.class);
    }
}
