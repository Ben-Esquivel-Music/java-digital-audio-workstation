package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Non-UI-thread tests for {@link BusCompressorPluginView}.
 *
 * <p>Avoids instantiating the {@code VBox} itself (which would require the
 * JavaFX toolkit to be initialised), and instead exercises the compile-time
 * constants and static invariants of the view.</p>
 */
class BusCompressorPluginViewTest {

    @Test
    void meterRangeShouldBePositive() {
        assertThat(BusCompressorPluginView.METER_MAX_DB).isGreaterThan(0.0);
    }
}
