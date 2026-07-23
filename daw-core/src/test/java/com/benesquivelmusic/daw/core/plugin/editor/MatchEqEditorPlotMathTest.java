package com.benesquivelmusic.daw.core.plugin.editor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * Headless tests for {@link MatchEqEditor}'s package-private static plot
 * mapping helpers — ported from the daw-app {@code MatchEqPluginViewTest}
 * (whose view retires with story 302) so the log-frequency / dB mapping keeps
 * its contract across the migration.
 *
 * <p>The helpers are pure math on plain doubles: no JavaFX type appears in
 * the exercised signatures and no FX toolkit is started (daw-core has no
 * JavaFX test infrastructure). The immersive canvas has no fixed plot size,
 * so the old view's {@code PLOT_WIDTH}/{@code PLOT_HEIGHT} constants become
 * arbitrary width/height arguments here.</p>
 */
class MatchEqEditorPlotMathTest {

    /** Same extent the retired view used, kept for expectation parity. */
    private static final double WIDTH = 560.0;

    /** Same extent the retired view used, kept for expectation parity. */
    private static final double HEIGHT = 240.0;

    @Test
    void plotExtentsShouldBePositive() {
        assertThat(MatchEqEditor.DB_RANGE).isGreaterThan(0.0);
        assertThat(MatchEqEditor.MIN_FREQUENCY_HZ).isGreaterThan(0.0);
        assertThat(MatchEqEditor.MAX_FREQUENCY_HZ)
                .isGreaterThan(MatchEqEditor.MIN_FREQUENCY_HZ);
    }

    @Test
    void frequencyMappingShouldBeMonotonicAndSpanEntirePlot() {
        double left = MatchEqEditor.freqToX(MatchEqEditor.MIN_FREQUENCY_HZ, WIDTH);
        double mid = MatchEqEditor.freqToX(1_000.0, WIDTH);
        double right = MatchEqEditor.freqToX(MatchEqEditor.MAX_FREQUENCY_HZ, WIDTH);
        assertThat(left).isEqualTo(0.0);
        assertThat(right).isEqualTo(WIDTH);
        assertThat(mid).isBetween(left, right);
    }

    @Test
    void frequencyMappingShouldClampOutOfRangeInputs() {
        assertThat(MatchEqEditor.freqToX(0.0, WIDTH)).isEqualTo(0.0);
        assertThat(MatchEqEditor.freqToX(1e9, WIDTH)).isEqualTo(WIDTH);
    }

    @Test
    void dbMappingShouldPlaceZeroAtVerticalCentre() {
        double centre = MatchEqEditor.dbToY(0.0, HEIGHT);
        assertThat(centre).isCloseTo(HEIGHT / 2.0, offset(1e-9));
    }

    @Test
    void dbMappingShouldClampAtExtremes() {
        double top = MatchEqEditor.dbToY(+MatchEqEditor.DB_RANGE, HEIGHT);
        double bottom = MatchEqEditor.dbToY(-MatchEqEditor.DB_RANGE, HEIGHT);
        assertThat(top).isCloseTo(0.0, offset(1e-9));
        assertThat(bottom).isCloseTo(HEIGHT, offset(1e-9));

        // Out-of-range inputs stay clipped.
        assertThat(MatchEqEditor.dbToY(+1_000.0, HEIGHT)).isCloseTo(0.0, offset(1e-9));
        assertThat(MatchEqEditor.dbToY(-1_000.0, HEIGHT)).isCloseTo(HEIGHT, offset(1e-9));
    }
}
