package com.benesquivelmusic.daw.app.ui.display;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoomTelemetryDisplayTest {

    private static final double AUDIENCE_RADIUS = 7.0;
    private static final double LABEL_OFFSET = 18.0;
    private static final double AUDIENCE_LABEL_STAGGER = 12.0;

    @Test
    void evenIndexShouldPlaceLabelBelowSilhouette() {
        double cy = 200.0;
        double labelY = RoomTelemetryDisplay.computeAudienceLabelY(cy, 0);

        assertThat(labelY).isEqualTo(cy + AUDIENCE_RADIUS + LABEL_OFFSET);
    }

    @Test
    void oddIndexShouldPlaceLabelAboveSilhouette() {
        double cy = 200.0;
        double labelY = RoomTelemetryDisplay.computeAudienceLabelY(cy, 1);

        assertThat(labelY).isEqualTo(cy - AUDIENCE_RADIUS - AUDIENCE_LABEL_STAGGER);
    }

    @Test
    void shouldAlternateLabelPositionsForConsecutiveMembers() {
        double cy = 150.0;

        double label0 = RoomTelemetryDisplay.computeAudienceLabelY(cy, 0);
        double label1 = RoomTelemetryDisplay.computeAudienceLabelY(cy, 1);
        double label2 = RoomTelemetryDisplay.computeAudienceLabelY(cy, 2);
        double label3 = RoomTelemetryDisplay.computeAudienceLabelY(cy, 3);

        // Even indices go below, odd indices go above
        assertThat(label0).isGreaterThan(cy);
        assertThat(label1).isLessThan(cy);
        assertThat(label2).isGreaterThan(cy);
        assertThat(label3).isLessThan(cy);

        // Same-parity labels should be at the same offset from center
        assertThat(label0).isEqualTo(label2);
        assertThat(label1).isEqualTo(label3);
    }

    @Test
    void labelsShouldBeDistinctForAdjacentIndices() {
        double cy = 100.0;

        double labelBelow = RoomTelemetryDisplay.computeAudienceLabelY(cy, 0);
        double labelAbove = RoomTelemetryDisplay.computeAudienceLabelY(cy, 1);

        // The stagger should create meaningful vertical separation
        assertThat(Math.abs(labelBelow - labelAbove))
                .isGreaterThan(AUDIENCE_RADIUS * 2);
    }

    @Test
    void shouldHandleLargeIndex() {
        double cy = 300.0;

        double label100 = RoomTelemetryDisplay.computeAudienceLabelY(cy, 100);
        double label101 = RoomTelemetryDisplay.computeAudienceLabelY(cy, 101);

        // Even index → below
        assertThat(label100).isGreaterThan(cy);
        // Odd index → above
        assertThat(label101).isLessThan(cy);
    }
}
