package com.benesquivelmusic.daw.core.plugin.editor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Non-UI-thread tests for {@link TruePeakLimiterEditor} — the story-302
 * migration of the retired daw-app {@code TruePeakLimiterPluginViewTest}.
 *
 * <p>daw-core has no JavaFX toolkit test infrastructure, so these tests
 * exercise only the compile-time meter-range constants the painting math is
 * built on; FX-level editor behaviour is covered host-side in daw-app.</p>
 */
class TruePeakLimiterEditorTest {

    @Test
    void grMeterRangeShouldBePositive() {
        assertThat(TruePeakLimiterEditor.GR_METER_MAX_DB).isGreaterThan(0.0);
    }

    @Test
    void peakMeterFloorShouldBeNegative() {
        assertThat(TruePeakLimiterEditor.PEAK_METER_FLOOR_DB).isLessThan(0.0);
    }
}
