package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Non-UI-thread tests for {@link MatchEqPluginView}.
 *
 * <p>Follows the same pattern as {@link BusCompressorPluginViewTest}: we
 * avoid instantiating the {@code VBox} (which would require the JavaFX
 * toolkit), and instead exercise the package-private static mapping helpers
 * and compile-time constants. This keeps the test safe to run in a truly
 * headless CI without the {@code monocle} / {@code TestFX} harness.</p>
 */
class MatchEqPluginViewTest {

    @Test
    void plotExtentsShouldBePositive() {
        assertThat(MatchEqPluginView.DB_RANGE).isGreaterThan(0.0);
        assertThat(MatchEqPluginView.MIN_FREQUENCY_HZ).isGreaterThan(0.0);
        assertThat(MatchEqPluginView.MAX_FREQUENCY_HZ)
                .isGreaterThan(MatchEqPluginView.MIN_FREQUENCY_HZ);
        assertThat(MatchEqPluginView.PLOT_WIDTH).isGreaterThan(0.0);
        assertThat(MatchEqPluginView.PLOT_HEIGHT).isGreaterThan(0.0);
    }

    @Test
    void frequencyMappingShouldBeMonotonicAndSpanEntirePlot() {
        double w = MatchEqPluginView.PLOT_WIDTH;
        double left = MatchEqPluginView.freqToX(MatchEqPluginView.MIN_FREQUENCY_HZ, w);
        double mid = MatchEqPluginView.freqToX(1_000.0, w);
        double right = MatchEqPluginView.freqToX(MatchEqPluginView.MAX_FREQUENCY_HZ, w);
        assertThat(left).isEqualTo(0.0);
        assertThat(right).isEqualTo(w);
        assertThat(mid).isBetween(left, right);
    }

    @Test
    void frequencyMappingShouldClampOutOfRangeInputs() {
        double w = MatchEqPluginView.PLOT_WIDTH;
        assertThat(MatchEqPluginView.freqToX(0.0, w)).isEqualTo(0.0);
        assertThat(MatchEqPluginView.freqToX(1e9, w)).isEqualTo(w);
    }

    @Test
    void dbMappingShouldPlaceZeroAtVerticalCentre() {
        double h = MatchEqPluginView.PLOT_HEIGHT;
        double centre = MatchEqPluginView.dbToY(0.0, h);
        assertThat(centre).isCloseTo(h / 2.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void dbMappingShouldClampAtExtremes() {
        double h = MatchEqPluginView.PLOT_HEIGHT;
        double top = MatchEqPluginView.dbToY(+MatchEqPluginView.DB_RANGE, h);
        double bottom = MatchEqPluginView.dbToY(-MatchEqPluginView.DB_RANGE, h);
        assertThat(top).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(bottom).isCloseTo(h, org.assertj.core.data.Offset.offset(1e-9));

        // Out-of-range inputs stay clipped.
        assertThat(MatchEqPluginView.dbToY(+1_000.0, h)).isCloseTo(0.0,
                org.assertj.core.data.Offset.offset(1e-9));
        assertThat(MatchEqPluginView.dbToY(-1_000.0, h)).isCloseTo(h,
                org.assertj.core.data.Offset.offset(1e-9));
    }
}
