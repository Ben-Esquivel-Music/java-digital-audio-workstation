package com.benesquivelmusic.daw.sdk.spatial;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpatialPannerDataTest {

    @Test
    void shouldCreateValidData() {
        SpatialPosition pos = new SpatialPosition(0, 0, 1.0);
        List<SpatialPosition> speakers = List.of(
                new SpatialPosition(30, 0, 1.0),
                new SpatialPosition(330, 0, 1.0)
        );
        double[] gains = new double[]{0.7, 0.7};

        SpatialPannerData data = new SpatialPannerData(pos, speakers, gains, 0.0, 1.0, 1.0, 0.0,
                PositioningMode.FREE_FORM);

        assertThat(data.sourcePosition()).isEqualTo(pos);
        assertThat(data.speakerPositions()).hasSize(2);
        assertThat(data.speakerGains()).hasSize(2);
        assertThat(data.spread()).isEqualTo(0.0);
        assertThat(data.distanceGain()).isEqualTo(1.0);
        assertThat(data.positioningMode()).isEqualTo(PositioningMode.FREE_FORM);
    }

    @Test
    void shouldRejectNullSourcePosition() {
        assertThatThrownBy(() -> new SpatialPannerData(
                null, List.of(), new double[0], 0, 1, 1, 0, PositioningMode.FREE_FORM))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldMakeDefensiveCopyOfGains() {
        double[] gains = new double[]{0.5, 0.5};
        SpatialPannerData data = new SpatialPannerData(
                new SpatialPosition(0, 0, 1.0),
                List.of(new SpatialPosition(30, 0, 1.0), new SpatialPosition(330, 0, 1.0)),
                gains, 0, 1, 1, 0, PositioningMode.FREE_FORM);
        gains[0] = 999;
        assertThat(data.speakerGains()[0]).isEqualTo(0.5);
    }
}
