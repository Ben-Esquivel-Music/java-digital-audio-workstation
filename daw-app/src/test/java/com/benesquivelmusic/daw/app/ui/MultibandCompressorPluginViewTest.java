package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Non-UI-thread tests for {@link MultibandCompressorPluginView}.
 *
 * <p>Avoids instantiating the {@code VBox} itself (which would require the
 * JavaFX toolkit to be initialised), and instead exercises the compile-time
 * constants and static invariants of the view.</p>
 */
class MultibandCompressorPluginViewTest {

    @Test
    void meterRangeShouldBePositive() {
        assertThat(MultibandCompressorPluginView.METER_MAX_DB).isGreaterThan(0.0);
    }

    @Test
    void spectrumStripDimensionsShouldBePositive() {
        assertThat(MultibandCompressorPluginView.SPECTRUM_WIDTH).isGreaterThan(0.0);
        assertThat(MultibandCompressorPluginView.SPECTRUM_HEIGHT).isGreaterThan(0.0);
    }

    @Test
    void frequencyRangeShouldCoverAudibleSpectrum() {
        assertThat(MultibandCompressorPluginView.MIN_FREQUENCY_HZ).isLessThanOrEqualTo(20.0);
        assertThat(MultibandCompressorPluginView.MAX_FREQUENCY_HZ).isGreaterThanOrEqualTo(20_000.0);
    }
}
