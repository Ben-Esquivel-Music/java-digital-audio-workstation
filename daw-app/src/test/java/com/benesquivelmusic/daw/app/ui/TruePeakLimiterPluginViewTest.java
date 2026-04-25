package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Non-UI-thread tests for {@link TruePeakLimiterPluginView}.
 *
 * <p>Mirrors {@link DeEsserPluginViewTest}: avoids instantiating the
 * {@code VBox} (which would require the JavaFX toolkit), and instead exercises
 * compile-time constants and static invariants of the view.</p>
 */
class TruePeakLimiterPluginViewTest {

    @Test
    void grMeterRangeShouldBePositive() {
        assertThat(TruePeakLimiterPluginView.GR_METER_MAX_DB).isGreaterThan(0.0);
    }

    @Test
    void peakMeterFloorShouldBeNegative() {
        assertThat(TruePeakLimiterPluginView.PEAK_METER_FLOOR_DB).isLessThan(0.0);
    }
}
