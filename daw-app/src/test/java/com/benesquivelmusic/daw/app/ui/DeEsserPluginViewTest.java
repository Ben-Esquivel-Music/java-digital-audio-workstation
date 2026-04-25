package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Non-UI-thread tests for {@link DeEsserPluginView}.
 *
 * <p>Mirrors {@link BusCompressorPluginViewTest}: avoids instantiating the
 * {@code VBox} (which would require the JavaFX toolkit), and instead exercises
 * compile-time constants and static invariants of the view.</p>
 */
class DeEsserPluginViewTest {

    @Test
    void meterRangeShouldBePositive() {
        assertThat(DeEsserPluginView.METER_MAX_DB).isGreaterThan(0.0);
    }
}
