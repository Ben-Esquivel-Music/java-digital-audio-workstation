package com.benesquivelmusic.daw.core.plugin.editor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-math tests for {@link ArpeggiatorEditor}'s static step-indicator
 * cell mapping (story 302 §8.3 item 7 — the editor folding the orphaned
 * daw-app {@code ArpeggiatorPluginView} onto the SDK contract). Exercises
 * only the package-private static — no JavaFX toolkit, no control or canvas
 * construction.
 */
class ArpeggiatorEditorLayoutTest {

    @Test
    void indicatorShouldHaveSixteenSteps() {
        assertThat(ArpeggiatorEditor.INDICATOR_STEPS).isEqualTo(16);
    }

    @Test
    void negativeStepShouldLightNoCell() {
        assertThat(ArpeggiatorEditor.activeIndicatorCell(-1)).isEqualTo(-1);
        assertThat(ArpeggiatorEditor.activeIndicatorCell(-7)).isEqualTo(-1);
    }

    @Test
    void stepsWithinFirstBarShouldMapDirectly() {
        assertThat(ArpeggiatorEditor.activeIndicatorCell(0)).isEqualTo(0);
        assertThat(ArpeggiatorEditor.activeIndicatorCell(5)).isEqualTo(5);
        assertThat(ArpeggiatorEditor.activeIndicatorCell(15)).isEqualTo(15);
    }

    @Test
    void stepsShouldWrapAroundTheIndicator() {
        assertThat(ArpeggiatorEditor.activeIndicatorCell(16)).isEqualTo(0);
        assertThat(ArpeggiatorEditor.activeIndicatorCell(17)).isEqualTo(1);
        assertThat(ArpeggiatorEditor.activeIndicatorCell(35)).isEqualTo(3);
    }
}
