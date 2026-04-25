package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Non-UI-thread tests for {@link TransientShaperPluginView}.
 *
 * <p>Avoids instantiating the {@code VBox} itself (which would require the
 * JavaFX toolkit to be initialised), and instead exercises the compile-time
 * constants and static invariants of the view.</p>
 */
class TransientShaperPluginViewTest {

    @Test
    void levelMeterRangeShouldBeValid() {
        assertThat(TransientShaperPluginView.METER_MAX_DB)
                .isGreaterThan(TransientShaperPluginView.METER_MIN_DB);
    }

    @Test
    void transientMeterRangeShouldBePositive() {
        assertThat(TransientShaperPluginView.TRANSIENT_METER_MAX).isGreaterThan(0.0);
    }
}
