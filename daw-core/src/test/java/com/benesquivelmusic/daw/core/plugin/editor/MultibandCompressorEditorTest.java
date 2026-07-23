package com.benesquivelmusic.daw.core.plugin.editor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Non-UI-thread tests for {@link MultibandCompressorEditor} — the story-302
 * migration of the retired daw-app {@code MultibandCompressorPluginViewTest}.
 *
 * <p>daw-core has no JavaFX toolkit test infrastructure, so these tests
 * exercise only the compile-time meter-range and spectrum-strip constants the
 * painting math is built on; FX-level editor behaviour is covered host-side
 * in daw-app.</p>
 */
class MultibandCompressorEditorTest {

    @Test
    void meterRangeShouldBePositive() {
        assertThat(MultibandCompressorEditor.METER_MAX_DB).isGreaterThan(0.0);
    }

    @Test
    void spectrumStripDimensionsShouldBePositive() {
        assertThat(MultibandCompressorEditor.SPECTRUM_WIDTH).isGreaterThan(0.0);
        assertThat(MultibandCompressorEditor.SPECTRUM_HEIGHT).isGreaterThan(0.0);
    }

    @Test
    void frequencyRangeShouldCoverAudibleSpectrum() {
        assertThat(MultibandCompressorEditor.MIN_FREQUENCY_HZ).isLessThanOrEqualTo(20.0);
        assertThat(MultibandCompressorEditor.MAX_FREQUENCY_HZ).isGreaterThanOrEqualTo(20_000.0);
    }
}
