package com.benesquivelmusic.daw.core.plugin.editor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Non-UI-thread tests for {@link TransientShaperEditor} — the story-302
 * migration of the retired daw-app {@code TransientShaperPluginViewTest}.
 *
 * <p>daw-core has no JavaFX toolkit test infrastructure, so these tests
 * exercise only the compile-time meter-range constants the painting math is
 * built on; FX-level editor behaviour is covered host-side in daw-app.</p>
 */
class TransientShaperEditorTest {

    @Test
    void levelMeterRangeShouldBeValid() {
        assertThat(TransientShaperEditor.METER_MAX_DB)
                .isGreaterThan(TransientShaperEditor.METER_MIN_DB);
    }

    @Test
    void transientMeterRangeShouldBePositive() {
        assertThat(TransientShaperEditor.TRANSIENT_METER_MAX).isGreaterThan(0.0);
    }
}
